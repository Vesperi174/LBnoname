package com.lbthreecountry.service.impl;

import com.lbthreecountry.entity.GameRoom;
import com.lbthreecountry.entity.RoomPlayer;
import com.lbthreecountry.game.GameMatch;
import com.lbthreecountry.game.GamePlayer;
import com.lbthreecountry.game.event.EventBus;
import com.lbthreecountry.game.event.GameEvent;
import com.lbthreecountry.game.event.GameEventType;
import com.lbthreecountry.model.enums.impl.GamePhase;
import com.lbthreecountry.model.enums.impl.GameStatus;
import com.lbthreecountry.model.enums.impl.PlayerStatus;
import com.lbthreecountry.model.enums.impl.RoleType;
import com.lbthreecountry.model.enums.impl.RoomStatus;
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
        GameRoom room = roomService.getRoom(roomId);
        if (room == null) throw new IllegalStateException("房间不存在: " + roomId);
        if (room.getStatus() != RoomStatus.WAITING)
            throw new IllegalStateException("房间状态不是等待中，无法开始游戏");
        if (room.getPlayerCount() < 2)
            throw new IllegalStateException("至少需要 2 名玩家才能开始");
        if (room.getPlayers().stream().anyMatch(p -> !p.isReady()))
            throw new IllegalStateException("还有玩家未准备");

        // 创建 GamePlayer 列表
        List<GamePlayer> gamePlayers = new ArrayList<>();
        for (RoomPlayer rp : room.getPlayers()) {
            gamePlayers.add(GamePlayer.builder()
                    .playerId(rp.getPlayerId())
                    .playerName(rp.getPlayerName())
                    .roomSeat(rp.getSeatNumber())
                    .status(PlayerStatus.ALIVE)
                    .handCards(new ArrayList<>())
                    .equipCards(new ArrayList<>())
                    .judgeArea(new ArrayList<>())
                    .flags(new HashMap<>())
                    .build());
        }

        // 随机分配游戏座位
        Collections.shuffle(gamePlayers);
        for (int i = 0; i < gamePlayers.size(); i++) gamePlayers.get(i).setGameSeat(i);
        gamePlayers.sort(Comparator.comparingInt(GamePlayer::getGameSeat));

        // 分配身份与初始体力
        assignRoles(gamePlayers);
        for (GamePlayer gp : gamePlayers) {
            gp.setMaxHp(4);
            gp.setCurrentHp(4);
        }

        // 发起始手牌（主公多摸1张）
        for (GamePlayer gp : gamePlayers) {
            int drawCount = (gp.getGameSeat() == 0) ? 5 : 4;
            for (int d = 0; d < drawCount; d++) gp.getHandCards().add(null);
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

        log.info("[游戏] 对局创建成功 [roomId={}, 人数={}]", roomId, gamePlayers.size());

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
    //  私有方法
    // ================================================================

    private void assignRoles(List<GamePlayer> players) {
        int count = players.size();
        List<RoleType> template = ROLE_TEMPLATES.get(count);
        if (template == null) throw new IllegalStateException("不支持的玩家人数: " + count);

        List<RoleType> shuffled = new ArrayList<>(template);
        RoleType lordRole = shuffled.remove(0);
        Collections.shuffle(shuffled);

        for (GamePlayer gp : players) {
            if (gp.getGameSeat() == 0) gp.setRole(lordRole);
        }

        int idx = 0;
        for (GamePlayer gp : players) {
            if (gp.getGameSeat() != 0) gp.setRole(shuffled.get(idx++));
        }
    }
}