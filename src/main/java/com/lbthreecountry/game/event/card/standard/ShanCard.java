package com.lbthreecountry.game.event.card.standard;

import com.lbthreecountry.entity.GameRoom;
import com.lbthreecountry.game.GameMatch;
import com.lbthreecountry.game.GamePlayer;
import com.lbthreecountry.game.event.EventBus;
import com.lbthreecountry.game.event.EventPriority;
import com.lbthreecountry.game.event.GameEvent;
import com.lbthreecountry.game.event.GameEventType;
import com.lbthreecountry.game.hero.HeroManager;
import com.lbthreecountry.game.interaction.InteractionMessageStack;
import com.lbthreecountry.model.card.CardInstance;
import com.lbthreecountry.model.enums.impl.CardActionStatus;
import com.lbthreecountry.service.RoomService;
import com.lbthreecountry.websocket.WebSocketSessionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 【闪】卡牌效果 — 监听 {@code CARD.USE.ACTIVE} 事件钩子
 *
 * <p>当【杀】指定目标时：</p>
 * <ol>
 *   <li>通知前端目标玩家的【闪】牌可用，其余牌不可用（HAND_STATUS）</li>
 *   <li>发送交互消息选择【闪】（ACTION_DECISION）</li>
 *   <li>玩家选牌后再发送确认消息，点确定后执行闪效果</li>
 * </ol>
 *
 * <h3>触发事件数据字段</h3>
 * <pre>
 * ┌──────────────┬──────────────┬──────────────────────────────────────┐
 * │ 字段名        │ 类型          │ 说明                                 │
 * ├──────────────┼──────────────┼──────────────────────────────────────┤
 * │ useplayer    │ GamePlayer   │ 使用牌的玩家                          │
 * │ targetplayer │ GamePlayer   │ 目标玩家                              │
 * │ card         │ CardInstance │ 使用的卡牌实例                        │
 * └──────────────┴──────────────┴──────────────────────────────────────┘
 * </pre>
 */
@Component
public class ShanCard {

    private static final Logger log = LoggerFactory.getLogger(ShanCard.class);

    private final EventBus eventBus;
    private final WebSocketSessionManager sessionManager;
    private final InteractionMessageStack interactionStack;
    private final RoomService roomService;
    private final HeroManager heroManager;
    private final ObjectMapper objectMapper;

    public ShanCard(EventBus eventBus,
                    WebSocketSessionManager sessionManager,
                    InteractionMessageStack interactionStack,
                    RoomService roomService,
                    HeroManager heroManager) {
        this.eventBus = eventBus;
        this.sessionManager = sessionManager;
        this.interactionStack = interactionStack;
        this.roomService = roomService;
        this.heroManager = heroManager;
        this.objectMapper = new ObjectMapper();
    }

    @PostConstruct
    public void init() {
        eventBus.register(GameEventType.CARD_USE_ACTIVE, EventPriority.EQUIP_CARD, this::onUseActive);
    }

