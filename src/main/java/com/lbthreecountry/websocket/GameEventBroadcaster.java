package com.lbthreecountry.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lbthreecountry.entity.GameRoom;
import com.lbthreecountry.game.GameMatch;
import com.lbthreecountry.game.event.EventBus;
import com.lbthreecountry.game.event.GameEvent;
import com.lbthreecountry.game.event.GameEventType;
import com.lbthreecountry.model.enums.impl.GamePhase;
import com.lbthreecountry.service.RoomService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 游戏事件广播器 — 监听游戏引擎的 EventBus 事件，向前端推送战报（BATTLE_REPORT）。
 *
 * <p>职责：</p>
 * <ul>
 *   <li>监听每个阶段的 ACTIVE 钩子（PHASE.ACTIVE.*）→ 推送「进入某阶段」战报</li>
 *   <li>监听回合钩子（TURN.BEFORE / TURN.ACTIVE / TURN.END / TURN.AFTER）→ 推送回合状态战报</li>
 *   <li>监听游戏开始/结束（GAME.START / GAME.OVER）→ 推送游戏状态战报</li>
 * </ul>
 *
 * <p>这个类是前端战报的唯一推送入口，其他模块不应再直接广播 BATTLE_REPORT。</p>
 */
@Component
public class GameEventBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(GameEventBroadcaster.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final Map<String, String> PHASE_CN = Map.of(
            "PREPARE", "准备阶段",
            "JUDGE", "判定阶段",
            "DRAW", "摸牌阶段",
            "PLAY", "出牌阶段",
            "DISCARD", "弃牌阶段",
            "END", "结束阶段"
    );

    private final EventBus eventBus;
    private final RoomService roomService;
    private final WebSocketSessionManager sessionManager;

    /** 需要监听的所有事件类型列表 */
    private static final String[] TURN_EVENTS = {
            GameEventType.TURN_BEFORE,
            GameEventType.TURN_ACTIVE,
            GameEventType.TURN_END,
            GameEventType.TURN_AFTER
    };

    /** 需要监听的所有阶段 ACTIVE 事件 */
    private static final String[] PHASE_ACTIVE_EVENTS = {
            GameEventType.phaseActive(GamePhase.PREPARE),
            GameEventType.phaseActive(GamePhase.JUDGE),
            GameEventType.phaseActive(GamePhase.DRAW),
            GameEventType.phaseActive(GamePhase.PLAY),
            GameEventType.phaseActive(GamePhase.DISCARD),
            GameEventType.phaseActive(GamePhase.END)
    };

    public GameEventBroadcaster(EventBus eventBus,
                                 RoomService roomService,
                                 WebSocketSessionManager sessionManager) {
        this.eventBus = eventBus;
        this.roomService = roomService;
        this.sessionManager = sessionManager;
    }

    @PostConstruct
    public void init() {
        // 注册 GAME.START 监听器
        eventBus.register(GameEventType.GAME_START, 10, this::onGameStart);

        // 注册 GAME.OVER 监听器
        eventBus.register(GameEventType.GAME_OVER, 10, this::onGameOver);

        // 注册所有回合钩子
        for (String type : TURN_EVENTS) {
            eventBus.register(type, 10, this::onTurnEvent);
        }

        // 注册所有阶段 ACTIVE 钩子
        for (String type : PHASE_ACTIVE_EVENTS) {
            eventBus.register(type, 10, this::onPhaseActive);
        }

        log.info("[广播器] 已注册事件监听器（回合/阶段/游戏状态）");
    }

    // ================================================================
    //  事件处理
    // ================================================================

    /**
     * 游戏开始
     */
    private void onGameStart(GameEvent event, GameMatch match) {
        String roomId = event.getData("roomId");
        if (roomId == null) return;

        broadcast(roomId, Map.of(
                "type", "BATTLE_REPORT",
                "message", "游戏开始！"
        ));
    }

    /**
     * 游戏结束
     */
    private void onGameOver(GameEvent event, GameMatch match) {
        String roomId = event.getData("roomId");
        if (roomId == null) return;

        String winnerName = event.getData("winnerPlayerName");
        if (winnerName == null) winnerName = "未知";

        broadcast(roomId, Map.of(
                "type", "BATTLE_REPORT",
                "message", "【" + winnerName + "】获胜！"
        ));
    }

    /**
     * 回合钩子（TURN_BEFORE / TURN_ACTIVE / TURN_END / TURN_AFTER）
     */
    private void onTurnEvent(GameEvent event, GameMatch match) {
        String roomId = event.getData("roomId");
        if (roomId == null) return;

        String playerName = event.getData("playerName");
        if (playerName == null) playerName = "未知";

        String msg;
        switch (event.getType()) {
            case GameEventType.TURN_BEFORE:
                msg = "【" + playerName + "】回合开始前";
                break;
            case GameEventType.TURN_ACTIVE:
                msg = "【" + playerName + "】回合进行中";
                break;
            case GameEventType.TURN_END:
                msg = "【" + playerName + "】回合结束时";
                break;
            case GameEventType.TURN_AFTER:
                msg = "【" + playerName + "】回合结束后";
                break;
            default:
                return;
        }

        broadcast(roomId, Map.of(
                "type", "BATTLE_REPORT",
                "message", msg
        ));
    }

    /**
     * 阶段 ACTIVE 钩子（进入某个阶段）
     */
    private void onPhaseActive(GameEvent event, GameMatch match) {
        String roomId = event.getData("roomId");
        if (roomId == null) return;

        String playerName = event.getData("playerName");
        if (playerName == null) playerName = "未知";

        // 从事件类型中提取阶段名，如 "PHASE.ACTIVE.PREPARE" → "PREPARE"
        String type = event.getType();
        String phaseName = type.substring(type.lastIndexOf('.') + 1);
        String phaseCn = PHASE_CN.getOrDefault(phaseName, phaseName);

        broadcast(roomId, Map.of(
                "type", "BATTLE_REPORT",
                "message", "【" + playerName + "】→ " + phaseCn
        ));
    }

    // ================================================================
    //  广播
    // ================================================================

    /**
     * 向指定房间内的所有玩家广播消息
     */
    private void broadcast(String roomId, Map<String, Object> message) {
        GameRoom room = roomService.getRoom(roomId);
        if (room == null) {
            log.warn("[广播器] 房间不存在: {}", roomId);
            return;
        }
        List<String> playerIds = room.getPlayerIdList();
        String json = toJson(message);
        sessionManager.broadcastToRoom(playerIds, json, null);
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (IOException e) {
            log.error("[广播器] JSON 序列化失败", e);
            return "{}";
        }
    }
}