package com.lbthreecountry.service.impl;

import com.lbthreecountry.entity.GameRoom;
import com.lbthreecountry.entity.RoomPlayer;
import com.lbthreecountry.game.GameMatch;
import com.lbthreecountry.game.GamePlayer;
import com.lbthreecountry.game.card.CardManager;
import com.lbthreecountry.game.card.EffectEngine;
import com.lbthreecountry.game.hero.HeroManager;
import com.lbthreecountry.game.event.DrawCardEvent;
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
import com.lbthreecountry.model.hero.BaseHero;
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
    private final HeroManager heroManager;

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

    private void publishPhaseHook(GameMatch match, String eventType, GamePhase phase) {
        GamePlayer player = match.currentPlayer();
        GameEvent event = GameEvent.builder()
                .type(eventType)
                .sourceId(player != null ? player.getPlayerId() : "system")
                .build();
        event.putData("roomId", match.getRoomId());
        event.putData("phase", phase.name());
        event.putData("gameSeat", match.getCurrentPlayerIndex());
        if (player != null) {
            event.putData("playerId", player.getPlayerId());
            event.putData("playerName", player.getPlayerName());
        }
        eventBus.publish(event, match);
        String playerName = player != null ? player.getPlayerName() : "系统";
        log.info("[钩子] {} — {}: {}", eventType, playerName, phase.getDescription());
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
            GamePlayer oldPlayer = match.currentPlayer();
            GamePhase oldPhase = match.getCurrentPhase();

            // 1) 如果当前还在 END 阶段，补发 END.END / END.AFTER
            if (oldPhase == GamePhase.END) {
                publishPhaseHook(match, GameEventType.phaseEnd(oldPhase), oldPhase);
                publishPhaseHook(match, GameEventType.phaseAfter(oldPhase), oldPhase);
            }

            // 2) 当前玩家回合结束
            if (oldPlayer != null) {
                // 2a) TURN_END：回合结束时
                GameEvent turnEnd = GameEvent.builder()
                        .type(GameEventType.TURN_END)
                        .sourceId(oldPlayer.getPlayerId())
                        .build();
                turnEnd.putData("roomId", roomId);
                turnEnd.putData("playerId", oldPlayer.getPlayerId());
                turnEnd.putData("playerName", oldPlayer.getPlayerName());
                turnEnd.putData("gameSeat", match.getCurrentPlayerIndex());
                eventBus.publish(turnEnd, match);
                log.info("[钩子] {} — {} 回合结束时",
                        GameEventType.TURN_END, oldPlayer.getPlayerName());

                // 2b) TURN_AFTER：回合结束后
                GameEvent turnAfter = GameEvent.builder()
                        .type(GameEventType.TURN_AFTER)
                        .sourceId(oldPlayer.getPlayerId())
                        .build();
                turnAfter.putData("roomId", roomId);
                turnAfter.putData("playerId", oldPlayer.getPlayerId());
                turnAfter.putData("playerName", oldPlayer.getPlayerName());
                turnAfter.putData("gameSeat", match.getCurrentPlayerIndex());
                eventBus.publish(turnAfter, match);
                log.info("[钩子] {} — {} 回合结束后",
                        GameEventType.TURN_AFTER, oldPlayer.getPlayerName());
            }

            // 3) 切换到下一玩家
            int nextIndex = match.nextAlivePlayerIndex(match.getCurrentPlayerIndex());
            match.setCurrentPlayerIndex(nextIndex);
            match.setCurrentPhase(GamePhase.PREPARE);
            match.setTotalTurns(match.getTotalTurns() + 1);

            GamePlayer newPlayer = match.currentPlayer();

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

            if (newPlayer != null) newPlayer.setHasPlayedSha(false);

            String pn = newPlayer != null ? newPlayer.getPlayerName() : "?";
            log.info("[回合] 轮到 {} (座位 {}) — 第 {} 回合", pn, nextIndex, match.getCurrentRound());

            // 4) 新玩家回合开始前
            if (newPlayer != null) {
                GameEvent turnBefore = GameEvent.builder()
                        .type(GameEventType.TURN_BEFORE)
                        .sourceId(newPlayer.getPlayerId())
                        .build();
                turnBefore.putData("roomId", roomId);
                turnBefore.putData("playerId", newPlayer.getPlayerId());
                turnBefore.putData("playerName", newPlayer.getPlayerName());
                turnBefore.putData("gameSeat", nextIndex);
                turnBefore.putData("round", match.getCurrentRound());
                turnBefore.putData("totalTurns", match.getTotalTurns());
                eventBus.publish(turnBefore, match);
                log.info("[钩子] {} — {} 回合开始前",
                        GameEventType.TURN_BEFORE, newPlayer.getPlayerName());

                // 4b) TURN_ACTIVE：回合进行中
                GameEvent turnActive = GameEvent.builder()
                        .type(GameEventType.TURN_ACTIVE)
                        .sourceId(newPlayer.getPlayerId())
                        .build();
                turnActive.putData("roomId", roomId);
                turnActive.putData("playerId", newPlayer.getPlayerId());
                turnActive.putData("playerName", newPlayer.getPlayerName());
                turnActive.putData("gameSeat", nextIndex);
                turnActive.putData("round", match.getCurrentRound());
                turnActive.putData("totalTurns", match.getTotalTurns());
                eventBus.publish(turnActive, match);
                log.info("[钩子] {} — {} 回合进行中",
                        GameEventType.TURN_ACTIVE, newPlayer.getPlayerName());
            }

            // 5) 新玩家的 PREPARE 阶段开始前 / 进行中
            publishPhaseHook(match, GameEventType.phaseBefore(GamePhase.PREPARE), GamePhase.PREPARE);
            publishPhaseHook(match, GameEventType.phaseActive(GamePhase.PREPARE), GamePhase.PREPARE);

            // 6) 旧版 TURN_START 事件（兼容已有监听器）
            GameEvent turnEvent = GameEvent.builder()
                    .type(GameEventType.TURN_START)
                    .sourceId(newPlayer != null ? newPlayer.getPlayerId() : "unknown")
                    .build();
            turnEvent.putData("roomId", roomId);
            turnEvent.putData("gameSeat", nextIndex);
            turnEvent.putData("playerName", newPlayer != null ? newPlayer.getPlayerName() : "?");
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
                case END     -> GamePhase.END;
            };

            // 已在结束阶段，此时需触发 END 钩子 + TURN.END/TURN.AFTER，让调用方切回合
            if (current == GamePhase.END) {
                publishPhaseHook(match, GameEventType.phaseEnd(current), current);
                publishPhaseHook(match, GameEventType.phaseAfter(current), current);

                // TURN.END：当前玩家回合结束时
                GamePlayer curPlayer = match.currentPlayer();
                if (curPlayer != null) {
                    GameEvent turnEnd = GameEvent.builder()
                            .type(GameEventType.TURN_END)
                            .sourceId(curPlayer.getPlayerId())
                            .build();
                    turnEnd.putData("roomId", roomId);
                    turnEnd.putData("playerId", curPlayer.getPlayerId());
                    turnEnd.putData("playerName", curPlayer.getPlayerName());
                    turnEnd.putData("gameSeat", match.getCurrentPlayerIndex());
                    eventBus.publish(turnEnd, match);
                    log.info("[钩子] {} — {} 回合结束时",
                            GameEventType.TURN_END, curPlayer.getPlayerName());
                }

                // TURN.AFTER：当前玩家回合结束后
                if (curPlayer != null) {
                    GameEvent turnAfter = GameEvent.builder()
                            .type(GameEventType.TURN_AFTER)
                            .sourceId(curPlayer.getPlayerId())
                            .build();
                    turnAfter.putData("roomId", roomId);
                    turnAfter.putData("playerId", curPlayer.getPlayerId());
                    turnAfter.putData("playerName", curPlayer.getPlayerName());
                    turnAfter.putData("gameSeat", match.getCurrentPlayerIndex());
                    eventBus.publish(turnAfter, match);
                    log.info("[钩子] {} — {} 回合结束后",
                            GameEventType.TURN_AFTER, curPlayer.getPlayerName());
                }

                throw new IllegalStateException("当前阶段已是结束阶段，请等待换回合");
            }

            // 1) 当前阶段结束时 / 结束后
            publishPhaseHook(match, GameEventType.phaseEnd(current), current);
            publishPhaseHook(match, GameEventType.phaseAfter(current), current);

            // 2) 推进到下一阶段
            match.setCurrentPhase(next);
            GamePlayer cur = match.currentPlayer();
            String curName = cur != null ? cur.getPlayerName() : "?";
            log.info("[阶段] {}: {} → {}", curName, current.name(), next.name());

            // 3) 下一阶段开始前 / 进行中
            publishPhaseHook(match, GameEventType.phaseBefore(next), next);
            publishPhaseHook(match, GameEventType.phaseActive(next), next);

            // 4) 旧版 PHASE_CHANGE 事件（兼容已有监听器）
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

    @Override
    public GameMatch drawCards(String roomId, int count) {
        GameMatch match = getMatch(roomId);
        if (match == null) throw new IllegalStateException("对局不存在: " + roomId);

        match.lock();
        try {
            GamePlayer player = match.currentPlayer();
            if (player == null) throw new IllegalStateException("当前回合没有玩家");

            // 使用 DrawCardEvent 执行完整的摸牌生命周期
            DrawCardEvent.DrawResult result = DrawCardEvent.execute(
                    match, player, count, eventBus, cardManager
            );

            if (result.isCancelled()) {
                log.info("[摸牌] {} 的摸牌被取消", player.getPlayerName());
            } else {
                log.info("[摸牌] {} 摸了 {} 张牌 (请求: {} 张)",
                        player.getPlayerName(), result.getActualCount(), count);
            }

            return match;
        } finally {
            match.unlock();
        }
    }

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
            log.info("[游戏] 对局结束 — 胜者: {}", winnerDesc);

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
        log.info("[游戏] 对局已清理");
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

            log.info("[出牌] {} 使用 {} → 目标: {}",
                    player.getPlayerName(), cardDef.getName(), targetIds);

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

            log.info("[出牌] {} 使用 {} 完成", player.getPlayerName(), cardDef.getName());

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
                    .bot(rp.isBot())
                    .status(PlayerStatus.ALIVE)
                    .handCards(new ArrayList<>())
                    .equipCards(new ArrayList<>())
                    .judgeArea(new ArrayList<>())
                    .flags(new HashMap<>())
                    .build());
        }

        // 1) 将所有玩家随机排序，决定游戏座位号
        Collections.shuffle(gamePlayers);
        for (int i = 0; i < gamePlayers.size(); i++) {
            gamePlayers.get(i).setGameSeat(i);
        }

        // 2) 排第一的玩家（gameSeat 0）为主公，其余玩家按模板随机分配身份
        gamePlayers.get(0).setRole(RoleType.LORD);
        assignRolesToRemaining(gamePlayers, identityConfig);

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
                .status(GameStatus.HERO_SELECT)
                .build();

        room.setStatus(RoomStatus.IN_PROGRESS);
        matchMap.put(roomId, match);

        // 初始化牌堆（从 JSON 展开副本、洗牌）
        cardManager.initDeck(match);

        // 发起始手牌由 distributeInitialHands() 在武将选择完成后执行
        log.info("[游戏] 对局创建成功 ({} 人, {}), 等待主公选择武将", gamePlayers.size(), identityConfig);

        // 发布 GAME_START 事件（不启动回合，等待武将选择完成）
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
    //  武将选择
    // ================================================================

    @Override
    public GameMatch selectHero(String roomId, String playerId, String heroId) {
        GameMatch match = getMatch(roomId);
        if (match == null) throw new IllegalStateException("对局不存在");
        match.lock();
        try {
            if (match.getStatus() != GameStatus.HERO_SELECT) {
                throw new IllegalStateException("当前不在武将选择阶段");
            }

            GamePlayer player = match.findPlayer(playerId);
            if (player == null) throw new IllegalStateException("玩家不在对局中");
            if (player.getHeroId() != null) {
                throw new IllegalStateException("已经选择过武将了");
            }

            player.setHeroId(heroId);

            log.info("[武将选择] {} {} 选择了武将 [{}]",
                    player.getRole() == RoleType.LORD ? "主公" : "玩家",
                    player.getPlayerName(), heroId);

            return match;
        } finally {
            match.unlock();
        }
    }

    @Override
    public GameMatch finalizeHeroSelection(String roomId) {
        GameMatch match = getMatch(roomId);
        if (match == null) throw new IllegalStateException("对局不存在");
        match.lock();
        try {
            if (match.getStatus() != GameStatus.HERO_SELECT) {
                throw new IllegalStateException("当前不在武将选择阶段");
            }

            for (GamePlayer gp : match.getPlayers()) {
                if (gp.getHeroId() == null) {
                    throw new IllegalStateException("玩家 " + gp.getPlayerName() + " 还未选择武将");
                }
            }

            log.info("[武将选择] 所有玩家武将分配完成");

            match.setStatus(GameStatus.PLAYING);
            match.setCurrentPhase(GamePhase.PREPARE);
            match.setCurrentRound(1);
            match.setTotalTurns(1);
            match.setCurrentPlayerIndex(0);

            return match;
        } finally {
            match.unlock();
        }
    }

    @Override
    public GameMatch distributeInitialHands(String roomId) {
        GameMatch match = getMatch(roomId);
        if (match == null) throw new IllegalStateException("对局不存在: " + roomId);

        match.lock();
        try {
            // ── 1. 发布"分发初始手牌"事件钩子（可被监听、修改） ──
            GameEvent initDrawEvent = GameEvent.builder()
                    .type(GameEventType.CARD_INITIAL_DRAW)
                    .sourceId("system")
                    .build();
            initDrawEvent.putData("roomId", roomId);

            List<Map<String, Object>> playersInfo = new java.util.ArrayList<>();
            for (GamePlayer gp : match.getPlayers()) {
                playersInfo.add(Map.of(
                        "playerId", gp.getPlayerId(),
                        "playerName", gp.getPlayerName(),
                        "gameSeat", gp.getGameSeat()
                ));
            }
            initDrawEvent.putData("players", playersInfo);
            initDrawEvent.putData("count", 4);  // 默认每人4张，可被监听器修改
            eventBus.publish(initDrawEvent, match);
            log.info("[初始手牌] {} 事件已发布，默认每人 4 张", GameEventType.CARD_INITIAL_DRAW);

            // ── 2. 读取可能被修改后的摸牌数 ──
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> modifiedPlayers =
                    (List<Map<String, Object>>) initDrawEvent.getDataOrDefault("players", playersInfo);
            int drawCount = initDrawEvent.getDataOrDefault("count", 4);

            // ── 3. 直接执行摸牌行为，不触发摸牌事件生命周期 ──
            //     （此为例外：分发初始手牌只调用 cardManager.draw()，绕过 DrawCardEvent）
            for (Map<String, Object> playerInfo : modifiedPlayers) {
                String playerId = (String) playerInfo.get("playerId");
                GamePlayer gp = match.getPlayers().stream()
                        .filter(p -> p.getPlayerId().equals(playerId))
                        .findFirst()
                        .orElse(null);
                if (gp == null) continue;

                cardManager.draw(match, gp, drawCount);
                log.info("[初始手牌] {} 摸了 {} 张牌", gp.getPlayerName(), drawCount);
            }

            return match;
        } finally {
            match.unlock();
        }
    }

    @Override
    public void initiateFirstTurn(String roomId) {
        GameMatch match = getMatch(roomId);
        if (match == null) throw new IllegalStateException("对局不存在");
        match.lock();
        try {
            GamePlayer firstPlayer = match.currentPlayer();
            if (firstPlayer == null) return;

            GameEvent turnBefore = GameEvent.builder()
                    .type(GameEventType.TURN_BEFORE)
                    .sourceId(firstPlayer.getPlayerId())
                    .build();
            turnBefore.putData("roomId", roomId);
            turnBefore.putData("playerId", firstPlayer.getPlayerId());
            turnBefore.putData("playerName", firstPlayer.getPlayerName());
            turnBefore.putData("gameSeat", firstPlayer.getGameSeat());
            turnBefore.putData("round", match.getCurrentRound());
            turnBefore.putData("totalTurns", match.getTotalTurns());
            eventBus.publish(turnBefore, match);
            log.info("[钩子] {} — {} 回合开始前",
                    GameEventType.TURN_BEFORE, firstPlayer.getPlayerName());

            GameEvent turnActive = GameEvent.builder()
                    .type(GameEventType.TURN_ACTIVE)
                    .sourceId(firstPlayer.getPlayerId())
                    .build();
            turnActive.putData("roomId", roomId);
            turnActive.putData("playerId", firstPlayer.getPlayerId());
            turnActive.putData("playerName", firstPlayer.getPlayerName());
            turnActive.putData("gameSeat", firstPlayer.getGameSeat());
            turnActive.putData("round", match.getCurrentRound());
            turnActive.putData("totalTurns", match.getTotalTurns());
            eventBus.publish(turnActive, match);
            log.info("[钩子] {} — {} 回合进行中",
                    GameEventType.TURN_ACTIVE, firstPlayer.getPlayerName());

            publishPhaseHook(match, GameEventType.phaseBefore(GamePhase.PREPARE), GamePhase.PREPARE);
            publishPhaseHook(match, GameEventType.phaseActive(GamePhase.PREPARE), GamePhase.PREPARE);
        } finally {
            match.unlock();
        }
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
     * 为剩余玩家（非主公）随机分配身份
     * <p>主公（gameSeat 0）已确定，从模板中移除主公后打乱分配给其余玩家。</p>
     */
    private void assignRolesToRemaining(List<GamePlayer> players, String identityConfig) {
        int count = players.size();
        List<RoleType> template = getRoleTemplate(count, identityConfig);

        // 移除主公，剩余身份打乱
        List<RoleType> remainingRoles = new ArrayList<>(template);
        remainingRoles.remove(RoleType.LORD);
        Collections.shuffle(remainingRoles);

        // 从 gameSeat 1 开始分配
        for (int i = 1; i < players.size(); i++) {
            players.get(i).setRole(remainingRoles.get(i - 1));
        }
    }
}