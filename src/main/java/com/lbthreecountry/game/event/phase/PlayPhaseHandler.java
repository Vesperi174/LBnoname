package com.lbthreecountry.game.event.phase;

import com.lbthreecountry.entity.GameRoom;
import com.lbthreecountry.game.GameMatch;
import com.lbthreecountry.game.GamePlayer;
import com.lbthreecountry.game.event.EventBus;
import com.lbthreecountry.game.event.EventPriority;
import com.lbthreecountry.game.event.GameEvent;
import com.lbthreecountry.game.event.GameEventType;
import com.lbthreecountry.game.interaction.InteractionMessageStack;
import com.lbthreecountry.game.state.PlayerTurnStateMachine;
import com.lbthreecountry.model.enums.impl.GamePhase;
import com.lbthreecountry.service.RoomService;
import com.lbthreecountry.websocket.WebSocketSessionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 出牌阶段处理器 — 监听"出牌阶段进行中"事件
 *
 * <p>当 {@link PlayerTurnStateMachine} 进入 PLAY 阶段（State 5）时，
 * 会发布 {@code PHASE.ACTIVE.PLAY} 事件。本组件监听此事件后，
 * 构造 {@code ACTION_DECISION} 消息并通过 {@link InteractionMessageStack#pushAndAwait}
 * 阻塞等待玩家响应。</p>
 *
 * <h3>线程安全</h3>
 * <p>此监听器在 {@code botScheduler} 线程上执行（{@code BATTLE_START}
 * 已被调度到该线程），因此可以安全地阻塞等待玩家响应。</p>
 *
 * <h3>执行顺序</h3>
 * <pre>
 * botScheduler 线程:
 *   publish("PHASE.ACTIVE.PLAY")
 *     └── PlayPhaseHandler.onPlayPhase()
 *           ├── 构造 ACTION_DECISION 消息
 *           └── pushAndAwait()  ⛔ 阻塞等玩家
 *                 │
 *                 ├─ 玩家响应 → resolve() → ⛔ 唤醒
 *                 └─ 继续后续阶段
 * </pre>
 */
@Component
public class PlayPhaseHandler {

    private static final Logger log = LoggerFactory.getLogger(PlayPhaseHandler.class);

    private final EventBus eventBus;
    private final InteractionMessageStack interactionStack;
    private final WebSocketSessionManager sessionManager;
    private final RoomService roomService;

    public PlayPhaseHandler(EventBus eventBus,
                            InteractionMessageStack interactionStack,
                            WebSocketSessionManager sessionManager,
                            RoomService roomService) {
        this.eventBus = eventBus;
        this.interactionStack = interactionStack;
        this.sessionManager = sessionManager;
        this.roomService = roomService;
    }

    @PostConstruct
    public void init() {
        eventBus.register(
                GameEventType.phaseActive(GamePhase.PLAY),
                EventPriority.ENGINE,
                this::onPlayPhase
        );
        log.info("[出牌阶段处理器] 已注册 PHASE.ACTIVE.PLAY 监听器 (ENGINE 优先级)");
    }

    // ================================================================
    //  事件回调
    // ================================================================

    /**
     * PHASE.ACTIVE.PLAY 事件处理
     *
     * <p>出牌阶段：构造 ACTION_DECISION 消息 → 压入消息栈 → 阻塞等待玩家响应。
     * 当前线程为 {@code botScheduler}，可以安全阻塞。</p>
     */
    private void onPlayPhase(GameEvent event, GameMatch match) {
        String playerId = event.getSourceId();
        if (playerId == null) {
            log.warn("[出牌阶段处理器] 事件中无 sourceId，忽略");
            return;
        }

        int round = match.getCurrentRound();
        log.info("[出牌阶段处理器] 第 {} 轮·玩家 {} 出牌阶段开始 (线程: {})",
                round, playerId, Thread.currentThread().getName());

        // ── 1. 读取房间设定的出手时间，构造 ACTION_DECISION 消息 ──
        int turnTime = getTurnTime(match.getRoomId());
        int backendTimeout = turnTime + 5; // 后端比前端多 5s 兜底

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "ACTION_DECISION");
        message.put("timeout", turnTime);
        message.put("description", "出牌阶段，请选择一张卡牌");
        message.put("actions", List.of(
                Map.of("text", "确定", "value", "confirm", "type", "default"),
                Map.of("text", "回合结束", "value", "end_turn", "type", "primary")
        ));
        message.put("handSelectable", true);
        message.put("handSelectMode", "single");
        message.put("targetSelectable", false);

        // ── 2. 判断是否为 Bot 玩家 ──
        GamePlayer player = match.findPlayer(playerId);
        if (player != null && player.isBot()) {
            // Bot 玩家：不阻塞等待，自动回复"回合结束"
            log.info("[出牌阶段处理器] 🤖 玩家 {} 是 Bot，自动跳过出牌阶段", playerId);
            // 只入栈但不阻塞，让 Bot 自动推进的逻辑处理
            interactionStack.push(match.getRoomId(), playerId, message, sessionManager);
            return;
        }

        // ── 3. 真人玩家出牌循环 ──
        // 每次出牌后重新发送 ACTION_DECISION 让玩家继续出牌，
        // 直到玩家点击"回合结束"或超时跳出循环。
        // 同时向其他玩家广播 PLAYER_THINKING，让他们看到当前玩家的倒计时。
        int playCount = 0;
        List<String> allPlayerIds = match.getPlayers().stream()
                .map(GamePlayer::getPlayerId)
                .collect(Collectors.toList());
        ObjectMapper objectMapper = new ObjectMapper();

        while (true) {
            // ── 3a. 广播给其他玩家：当前玩家正在决策中 ──
            Map<String, Object> thinkingMsg = new LinkedHashMap<>();
            thinkingMsg.put("type", "PLAYER_THINKING");
            thinkingMsg.put("playerId", playerId);
            thinkingMsg.put("timeout", turnTime);
            thinkingMsg.put("description", "出牌阶段思考中");
            try {
                sessionManager.broadcastToRoom(
                        allPlayerIds,
                        objectMapper.writeValueAsString(thinkingMsg),
                        playerId // 排除当前玩家自己
                );
            } catch (Exception e) {
                log.warn("[出牌阶段处理器] 广播 PLAYER_THINKING 失败", e);
            }
            log.info("[出牌阶段处理器] ⏳ 等待玩家 {} 出牌决策... (第 {} 次, 前端超时={}s, 后端兜底={}s)",
                    playerId, ++playCount, turnTime, backendTimeout);
            Map<String, Object> response = interactionStack.pushAndAwait(
                    match.getRoomId(), playerId, message, sessionManager, backendTimeout
            );

            String action = response != null ? (String) response.get("action") : "timeout";
            log.info("[出牌阶段处理器] 玩家 {} 出牌决策完成: action={}", playerId, action);

            // ── 4. 根据响应跳出循环或执行出牌 ──
            if ("end_turn".equals(action) || "timeout".equals(action) || "TIMEOUT".equals(action)) {
                log.info("[出牌阶段处理器] 玩家 {} {}，出牌阶段结束（共出牌 {} 次）",
                        playerId, "TIMEOUT".equals(action) ? "超时" : "回合结束", playCount - 1);
                break;
            }

            // "confirm" / "play_slash" → 执行出牌（TODO: 后续完善）
            log.info("[出牌阶段处理器] 玩家 {} 执行出牌 action={}, selectedIds={} (出牌逻辑待实现)",
                    playerId, action, response.get("selectedIds"));

            // TODO: 出牌后刷新手牌可用性并重新检测
        }

        // 循环结束 → 状态机自动推进到 DISCARD
        log.info("[出牌阶段处理器] 出牌循环结束，状态机进入弃牌阶段");
    }

    // ================================================================
    //  辅助方法
    // ================================================================

    /**
     * 读取房间设定的出手时间（秒），兜底默认 15s
     */
    private int getTurnTime(String roomId) {
        try {
            GameRoom room = roomService.getRoom(roomId);
            if (room != null && room.getRoomSettings() != null) {
                Object val = room.getRoomSettings().get("turnTime");
                if (val instanceof Number) {
                    return ((Number) val).intValue();
                }
            }
        } catch (Exception e) {
            log.warn("[出牌阶段处理器] 读取 turnTime 失败，使用默认 15s", e);
        }
        return 15;
    }
}