package com.lbthreecountry.game.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lbthreecountry.game.GameMatch;
import com.lbthreecountry.game.GamePlayer;
import com.lbthreecountry.game.event.EventBus;
import com.lbthreecountry.game.event.EventPriority;
import com.lbthreecountry.game.event.GameEvent;
import com.lbthreecountry.game.event.GameEventType;
import com.lbthreecountry.websocket.WebSocketSessionManager;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 轮次控制器 — 纯记录当前对局是第几轮
 *
 * <p>职责单一：</p>
 * <ul>
 *   <li>监听 {@link GameEventType#ROUND_ROLL} 事件（{@link EventPriority#FRAMEWORK 最高优先级}）</li>
 *   <li>轮次 +1，更新 {@link GameMatch#setCurrentRound(int)}</li>
 *   <li>广播 {@code ROUND_STATE} 消息给前端</li>
 * </ul>
 *
 * <p>不参与任何状态机逻辑，只是轮次的"计数器 + 推送器"。</p>
 *
 * <h3>前端消息格式</h3>
 * <pre>{@code
 * {
 *   "type": "ROUND_STATE",
 *   "round": 1
 * }
 * }</pre>
 *
 * <h3>执行顺序</h3>
 * <pre>
 * enterRoundStart()
 *   └── publish ROUND.ROLL
 *         ↑
 *   FRAMEWORK (-100)  RoundController.onRoundRoll()  ← 最先执行
 *     ├── round++
 *     ├── match.setCurrentRound(round)
 *     └── 广播 "ROUND_STATE" 给前端
 *
 *   ENGINE (200)      RoundStateMachine.onRoundStart()
 *     └── 状态转移
 * </pre>
 */
@Component
public class RoundController {

    private static final Logger log = LoggerFactory.getLogger(RoundController.class);

    private final EventBus eventBus;
    private final WebSocketSessionManager sessionManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** roomId → 当前轮次 */
    private final Map<String, Integer> roundMap = new ConcurrentHashMap<>();

    public RoundController(EventBus eventBus, WebSocketSessionManager sessionManager) {
        this.eventBus = eventBus;
        this.sessionManager = sessionManager;
    }

    @PostConstruct
    public void init() {
        eventBus.register(GameEventType.ROUND_ROLL, EventPriority.FRAMEWORK, this::onRoundRoll);
        
    }

    // ================================================================
    //  事件回调
    // ================================================================

    /**
     * ROUND.ROLL 事件 → 轮次 +1，更新 match，广播给前端
     */
    void onRoundRoll(GameEvent event, GameMatch match) {
        String roomId = match.getRoomId();
        if (roomId == null) return;

        int current = roundMap.getOrDefault(roomId, 0);
        int next = current + 1;
        roundMap.put(roomId, next);
        match.setCurrentRound(next);

        log.info("[轮次控制器]  第 {} 轮", next);

        // ── 广播给前端 ──
        broadcastRoundState(match);
    }

    // ================================================================
    //  查询
    // ================================================================

    /**
     * 获取指定房间的当前轮次
     */
    public int getCurrentRound(String roomId) {
        return roundMap.getOrDefault(roomId, 0);
    }

    // ================================================================
    //  内部
    // ================================================================

    private void broadcastRoundState(GameMatch match) {
        List<String> playerIds = match.getPlayers().stream()
                .map(GamePlayer::getPlayerId)
                .collect(Collectors.toList());
        if (playerIds.isEmpty()) return;

        try {
            String json = objectMapper.writeValueAsString(Map.of(
                    "type", "ROUND_STATE",
                    "round", match.getCurrentRound()
            ));
            sessionManager.broadcastToRoom(playerIds, json, null);
        } catch (Exception e) {
            log.warn("[轮次控制器] 广播轮次状态失败", e);
        }
    }
}