    /**
     * {@code CARD.USE.ACTIVE} 事件回调 — 检测【杀】的目标，推送 HAND_STATUS 和交互消息
     *
     * <p>判断逻辑：</p>
     * <ol>
     *   <li>使用的卡牌是否为【杀】</li>
     *   <li>目标玩家是否存在</li>
     * </ol>
     * <p>通过后：</p>
     * <ol>
     *   <li>推送 HAND_STATUS — 只有【闪】可选</li>
     *   <li>发送 ACTION_DECISION — 玩家选择一张【闪】</li>
     *   <li>玩家选牌后发送确认 ACTION_DECISION — 点确定后执行效果</li>
     * </ol>
     */
    private void onUseActive(GameEvent event, GameMatch match) {
        // ── 判断使用的卡牌是否为【杀】 ──
        CardInstance card = event.getData("card");
        if (card == null || !"sha".equals(card.getDefId())) {
            return;
        }

        // ── 判断目标玩家是否存在 ──
        GamePlayer targetplayer = event.getData("targetplayer");
        if (targetplayer == null) {
            return;
        }

        GamePlayer useplayer = event.getData("useplayer");

        // ── 获取使用者的角色名 ──
        String useplayerName = "未知";
        if (useplayer != null) {
            String heroId = useplayer.getHeroId();
            if (heroId != null && heroManager.getHero(heroId) != null) {
                useplayerName = heroManager.getHero(heroId).getHeroName();
            } else {
                useplayerName = useplayer.getPlayerId();
            }
        }

        log.info("[闪] 检测到 【{}】 对 【{}】 使用【杀】，准备闪响应",
                useplayerName, targetplayer.getPlayerId());

        int turnTime = getTurnTime(match.getRoomId());
        int backendTimeout = turnTime + 5;

        // ── 获取房间所有玩家 ID（用于广播 PLAYER_THINKING） ──
        List<String> allPlayerIds = match.getPlayers().stream()
                .map(GamePlayer::getPlayerId)
                .collect(Collectors.toList());

        // ── 1. 推送 HAND_STATUS：只有【闪】可选 ──
        pushHandStatus(targetplayer, match);

        // ── 2. 循环：选牌 → 确认 → 取消则重选，直到确认打出或放弃 ──
        boolean didPlayShan = false;
        while (true) {
            // ── 2a. 选择一张【闪】 ──
            Map<String, Object> selectMsg = buildSelectMessage(turnTime, useplayerName);
            log.info("[闪] 等待玩家 {} 选择【闪】... (timeout={}s)",
                    targetplayer.getPlayerId(), turnTime);

            // ── 广播给其他玩家：目标玩家正在决策是否出闪 ──
            broadcastThinking(allPlayerIds, targetplayer.getPlayerId(), turnTime, "玩家【闪】思考中");

            Map<String, Object> selectResp = interactionStack.pushAndAwait(
                    match.getRoomId(), targetplayer.getPlayerId(),
                    selectMsg, sessionManager, backendTimeout);

            Long selectedInstanceId = handleSelectResponse(selectResp, targetplayer);
            if (selectedInstanceId == null) {
                // 取消/超时 → 只有这里跳出循环，杀继续执行
                log.info("[闪] 玩家 {} 放弃出闪，杀效果继续执行", targetplayer.getPlayerId());
                break;
            }

            // ── 2b. 确认打出这张【闪】 ──
            Map<String, Object> confirmMsg = buildConfirmMessage(turnTime, selectedInstanceId);
            log.info("[闪] 玩家 {} 已选 【闪】(instanceId={})，等待最终确认...",
                    targetplayer.getPlayerId(), selectedInstanceId);

            // ── 广播给其他玩家：目标玩家正在确认是否出闪 ──
            broadcastThinking(allPlayerIds, targetplayer.getPlayerId(), turnTime, "玩家【闪】思考中");

            Map<String, Object> confirmResp = interactionStack.pushAndAwait(
                    match.getRoomId(), targetplayer.getPlayerId(),
                    confirmMsg, sessionManager, backendTimeout);

            ConfirmResult confirmResult = handleConfirmResponse(confirmResp, targetplayer, selectedInstanceId);

            if (confirmResult == ConfirmResult.CONFIRMED) {
                // 确认打出 → 执行闪效果，跳出循环
                log.info("[闪] 玩家 {} 确认打出 【闪】(instanceId={})，开始执行效果",
                        targetplayer.getPlayerId(), selectedInstanceId);
                didPlayShan = true;

                // ── 从手牌中取出选中的【闪】实例 ──
                CardInstance shanCard = targetplayer.getHandCards().stream()
                        .filter(c -> c.getInstanceId() == selectedInstanceId)
                        .findFirst()
                        .orElse(null);
                if (shanCard == null) {
                    log.warn("[闪] 找不到选中的【闪】(instanceId={})，跳过", selectedInstanceId);
                    break;
                }

                // ── 闪的使用牌生命周期（不含 EFFECT，改为取消原【杀】） ──
                String sid = targetplayer.getPlayerId();

                // ① 移入牌桌中央
                eventBus.publish(GameEvent.builder()
                        .type(GameEventType.CARD_MOVE)
                        .sourceId(sid).build()
                        .putData("player", targetplayer)
                        .putData("cards", List.of(shanCard))
                        .putData("destination", "TABLE_CENTER"),
                        match);

                // ② BEFORE
                eventBus.publish(GameEvent.builder()
                        .type(GameEventType.CARD_USE_BEFORE)
                        .sourceId(sid).build()
                        .putData("useplayer", targetplayer)
                        .putData("targetplayer", null)
                        .putData("card", shanCard),
                        match);

                // ③ ACTIVE
                eventBus.publish(GameEvent.builder()
                        .type(GameEventType.CARD_USE_ACTIVE)
                        .sourceId(sid).build()
                        .putData("useplayer", targetplayer)
                        .putData("targetplayer", null)
                        .putData("card", shanCard),
                        match);

                // ④ 代替 EFFECT → 取消原【杀】的效果，并将杀移入弃牌堆
                event.setCancelled(true);
                eventBus.publish(GameEvent.builder()
                        .type(GameEventType.CARD_MOVE)
                        .sourceId(useplayer.getPlayerId()).build()
                        .putData("player", useplayer)
                        .putData("cards", List.of(card))
                        .putData("destination", "DISCARD_PILE"),
                        match);

                // ⑤ AFTER
                eventBus.publish(GameEvent.builder()
                        .type(GameEventType.CARD_USE_AFTER)
                        .sourceId(sid).build()
                        .putData("useplayer", targetplayer)
                        .putData("targetplayer", null)
                        .putData("card", shanCard)
                        .putData("cancelled", false),
                        match);

                // ⑥ 移入弃牌堆
                eventBus.publish(GameEvent.builder()
                        .type(GameEventType.CARD_MOVE)
                        .sourceId(sid).build()
                        .putData("player", targetplayer)
                        .putData("cards", List.of(shanCard))
                        .putData("destination", "DISCARD_PILE"),
                        match);

                break;
            }

            // 取消 → 回到 2a 重新选牌
            // 超时 → 视为放弃，跳出循环
            if (confirmResult == ConfirmResult.TIMEOUT) {
                log.info("[闪] 玩家 {} 确认超时，杀效果继续执行", targetplayer.getPlayerId());
                break;
            }
            // cancel → continue 循环，重新选牌
        }
    }

