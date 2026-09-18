package com.lbthreecountry.game.event.phase;

import com.lbthreecountry.entity.GameRoom;
import com.lbthreecountry.game.GameMatch;
import com.lbthreecountry.game.GamePlayer;
import com.lbthreecountry.game.card.CardLibrary;
import com.lbthreecountry.game.event.EventBus;
import com.lbthreecountry.game.event.EventPriority;
import com.lbthreecountry.game.event.GameEvent;
import com.lbthreecountry.game.event.GameEventType;
import com.lbthreecountry.game.event.common.Source;
import com.lbthreecountry.game.interaction.InteractionMessageStack;
import com.lbthreecountry.game.state.PlayerTurnStateMachine;
import com.lbthreecountry.model.card.CardInstance;
import com.lbthreecountry.model.card.def.CardDef;
import com.lbthreecountry.model.card.def.CardRules;
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
 * <p>
 * 当 {@link PlayerTurnStateMachine} 进入 PLAY 阶段（State 5）时，
 * 会发布 {@code PHASE.ACTIVE.PLAY} 事件。本组件监听此事件后，
 * 构造 {@code ACTION_DECISION} 消息并通过 {@link InteractionMessageStack#pushAndAwait}
 * 阻塞等待玩家响应。
 * </p>
 *
 * <h3>线程安全</h3>
 * <p>
 * 此监听器在 {@code botScheduler} 线程上执行（{@code BATTLE_START}
 * 已被调度到该线程），因此可以安全地阻塞等待玩家响应。
 * </p>
 *
 * <h3>执行顺序</h3>
 * 
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
    private final CardLibrary cardLibrary;

    /** 防止循环内重入发布 PHASE.ACTIVE.PLAY 导致递归 */
    private final ThreadLocal<Boolean> inPlayLoop = ThreadLocal.withInitial(() -> false);

    public PlayPhaseHandler(EventBus eventBus,
            InteractionMessageStack interactionStack,
            WebSocketSessionManager sessionManager,
            RoomService roomService,
            CardLibrary cardLibrary) {
        this.eventBus = eventBus;
        this.interactionStack = interactionStack;
        this.sessionManager = sessionManager;
        this.roomService = roomService;
        this.cardLibrary = cardLibrary;
    }

    @PostConstruct
    public void init() {
        eventBus.register(
                GameEventType.phaseActive(GamePhase.PLAY),
                EventPriority.ENGINE,
                this::onPlayPhase);
    }

    // ================================================================
    // 事件回调
    // ================================================================

    /**
     * PHASE.ACTIVE.PLAY 事件处理
     *
     * <p>
     * 出牌阶段：构造 ACTION_DECISION 消息 → 压入消息栈 → 阻塞等待玩家响应。
     * 当前线程为 {@code botScheduler}，可以安全阻塞。
     * </p>
     */
    private void onPlayPhase(GameEvent event, GameMatch match) {
        String playerId = event.getSourceId();
        if (playerId == null) {
            log.warn("[出牌阶段处理器] 事件中无 sourceId，忽略");
            return;
        }

        // ── 重入保护：循环内重新发布 PHASE.ACTIVE.PLAY 时跳过 ──
        if (inPlayLoop.get()) {
            log.debug("[出牌阶段处理器] 循环内重入钩子，跳过（仅用于触发卡牌检测等）");
            return;
        }

        int round = match.getCurrentRound();
        log.info("[出牌阶段处理器] 第 {} 轮·玩家 {} 出牌阶段开始 (线程: {})",
                round, playerId, Thread.currentThread().getName());

        // ── 读取房间设定的出手时间 ──
        int turnTime = getTurnTime(match.getRoomId());

        // ── 判断是否为 Bot 玩家 ──
        GamePlayer player = match.findPlayer(playerId);
        if (player != null && player.isBot()) {
            log.info("[出牌阶段处理器] 🤖 玩家 {} 是 Bot，自动跳过出牌阶段", playerId);
            Map<String, Object> dummyMsg = buildDecisionMessage(turnTime);
            interactionStack.push(match.getRoomId(), playerId, dummyMsg, sessionManager);
            return;
        }

        // ── 真人玩家出牌循环 ──
        int backendTimeout = turnTime + 5;
        int playCount = 0;
        List<String> allPlayerIds = match.getPlayers().stream()
                .map(GamePlayer::getPlayerId)
                .collect(Collectors.toList());
        ObjectMapper objectMapper = new ObjectMapper();

        inPlayLoop.set(true);
        try {
            while (true) {
                // ── 重新发布 PHASE.ACTIVE.PLAY 钩子 → 触发卡牌可用性检测等 ──
                GameEvent hookEvent = new GameEvent();
                hookEvent.setType(GameEventType.phaseActive(GamePhase.PLAY));
                hookEvent.setSourceId(playerId);
                eventBus.publish(hookEvent, match);

                // ── 构造 ACTION_DECISION 消息（每次重新构造，反映最新的手牌状态） ──
                Map<String, Object> message = buildDecisionMessage(turnTime);

                // ── 广播给其他玩家：当前玩家正在决策中 ──
                Map<String, Object> thinkingMsg = new LinkedHashMap<>();
                thinkingMsg.put("type", "PLAYER_THINKING");
                thinkingMsg.put("playerId", playerId);
                thinkingMsg.put("timeout", turnTime);
                thinkingMsg.put("description", "出牌阶段思考中");
                try {
                    sessionManager.broadcastToRoom(
                            allPlayerIds,
                            objectMapper.writeValueAsString(thinkingMsg),
                            playerId);
                } catch (Exception e) {
                    log.warn("[出牌阶段处理器] 广播 PLAYER_THINKING 失败", e);
                }
                log.info("[出牌阶段处理器] ⏳ 等待玩家 {} 出牌决策... (第 {} 次, 前端超时={}s, 后端兜底={}s)",
                        playerId, ++playCount, turnTime, backendTimeout);
                Map<String, Object> response = interactionStack.pushAndAwait(
                        match.getRoomId(), playerId, message, sessionManager, backendTimeout);

                String action = response != null ? (String) response.get("action") : null;
                String value = response != null ? (String) response.get("value") : null;
                log.info("[出牌阶段处理器] 玩家 {} 出牌决策完成: action={}, value={}, selectedCardIds={}, selectedPlayerIds={}",
                        playerId, action, value,
                        response != null ? response.get("selectedCardIds") : null,
                        response != null ? response.get("selectedPlayerIds") : null);

                // ── 处理卡牌出牌（前端返回卡牌 action value，如 "play_slash"）──
                if (value != null && value.startsWith("play_")) {
                    boolean continueLoop = handlePlayCard(playerId, response, value,
                            match, turnTime, backendTimeout);
                    if (!continueLoop) {
                        break;
                    }
                    continue;
                }

                // ── 处理回合结束 / 超时 / 中断 / 房间销毁 ──
                String respType = response != null ? (String) response.get("type") : null;
                if ("end_turn".equals(action) || "timeout".equals(action)
                        || "TIMEOUT".equals(action) || "interrupted".equals(action)
                        || "CANCELLED".equals(respType)) {
                    log.info("[出牌阶段处理器] 玩家 {} {}，出牌阶段结束（共出牌 {} 次）",
                            playerId,
                            "CANCELLED".equals(respType) ? "房间销毁"
                                    : "interrupted".equals(action) ? "中断" : "TIMEOUT".equals(action) ? "超时" : "回合结束",
                            playCount - 1);
                    break;
                }

                // ── 其他 action，忽略并继续 ──
                log.debug("[出牌阶段处理器] 忽略未识别的 action={}", action);
            }
        } finally {
            inPlayLoop.set(false);
        }

        // 循环结束 → 状态机自动推进到 DISCARD
        log.info("[出牌阶段处理器] 出牌循环结束，状态机进入弃牌阶段");
    }

    // ================================================================
    // 出牌处理
    // ================================================================

    /**
     * 处理玩家出牌 — 获取目标（如需）→ 发布 {@code CARD.USE} 事件
     *
     * @return true=继续出牌循环，false=出牌阶段结束
     */
    @SuppressWarnings("unchecked")
    private boolean handlePlayCard(String playerId, Map<String, Object> response,
            String cardAction, GameMatch match,
            int turnTime, int backendTimeout) {
        // ── 解析 selectedCardIds[0] 为卡牌实例 ID ──
        List<String> selectedCardIds = (List<String>) response.get("selectedCardIds");
        if (selectedCardIds == null || selectedCardIds.isEmpty()) {
            // 如果 type=CANCELLED，说明房间已销毁，直接结束
            String respType = (String) response.get("type");
            if ("CANCELLED".equals(respType)) {
                return false;
            }
            log.warn("[出牌阶段处理器] selectedCardIds 为空，忽略");
            return true;
        }

        long cardInstanceId;
        try {
            cardInstanceId = Long.parseLong(selectedCardIds.get(0));
        } catch (NumberFormatException e) {
            log.warn("[出牌阶段处理器] 卡牌实例 ID 解析失败: {}", selectedCardIds.get(0));
            return true;
        }

        GamePlayer useplayer = match.findPlayer(playerId);
        if (useplayer == null) {
            log.warn("[出牌阶段处理器] 玩家 {} 不存在", playerId);
            return true;
        }

        // ── 从玩家手牌中查找卡牌实例 ──
        CardInstance card = useplayer.getHandCards().stream()
                .filter(c -> c.getInstanceId().equals(cardInstanceId))
                .findFirst().orElse(null);
        if (card == null) {
            log.warn("[出牌阶段处理器] 卡牌实例 {} 不在玩家手牌中", cardInstanceId);
            return true;
        }

        // ── 获取卡牌定义 ──
        CardDef cardDef = cardLibrary.getDef(card.getDefId());
        if (cardDef == null) {
            log.warn("[出牌阶段处理器] 卡牌定义 {} 不存在", card.getDefId());
            return true;
        }

        CardRules rules = cardDef.getRules();
        boolean needsTarget = rules != null && rules.getTargetCount() > 0;

        GamePlayer targetplayer = null;

        if (needsTarget) {
            // ── 获取合法目标 ──
            String filterType = mapFilterType(rules);
            GameEvent targetEvent = GameEvent.builder()
                    .type(GameEventType.GET_TARGET)
                    .sourceId(playerId)
                    .build()
                    .putData("playerId", playerId)
                    .putData("filterType", filterType)
                    .putData("source", new Source(card))
                    .putData("card", card)
                    .putData("sourceType", "BASIC_CARD")
                    .putData("includeSelf", "SELF".equals(filterType));
            if (rules.getRangeLimit() >= 0) {
                targetEvent.putData("distance", rules.getRangeLimit());
            }
            eventBus.publish(targetEvent, match);

            List<String> targets = targetEvent.getData("targets");
            if (targets == null || targets.isEmpty()) {
                log.warn("[出牌阶段处理器] 【{}】无合法目标", cardDef.getName());
                return true;
            }

            // ── 发送目标选择 ACTION_DECISION ──
            Map<String, Object> targetMsg = new LinkedHashMap<>();
            targetMsg.put("type", "ACTION_DECISION");
            targetMsg.put("timeout", turnTime);
            targetMsg.put("description", "请选择【" + cardDef.getName() + "】的目标");
            targetMsg.put("actions", List.of(
                    Map.of("text", "确定", "value", cardAction, "type", "primary"),
                    Map.of("text", "取消", "value", "cancel", "type", "primary")));
            targetMsg.put("handSelectable", false);
            targetMsg.put("targetSelectable", true);
            targetMsg.put("selectableTargetIds", targets);
            targetMsg.put("targetSelectMode", "single");

            log.info("[出牌阶段处理器] 等待玩家 {} 选择【{}】的目标... (可选目标: {})",
                    playerId, cardDef.getName(), targets);
            Map<String, Object> targetResponse = interactionStack.pushAndAwait(
                    match.getRoomId(), playerId, targetMsg, sessionManager, backendTimeout);

            String targetAction = targetResponse != null
                    ? (String) targetResponse.get("action")
                    : "cancel";
            String targetRespType = targetResponse != null
                    ? (String) targetResponse.get("type")
                    : null;

            // 房间已销毁 → 结束出牌阶段
            if ("CANCELLED".equals(targetRespType)) {
                log.info("[出牌阶段处理器] 房间已销毁，结束出牌阶段");
                return false;
            }

            if ("cancel".equals(targetAction) || "interrupted".equals(targetAction)) {
                log.info("[出牌阶段处理器] 玩家 {} 取消使用【{}】", playerId, cardDef.getName());
                return true;
            }

            // ── 从 selectedPlayerIds[0] 获取目标玩家 ID ──
            List<String> targetPlayerIds = (List<String>) targetResponse.get("selectedPlayerIds");
            String targetPlayerId = targetPlayerIds != null && !targetPlayerIds.isEmpty()
                    ? targetPlayerIds.get(0)
                    : null;
            if (targetPlayerId == null) {
                log.warn("[出牌阶段处理器] 未选择目标，取消使用【{}】", cardDef.getName());
                return true;
            }
            targetplayer = match.findPlayer(targetPlayerId);
            if (targetplayer == null) {
                log.warn("[出牌阶段处理器] 目标玩家 {} 不存在", targetPlayerId);
                return true;
            }
        }

        // ── 无目标时默认以自身为目标 ──
        if (targetplayer == null) {
            targetplayer = useplayer;
        }

        // ── 发布 CARD.USE 事件 ──
        log.info("[出牌阶段处理器] 玩家 {} 使用【{}】→ 目标 {}",
                playerId, cardDef.getName(), targetplayer.getPlayerId());

        GameEvent useEvent = GameEvent.builder()
                .type(GameEventType.CARD_USE)
                .sourceId(playerId)
                .targetId(targetplayer.getPlayerId())
                .build()
                .putData("useplayer", useplayer)
                .putData("targetplayer", targetplayer)
                .putData("card", card);
        eventBus.publish(useEvent, match);

        return true;
    }

    /**
     * 将卡牌规则的 filterType 映射为 GetTargetEvent 的筛选类型
     * <p>
     * 优先使用 {@code rules.filterType}，若为 null 则降级使用 {@code rules.targetType}。
     * </p>
     */
    private String mapFilterType(CardRules rules) {
        String ft = rules != null ? rules.getFilterType() : null;
        if (ft == null) {
            ft = rules != null ? rules.getTargetType() : null;
        }
        if (ft == null) {
            return "ALL";
        }
        return switch (ft) {
            case "SELF" -> "SELF";
            case "ENEMY" -> rules.getRangeLimit() < 0 ? "IN_ATTACK_RANGE" : "DISTANCE_WITHIN";
            case "ALLY" -> "ALL";
            default -> "ALL";
        };
    }

    /**
     * 构造 ACTION_DECISION 消息
     */
    private Map<String, Object> buildDecisionMessage(int turnTime) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "ACTION_DECISION");
        message.put("timeout", turnTime);
        message.put("description", "出牌阶段，请选择一张卡牌");
        message.put("actions", List.of(
                Map.of("text", "确定", "value", "confirm", "type", "default"),
                Map.of("text", "回合结束", "value", "end_turn", "type", "primary")));
        message.put("handSelectable", true);
        message.put("handSelectMode", "single");
        message.put("targetSelectable", false);
        return message;
    }

    // ================================================================
    // 辅助方法
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