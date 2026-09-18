package com.lbthreecountry.game.interaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lbthreecountry.entity.GameRoom;
import com.lbthreecountry.game.GameMatch;
import com.lbthreecountry.game.GamePlayer;
import com.lbthreecountry.game.event.EventBus;
import com.lbthreecountry.game.event.EventPriority;
import com.lbthreecountry.game.event.GameEvent;
import com.lbthreecountry.game.event.GameEventType;
import com.lbthreecountry.service.RoomService;
import com.lbthreecountry.websocket.WebSocketSessionManager;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 交互管理器 — 监听交互事件钩子，驱动前端交互
 *
 * <p>监听 {@link GameEventType#INTERACTION_REQUEST} 事件，
 * 将事件数据转换为前端 {@code ACTION_DECISION} 消息格式并发送。</p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // ===== 示例：请求玩家选择【决斗】的目标和手牌 =====
 * eventBus.publish(
 *     GameEvent.builder()
 *         .type(GameEventType.INTERACTION_REQUEST)
 *         .sourceId(playerId)
 *         .build()
 *         // ── 基础 ──
 *         .putData("playerId", playerId)
 *         .putData("timeout", 15)
 *         .putData("description", "请选择【决斗】的目标")
 *         // ── 按钮 ──
 *         .putData("actions", List.of(
 *             Map.of("text", "出杀", "value", "play_slash", "type", "primary"),
 *             Map.of("text", "取消", "value", "cancel", "type", "default")
 *         ))
 *         // ── 手牌选择（可选） ──
 *         .putData("handSelectable", true)
 *         .putData("handSelectMode", "single")
 *         .putData("maxHandSelect", 1)
 *         // ── 目标选择（可选） ──
 *         .putData("targetSelectable", true)
 *         .putData("targetSelectMode", "single")
 *         .putData("selectableTargets", List.of("playerId1", "playerId2")),
 *     match
 * );
 * }</pre>
 */
@Component
public class InteractionManager {

    private static final Logger log = LoggerFactory.getLogger(InteractionManager.class);

    private final EventBus eventBus;
    private final WebSocketSessionManager sessionManager;
    private final RoomService roomService;
    private final InteractionMessageStack interactionStack;
    private final ObjectMapper objectMapper;

    public InteractionManager(EventBus eventBus, WebSocketSessionManager sessionManager,
                              RoomService roomService, InteractionMessageStack interactionStack) {
        this.eventBus = eventBus;
        this.sessionManager = sessionManager;
        this.roomService = roomService;
        this.interactionStack = interactionStack;
        this.objectMapper = new ObjectMapper();
    }

    @PostConstruct
    public void init() {
        eventBus.register(GameEventType.INTERACTION_REQUEST, EventPriority.ENGINE, this::onInteractionRequest);
        
    }

    /**
     * INTERACTION.REQUEST 事件回调 — 构造 ACTION_DECISION 消息并发送到前端
     *
     * <p>从事件数据中读取交互参数，组装成前端约定的消息格式：</p>
     * <pre>{@code
     * {
     *   type: "ACTION_DECISION",
     *   timeout: 15,
     *   description: "...",
     *   actions: [...],
     *   handSelectable: true,
     *   handSelectMode: "multiple",
     *   maxHandSelect: 1,
     *   targetSelectable: true,
     *   targetSelectMode: "single",
     *   selectableTargets: [...],
     *   includeSelf: false
     * }
     * }</pre>
     */
    @SuppressWarnings("unchecked")
    private void onInteractionRequest(GameEvent event, GameMatch match) {
        // ── 1. 读取目标玩家 ──
        String playerId = event.getData("playerId");
        if (playerId == null) {
            GamePlayer player = event.getData("player");
            if (player != null) playerId = player.getPlayerId();
        }
        if (playerId == null) {
            log.warn("[交互管理器] 事件中缺少 playerId，忽略");
            return;
        }

        // ── 2. 读取交互参数 ──
        // timeout 默认值：优先取事件中指定的，否则从房间设置 turnTime 读取，兜底 15 秒
        Integer timeout = event.getData("timeout");
        if (timeout == null || timeout < 1) {
            timeout = getDefaultTimeout(match);
        }

        String description = event.getData("description");

        List<Map<String, Object>> actions = event.getData("actions");

        Boolean handSelectable = event.getDataOrDefault("handSelectable", false);
        String handSelectMode = event.getData("handSelectMode");
        Integer maxHandSelect = event.getData("maxHandSelect");

        Boolean targetSelectable = event.getDataOrDefault("targetSelectable", false);
        String targetSelectMode = event.getData("targetSelectMode");
        List<String> selectableTargets = event.getData("selectableTargets");
        Boolean includeSelf = event.getDataOrDefault("includeSelf", false);

        // ── 3. 构造前端消息（保持字段添加顺序与文档一致） ──
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "ACTION_DECISION");
        message.put("timeout", timeout);

        if (description != null) {
            message.put("description", description);
        }

        message.put("actions", actions);

        if (handSelectable) {
            message.put("handSelectable", true);
            if (handSelectMode != null) {
                message.put("handSelectMode", handSelectMode);
            }
            if (maxHandSelect != null && maxHandSelect > 0) {
                message.put("maxHandSelect", maxHandSelect);
            }
        }

        if (targetSelectable) {
            message.put("targetSelectable", true);
            if (targetSelectMode != null) {
                message.put("targetSelectMode", targetSelectMode);
            }
            if (selectableTargets != null) {
                message.put("selectableTargets", selectableTargets);
            }
            if (includeSelf) {
                message.put("includeSelf", true);
            }
        }

        // ── 4. 通过消息栈发送到前端 ──
        // 消息栈会确保只有栈顶消息是活跃的，新消息自动覆盖旧消息
        String roomId = match != null ? match.getRoomId() : null;
        if (roomId != null) {
            interactionStack.push(roomId, playerId, message, sessionManager);
            log.debug("[交互管理器] ACTION_DECISION → {} (timeout={}s) [已压入消息栈, 栈深={}]",
                    playerId, timeout, interactionStack.getDepth(roomId, playerId));
        } else {
            // 兜底：没有 roomId 时直接发送
            try {
                String json = objectMapper.writeValueAsString(message);
                sessionManager.sendMessage(playerId, json);
                log.debug("[交互管理器] ACTION_DECISION → {} (timeout={}s) [无 roomId，直接发送]",
                        playerId, timeout);
            } catch (Exception e) {
                log.error("[交互管理器] 发送 ACTION_DECISION 给 {} 失败", playerId, e);
            }
        }
    }

    /**
     * 获取默认超时时间（秒）
     *
     * <p>优先从房间设置 {@code turnTime} 中读取，取不到时兜底返回 15。</p>
     */
    private int getDefaultTimeout(GameMatch match) {
        if (match != null && match.getRoomId() != null) {
            try {
                GameRoom room = roomService.getRoom(match.getRoomId());
                if (room != null && room.getRoomSettings() != null) {
                    Object turnTime = room.getRoomSettings().get("turnTime");
                    if (turnTime instanceof Number) {
                        return ((Number) turnTime).intValue();
                    }
                }
            } catch (Exception e) {
                log.warn("[交互管理器] 获取房间设置超时时间失败，使用默认 15 秒", e);
            }
        }
        return 15;
    }
}