    // ================================================================
    //  HAND_STATUS 推送
    // ================================================================

    private void pushHandStatus(GamePlayer targetplayer, GameMatch match) {
        Map<Long, CardCheckResult> results = new LinkedHashMap<>();
        for (CardInstance handCard : targetplayer.getHandCards()) {
            if ("shan".equals(handCard.getDefId())) {
                results.put(handCard.getInstanceId(),
                        new CardCheckResult(CardActionStatus.PLAYABLE, null));
            } else {
                results.put(handCard.getInstanceId(),
                        new CardCheckResult(CardActionStatus.NOT_SELECTABLE, "不可使用"));
            }
        }

        List<Map<String, Object>> cardStatusList = new ArrayList<>();
        for (Map.Entry<Long, CardCheckResult> entry : results.entrySet()) {
            Map<String, Object> cardMap = new LinkedHashMap<>();
            cardMap.put("instanceId", entry.getKey());
            cardMap.put("status", entry.getValue().status.getCode());
            cardMap.put("statusName", entry.getValue().status.name());
            cardMap.put("reason", entry.getValue().reason);
            cardStatusList.add(cardMap);
        }

        Map<String, Object> handStatus = new LinkedHashMap<>();
        handStatus.put("type", "HAND_STATUS");
        handStatus.put("cards", cardStatusList);
        handStatus.put("phase", match.getCurrentPhase().name());

        GamePlayer curPlayer = match.currentPlayer();
        boolean isMyTurn = curPlayer != null && curPlayer.getPlayerId().equals(targetplayer.getPlayerId());
        handStatus.put("isMyTurn", isMyTurn);

        sessionManager.sendMessage(targetplayer.getPlayerId(), toJson(handStatus));
    }

    // ================================================================
    //  交互消息构造
    // ================================================================

