package com.lbthreecountry.service.impl;

import com.lbthreecountry.entity.GameRoom;
import com.lbthreecountry.entity.RoomPlayer;
import com.lbthreecountry.game.GameMatch;
import com.lbthreecountry.game.GamePlayer;
import com.lbthreecountry.game.card.CardManager;
import com.lbthreecountry.game.card.EffectEngine;
import com.lbthreecountry.game.event.EventBus;
import com.lbthreecountry.game.event.GameEvent;
import com.lbthreecountry.game.event.GameEventType;
import com.lbthreecountry.model.card.CardInstance;
import com.lbthreecountry.model.card.def.CardDef;
import com.lbthreecountry.model.enums.impl.GamePhase;
import com.lbthreecountry.model.enums.impl.GameStatus;
import com.lbthreecountry.model.enums.impl.PlayerStatus;
import com.lbthreecountry.model.enums.impl.RoleType;
import com.lbthreecountry.model.enums.impl.RoomStatus;
import com.lbthreecountry.model.player.PlayerInfo;
import com.lbthreecountry.service.GameService;
import com.lbthreecountry.service.RoomService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 游戏服务实现 — 纯内存，无数据库
 *
 * <p>对局用 {@link ConcurrentHashMap} 存储，roomId → GameMatch。
 * 所有修改对局状态的操作都通过 {@link GameMatch#lock()} 保证线程安全。</p>
 */
@Service
@RequiredArgsConstructor
public class GameServiceImpl implements GameService {

    private static final Logger log = LoggerFactory.getLogger(GameServiceImpl.class);

    private final RoomService roomService;
    private final EventBus eventBus;
    private final CardManager cardManager;
    private final EffectEngine effectEngine;

    /** roomId → GameMatch */
    private final Map<String, GameMatch> matchMap = new ConcurrentHashMap<>();

    /** 身份分配模板：人数 → 身份列表（顺序固定，分配时打乱） */
    private static final Map<Integer, List<RoleType>> ROLE_TEMPLATES = new HashMap<>();

    static {
        ROLE_TEMPLATES.put(2, List.of(RoleType.LORD, RoleType.REBEL));
        ROLE_TEMPLATES.put(3, List.of(RoleType.LORD, RoleType.MINION, RoleType.REBEL));
        ROLE_TEMPLATES.put(4, List.of(RoleType.LORD, RoleType.MINION, RoleType.REBEL, RoleType.INTRUDER));
        ROLE_TEMPLATES.put(5, List.of(RoleType.LORD, RoleType.MINION, RoleType.REBEL, RoleType.REBEL, RoleType.INTRUDER));
        ROLE_TEMPLATES.put(6, List.of(RoleType.LORD, RoleType.MINION, RoleType.REBEL, RoleType.REBEL, RoleType.REBEL, RoleType.INTRUDER));
        ROLE_TEMPLATES.put(7, List.of(RoleType.LORD, RoleType.MINION, RoleType.MINION, RoleType.REBEL, RoleType.REBEL, RoleType.REBEL, RoleType.INTRUDER));
        ROLE_TEMPLATES.put(8, List.of(RoleType.LORD, RoleType.MINION, RoleType.MINION, RoleType.REBEL, RoleType.REBEL, RoleType.REBEL, RoleType.REBEL, RoleType.INTRUDER));
    }

    // ================================================================
    //  开始游戏
    // ================================================================

    @Override
    public GameMatch startGame(String roomId) {
        return startGame(roomId, "standard");
    }

    // ================================================================
    //  回合 / 阶段
    // ================================================================

    @Override
    public GameMatch nextTurn(String roomId) {
        GameMatch match = getMatch(roomId);
        if (match == null) throw new IllegalStateException("对局不存在: " + roomId);

        match.lock();
        try {
            int nextIndex = match.nextAlivePlayerIndex(match.getCurrentPlayerIndex());
            match.setCurrentPlayerIndex(nextIndex);
            match.setCurrentPhase(GamePhase.PREPARE);
            match.setTotalTurns(match.getTotalTurns() + 1);

            if (nextIndex == 0) {
                match.setCurrentRound(match.getCurrentRound() + 1);
                GameEvent roundEvent = GameEvent.builder()
                        .type(GameEventType.ROUND_CHANGE)
                        .sourceId("system")
                        .build();
                roundEvent.putData("roomId", roomId);
                roundEvent.putData("round", match.getCurrentRound());
                eventBus.publish(roundEvent, match);
            }

            GamePlayer currentPlayer = match.currentPlayer();
            if (currentPlayer != null) currentPlayer.setHasPlayedSha(false);

            log.info("[回合] 轮到玩家 [roomId={}, gameSeat={}, playerName={}, round={}]",
                    roomId, nextIndex,
                    currentPlayer != null ? currentPlayer.getPlayerName() : "?",
                    match.getCurrentRound());

            GameEvent turnEvent = GameEvent.builder()
                    .type(GameEventType.TURN_START)
                    .sourceId(currentPlayer != null ? currentPlayer.getPlayerId() : "unknown")
                    .build();
            turnEvent.putData("roomId", roomId);
            turnEvent.putData("gameSeat", nextIndex);
            turnEvent.putData("playerName", currentPlayer != null ? currentPlayer.getPlayerName() : "?");
            turnEvent.putData("round", match.getCurrentRound());
            turnEvent.putData("totalTurns", match.getTotalTurns());
            turnEvent.putData("phase", match.getCurrentPhase().name());
            eventBus.publish(turnEvent, match);

            return match;
        } finally {
            match.unlock();
        }
    }

    @Override
    public GameMatch nextPhase(String roomId) {
        GameMatch match = getMatch(roomId);
        if (match == null) throw new IllegalStateException("对局不存在: " + roomId);

        match.lock();
        try {
            GamePhase current = match.getCurrentPhase();
            GamePhase next = switch (current) {
                case PREPARE -> GamePhase.JUDGE;
                case JUDGE   -> GamePhase.DRAW;
                case DRAW    -> GamePhase.PLAY;
                case PLAY    -> GamePhase.DISCARD;
                case DISCARD -> GamePhase.END;
                case END     -> GamePhase.END; // 已是结束阶段则不变
            };

            // 已在结束阶段，拒绝继续推进
            if (current == GamePhase.END) {
                throw new IllegalStateException("当前阶段已是结束阶段，请等待换回合");
            }

            match.setCurrentPhase(next);

            log.info("[阶段] {} → {} [roomId={}]", current.name(), next.name(), roomId);

            GameEvent phaseEvent = GameEvent.builder()
                    .type(GameEventType.PHASE_CHANGE)
                    .sourceId("system")
                    .build();
            phaseEvent.putData("roomId", roomId);
            phaseEvent.putData("fromPhase", current.name());
            phaseEvent.putData("toPhase", next.name());
            phaseEvent.putData("gameSeat", match.getCurrentPlayerIndex());
            eventBus.publish(phaseEvent, match);

            return match;
        } finally {
            match.unlock();
        }
    }

    // ================================================================
    //  结束游戏
    // ================================================================

    @Override
    public GameMatch endGame(String roomId, RoleType winnerRole) {
        GameMatch match = getMatch(roomId);
        if (match == null) throw new IllegalStateException("对局不存在: " + roomId);

        match.lock();
        try {
            match.setStatus(GameStatus.FINISHED);

            GameRoom room = roomService.getRoom(roomId);
            if (room != null) room.setStatus(RoomStatus.WAITING);

            String winnerDesc = winnerRole != null ? winnerRole.getDescription() : "无人（所有玩家离开）";
            log.info("[游戏] 对局结束 [roomId={}, 胜者={}]", roomId, winnerDesc);

            GameEvent endEvent = GameEvent.builder()
                    .type(GameEventType.GAME_OVER)
                    .sourceId("system")
                    .build();
            endEvent.putData("roomId", roomId);
            endEvent.putData("winnerRole", winnerRole != null ? winnerRole.name() : "NONE");
            endEvent.putData("winnerDesc", winnerDesc);
            eventBus.publish(endEvent, match);

            return match;
        } finally {
            match.unlock();
        }
    }

    // ================================================================
    //  查询 & 清理
    // ================================================================

    @Override
    public GameMatch getMatch(String roomId) {
        return matchMap.get(roomId);
    }

    @Override
    public void removeMatch(String roomId) {
        matchMap.remove(roomId);
        log.info("[游戏] 对局已清理 [roomId={}]", roomId);
    }

    // ================================================================
    //  使用卡牌
    // ================================================================

    @Override
    public GameMatch playCard(String roomId, String playerId, Long cardInstanceId, List<String> targetIds) {
        GameMatch match = getMatch(roomId);
        if (match == null) throw new IllegalStateException("对局不存在: " + roomId);

        match.lock();
        try {
            // 1. 校验：游戏进行中
            if (match.getStatus() != GameStatus.PLAYING) {
                throw new IllegalStateException("游戏未在进行中");
            }

            // 2. 校验：轮到该玩家
            GamePlayer player = match.currentPlayer();
            if (player == null || !player.getPlayerId().equals(playerId)) {
                throw new IllegalStateException("当前不是你的回合");
            }

            // 3. 校验：出牌阶段
            if (match.getCurrentPhase() != GamePhase.PLAY) {
                throw new IllegalStateException("当前不是出牌阶段");
            }

            // 4. 查找卡牌实例
            CardInstance card = null;
            int cardIndex = -1;
            for (int i = 0; i < player.getHandCards().size(); i++) {
                if (player.getHandCards().get(i).getInstanceId().equals(cardInstanceId)) {
                    card = player.getHandCards().get(i);
                    cardIndex = i;
                    break;
                }
            }
            if (card == null) {
                throw new IllegalStateException("手牌中未找到该卡牌");
            }

            // 5. 获取卡牌定义
            CardDef cardDef = cardManager.getDef(card.getDefId());
            if (cardDef == null) {
                throw new IllegalStateException("卡牌定义未找到: " + card.getDefId());
            }

            log.info("[出牌] {} 使用 {} → 目标: {} [roomId={}]",
                    player.getPlayerName(), cardDef.getName(), targetIds, roomId);

            // 6. 从手牌移除
            player.getHandCards().remove(cardIndex);

            // 7. 执行卡牌效果（在弃牌前执行，效果可能需要引用卡牌）
            List<String> safeTargets = targetIds != null ? targetIds : List.of();
            effectEngine.executeOnUse(cardDef, card, match, playerId, safeTargets);

            // 8. 放入弃牌堆
            card.setOwnerId(null);
            card.setStatus(com.lbthreecountry.model.enums.impl.CardStatus.DISCARD_PILE);
            match.getDiscardPile().add(card);

            // 9. 如果卡牌是【杀】，标记本回合已出杀
            if ("sha".equals(card.getDefId())) {
                player.setHasPlayedSha(true);
            }

            // 10. 发布事件
            GameEvent playEvent = GameEvent.builder()
                    .type(GameEventType.CARD_PLAYED)
                    .sourceId(playerId)
                    .build();
            playEvent.putData("roomId", roomId);
            playEvent.putData("playerId", playerId);
            playEvent.putData("playerName", player.getPlayerName());
            playEvent.putData("cardDefId", card.getDefId());
            playEvent.putData("cardName", cardDef.getName());
            playEvent.putData("suit", card.getSuit() != null ? card.getSuit().name() : null);
            playEvent.putData("point", card.getPoint());
            playEvent.putData("targetIds", safeTargets);
            eventBus.publish(playEvent, match);

            log.info("[出牌] {} 使用 {} 完成 [roomId={}]", player.getPlayerName(), cardDef.getName(), roomId);

            return match;
        } finally {
            match.unlock();
        }
    }

    // ================================================================
    //  私有方法
    // ================================================================

    @Override
    public GameMatch startGame(String roomId, String identityConfig) {
        GameRoom room = roomService.getRoom(roomId);
        if (room == null) throw new IllegalStateException("房间不存在: " + roomId);
        if (room.getStatus() != RoomStatus.WAITING)
            throw new IllegalStateException("房间状态不是等待中，无法开始游戏");
        if (room.getPlayerCount() < 2)
            throw new IllegalStateException("至少需要 2 名玩家才能开始");
        if (room.getPlayers().stream().anyMatch(p -> !p.isReady()))
            throw new IllegalStateException("还有玩家未准备");

        // 创建 GamePlayer 列表（从 RoomPlayer 转换）
        List<GamePlayer> gamePlayers = new ArrayList<>();
        for (RoomPlayer rp : room.getPlayers()) {
            gamePlayers.add(GamePlayer.builder()
                    .playerId(rp.getPlayerId())
                    .playerName(rp.getPlayerName())
                    .roomSeat(rp.getSeatNumber())
                    .bot(rp.isBot())
                    .status(PlayerStatus.ALIVE)
                    .handCards(new ArrayList<>())
                    .equipCards(new ArrayList<>())
                    .judgeArea(new ArrayList<>())
                    .flags(new HashMap<>())
                    .build());
        }

        // 1) 随机分配身份（包含主公，所有人的身份完全随机）
        assignRolesRandomly(gamePlayers, identityConfig);

        // 2) 按主公座位重排：主公 → seat 0，其余玩家随机分配剩余座位
        List<GamePlayer> rearranged = new ArrayList<>();
        GamePlayer lordPlayer = null;
        for (GamePlayer gp : gamePlayers) {
            if (gp.getRole() == RoleType.LORD) {
                lordPlayer = gp;
            } else {
                rearranged.add(gp);
            }
        }
        // 打乱非主公玩家的顺序，避免房主（加入最早）总是 1/2 号位
        Collections.shuffle(rearranged);
        gamePlayers.clear();
        if (lordPlayer != null) gamePlayers.add(lordPlayer);
        gamePlayers.addAll(rearranged);
        for (int i = 0; i < gamePlayers.size(); i++) gamePlayers.get(i).setGameSeat(i);

        // 3) 初始体力
        for (GamePlayer gp : gamePlayers) {
            gp.setMaxHp(4);
            gp.setCurrentHp(4);
        }

        // 构建对局
        GameMatch match = GameMatch.builder()
                .roomId(roomId)
                .players(gamePlayers)
                .currentPlayerIndex(0)
                .currentPhase(GamePhase.PREPARE)
                .drawPile(new ArrayList<>())
                .discardPile(new ArrayList<>())
                .gameOuterPile(new ArrayList<>())
                .otherPile(new ArrayList<>())
                .currentRound(1)
                .totalTurns(1)
                .status(GameStatus.PLAYING)
                .build();

        room.setStatus(RoomStatus.IN_PROGRESS);
        matchMap.put(roomId, match);

        // 初始化牌堆（从 JSON 展开副本、洗牌）
        cardManager.initDeck(match);

        // 发起始手牌（主公多摸1张）
        for (GamePlayer gp : gamePlayers) {
            int drawCount = (gp.getGameSeat() == 0) ? 5 : 4;
            cardManager.draw(match, gp, drawCount);
        }

        log.info("[游戏] 对局创建成功 [roomId={}, 人数={}, config={}]", roomId, gamePlayers.size(), identityConfig);

        // 发布 GAME_START 事件
        GameEvent startEvent = GameEvent.builder()
                .type(GameEventType.GAME_START)
                .sourceId("system")
                .build();
        startEvent.putData("roomId", roomId);
        startEvent.putData("players", gamePlayers.stream()
                .map(gp -> Map.of(
                        "playerId", gp.getPlayerId(),
                        "playerName", gp.getPlayerName(),
                        "gameSeat", gp.getGameSeat(),
                        "role", gp.getRole().name(),
                        "maxHp", gp.getMaxHp(),
                        "currentHp", gp.getCurrentHp(),
                        "handCardCount", gp.getHandCards().size()
                ))
                .toList());
        eventBus.publish(startEvent, match);

        return match;
    }

    // ================================================================
    //  单机模式
    // ================================================================

    @Override
    public GameMatch startSinglePlayer(String playerId, String playerName, int totalPlayers, String identityConfig) {
        // 1. 创建房间（人类玩家为房主）
        PlayerInfo owner = PlayerInfo.builder()
                .playerId(playerId)
                .name(playerName)
                .build();
        GameRoom room = roomService.createRoom(playerName + "的单机局", owner, totalPlayers);
        String roomId = room.getRoomId();

        // 2. 填充 Bot
        for (int i = 1; i < totalPlayers; i++) {
            String botId = "bot_sp_" + roomId + "_" + i;
            String botName = "机器人" + i;
            PlayerInfo botInfo = PlayerInfo.builder()
                    .playerId(botId)
                    .name(botName)
                    .build();
            roomService.joinRoom(roomId, botInfo);
            // 标记 Bot 为已准备
            GameRoom r = roomService.getRoom(roomId);
            r.getPlayers().stream()
                    .filter(p -> p.getPlayerId().equals(botId))
                    .findFirst()
                    .ifPresent(p -> {
                        p.setReady(true);
                        p.setBot(true);
                    });
        }

        // 3. 人类玩家已准备（房主默认 ready）
        room.getPlayers().stream()
                .filter(p -> p.getPlayerId().equals(playerId))
                .findFirst()
                .ifPresent(p -> p.setReady(true));

        // 4. 启动游戏
        return startGame(roomId, identityConfig);
    }

    // ================================================================
    //  私有方法
    // ================================================================

    /**
     * 根据人数和身份配置获取身份模板
     */
    private List<RoleType> getRoleTemplate(int count, String config) {
        if ("double_intruder".equals(config) && count == 8) {
            return List.of(
                    RoleType.LORD, RoleType.MINION, RoleType.MINION,
                    RoleType.REBEL, RoleType.REBEL, RoleType.REBEL,
                    RoleType.INTRUDER, RoleType.INTRUDER
            );
        }
        List<RoleType> template = ROLE_TEMPLATES.get(count);
        if (template == null) throw new IllegalStateException("不支持的玩家人数: " + count);
        return template;
    }

    /**
     * 完全随机分配身份：所有身份（含主公）打乱后随机分给每个玩家，与座位号无关
     */
    private void assignRolesRandomly(List<GamePlayer> players, String identityConfig) {
        int count = players.size();
        List<RoleType> template = getRoleTemplate(count, identityConfig);

        List<RoleType> shuffled = new ArrayList<>(template);
        Collections.shuffle(shuffled);

        for (int i = 0; i < players.size(); i++) {
            players.get(i).setRole(shuffled.get(i));
        }
    }
}