    /**
     * 第一步：选择【闪】
     *
     * <p>仅有一个"取消"按钮，玩家点击手牌中的【闪】即可自动提交选牌。</p>
     */
    private Map<String, Object> buildSelectMessage(int turnTime, String useplayerName) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "ACTION_DECISION");
        message.put("timeout", turnTime);
        message.put("description", String.format("【%s】对你使用一张【杀】，请使用一张【闪】", useplayerName));
        message.put("actions", List.of(
                Map.of("text", "取消", "value", "cancel", "type", "primary")));
        message.put("handSelectable", true);
        message.put("handSelectMode", "single");
        message.put("targetSelectable", false);
        return message;
    }

    /**
     * 第二步：确认打出
     *
     * <p>携带 {@code selectedCardIds} 告知前端已选的【闪】，前端会将该牌上浮展示。</p>
     *
     * <ul>
     *   <li>确定 — 点击执行闪效果</li>
     *   <li>取消 — 放弃出闪</li>
     * </ul>
     */
    private Map<String, Object> buildConfirmMessage(int turnTime, long selectedInstanceId) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "ACTION_DECISION");
        message.put("timeout", turnTime);
        message.put("description", "确定要使用【闪】来抵消【杀】吗？");
        message.put("actions", List.of(
                Map.of("text", "确定", "value", "confirm", "type", "primary"),
                Map.of("text", "取消", "value", "cancel", "type", "primary")));
        message.put("handSelectable", false);
        message.put("targetSelectable", false);
        message.put("selectedCardIds", List.of(String.valueOf(selectedInstanceId)));
        return message;
    }

    // ================================================================
    //  确认结果枚举
    // ================================================================

    private enum ConfirmResult {
        /** 确认打出 */
        CONFIRMED,
        /** 取消，重新选牌 */
        CANCEL,
        /** 超时/中断，视为放弃 */
        TIMEOUT
    }

    // ================================================================
    //  响应处理
    // ================================================================

    /**
     * 处理第一步选择响应
     *
     * <p>第一步没有"确定"按钮，玩家选牌即提交。前端在玩家点击手牌时会返回
     * {@code selectedCardIds}，此时直接取第一张作为选中的【闪】。</p>
     *
     * @return 选中的【闪】instanceId，取消/超时/未选牌返回 null
     */
    @SuppressWarnings("unchecked")
    private Long handleSelectResponse(Map<String, Object> response, GamePlayer targetplayer) {
        if (response == null) {
            log.info("[闪] 玩家 {} 选择响应为 null，杀效果继续执行", targetplayer.getPlayerId());
            return null;
        }

        String action = (String) response.get("action");
        log.info("[闪] 玩家 {} 选择响应: action={}", targetplayer.getPlayerId(), action);

        // 超时/中断
        if ("timeout".equals(action) || "TIMEOUT".equals(action)
                || "interrupted".equals(action)) {
            log.info("[闪] 玩家 {} 选择超时/中断，杀效果继续执行", targetplayer.getPlayerId());
            return null;
        }

        // 检查是否选中了手牌（选牌即提交）
        Object rawIds = response.get("selectedCardIds");
        if (rawIds instanceof List<?> idList && !idList.isEmpty()) {
            Object first = idList.get(0);
            if (first instanceof String s) {
                return Long.parseLong(s);
            } else if (first instanceof Number n) {
                return n.longValue();
            }
        }

        // 未选中任何牌 → 视为取消
        log.info("[闪] 玩家 {} 未选择任何牌，杀效果继续执行", targetplayer.getPlayerId());
        return null;
    }

    /**
     * 处理第二步确认响应
     *
     * @return {@link ConfirmResult#CONFIRMED} 确认打出；
     *         {@link ConfirmResult#CANCEL}   取消回选牌；
     *         {@link ConfirmResult#TIMEOUT}  超时/中断，视为放弃
     */
    private ConfirmResult handleConfirmResponse(Map<String, Object> response,
                                                  GamePlayer targetplayer,
                                                  Long selectedInstanceId) {
        if (response == null) {
            log.info("[闪] 玩家 {} 确认响应为 null，视为超时", targetplayer.getPlayerId());
            return ConfirmResult.TIMEOUT;
        }

        String action = (String) response.get("action");
        log.info("[闪] 玩家 {} 确认响应: action={}", targetplayer.getPlayerId(), action);

        if ("cancel".equals(action)) {
            log.info("[闪] 玩家 {} 取消确认，可重新选牌", targetplayer.getPlayerId());
            return ConfirmResult.CANCEL;
        }

        if ("timeout".equals(action) || "TIMEOUT".equals(action)
                || "interrupted".equals(action)) {
            log.info("[闪] 玩家 {} 确认超时/中断，视为放弃", targetplayer.getPlayerId());
            return ConfirmResult.TIMEOUT;
        }

        if ("confirm".equals(action)) {
            return ConfirmResult.CONFIRMED;
        }

        // 未知 action → 视为取消
        log.info("[闪] 玩家 {} 未知确认 action={}，视为取消", targetplayer.getPlayerId(), action);
        return ConfirmResult.CANCEL;
    }

    // ================================================================
    //  辅助方法
    // ================================================================

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
            log.warn("[闪] 读取 turnTime 失败，使用默认 15s", e);
        }
        return 15;
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.error("[闪] JSON 序列化失败", e);
            return "{\"type\":\"ERROR\",\"message\":\"序列化失败\"}";
        }
    }

    /**
     * 广播 PLAYER_THINKING 给其他玩家
     *
     * <p>与 {@code PlayPhaseHandler} 中的模式一致，在每次 {@code pushAndAwait} 之前调用，
     * 让其他玩家知道目标玩家正在决策。</p>
     */
    private void broadcastThinking(List<String> allPlayerIds, String playerId, int timeout, String description) {
        Map<String, Object> thinkingMsg = new LinkedHashMap<>();
        thinkingMsg.put("type", "PLAYER_THINKING");
        thinkingMsg.put("playerId", playerId);
        thinkingMsg.put("timeout", timeout);
        thinkingMsg.put("description", description);
        try {
            sessionManager.broadcastToRoom(
                    allPlayerIds,
                    objectMapper.writeValueAsString(thinkingMsg),
                    playerId);
        } catch (Exception e) {
            log.warn("[闪] 广播 PLAYER_THINKING 失败", e);
        }
    }

    // ================================================================
    //  内部类 — 检测结果
    // ================================================================

    private static class CardCheckResult {
        final CardActionStatus status;
        final String reason;

        CardCheckResult(CardActionStatus status, String reason) {
            this.status = status;
            this.reason = reason;
        }
    }
}