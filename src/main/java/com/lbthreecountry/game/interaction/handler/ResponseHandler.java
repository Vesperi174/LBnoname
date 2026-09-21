package com.lbthreecountry.game.interaction.handler;

import com.lbthreecountry.entity.GameRoom;
import com.lbthreecountry.game.GameMatch;
import com.lbthreecountry.game.GamePlayer;
import com.lbthreecountry.game.interaction.InteractionMessageStack;
import com.lbthreecountry.model.card.CardInstance;
import com.lbthreecountry.service.RoomService;
import com.lbthreecountry.websocket.WebSocketSessionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 通用响应处理器 — 封装与前端交互的阻塞式请求/响应模式
 *
 * <p>此框架提取了 {@code InteractionMessageStack.pushAndAwait} 的通用交互模式，
 * 提供选牌、确认、目标选择等高层抽象，减少重复代码。</p>
 *
 * <h3>核心能力</h3>
 * <ul>
 *   <li>{@link #selectCardWithConfirm} — 选牌 → 确认 → 取消重选循环（两阶段交互）</li>
 *   <li>{@link #selectCard} — 选牌即确认（单阶段交互）</li>
 *   <li>{@link #requestDecision} — 自定义按钮决策</li>
 *   <li>{@link #confirmAction} — 纯确认/取消</li>
 *   <li>{@link #pushHandStatus} — 推送手牌可用性状态</li>
 *   <li>{@link #broadcastThinking} — 广播思考状态</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * <p>此类的方法设计为在游戏线程（如 {@code botScheduler}）上调用，
 * 会阻塞等待前端响应。不能在 Netty/WebSocket 的 I/O 线程上调用。</p>
 *
 * <h3>设计原则</h3>
 * <ul>
 *   <li>不改变已有交互流程，仅将重复模式提取为可复用方法</li>
 *   <li>返回 {@link ResponseResult} 泛型结果，调用方通过状态判断后续逻辑</li>
 *   <li>所有超时、中断等异常情况统一处理</li>
 * </ul>
 */
@Component
public class ResponseHandler {

    private static final Logger log = LoggerFactory.getLogger(ResponseHandler.class);

    private final InteractionMessageStack interactionStack;
    private final WebSocketSessionManager sessionManager;
    private final RoomService roomService;
    private final ObjectMapper objectMapper;

    public ResponseHandler(InteractionMessageStack interactionStack,
                           WebSocketSessionManager sessionManager,
                           RoomService roomService) {
        this.interactionStack = interactionStack;
        this.sessionManager = sessionManager;
        this.roomService = roomService;
        this.objectMapper = new ObjectMapper();
    }

    // ================================================================
    //  对外核心方法
    // ================================================================

    /**
     * 两阶段交互：选牌 → 确认 → 取消重选循环
     *
     * <p>经典用例：【闪】响应【杀】：</p>
     * <ol>
     *   <li>推送 HAND_STATUS（仅【闪】可选）</li>
     *   <li>发送选牌消息，等待玩家选牌</li>
     *   <li>用户选牌后，发送确认消息</li>
     *   <li>确认 → 返回确认结果；取消 → 回到第 2 步；超时/中断 → 返回放弃</li>
     * </ol>
     *
     * <p>在每次 {@code pushAndAwait} 之前会自动广播 {@code PLAYER_THINKING}。</p>
     *
     * @param match  当前对局
     * @param player 交互目标玩家
     * @param config 配置参数（文案、过滤器、超时等）
     * @return 响应结果，{@link ResponseResult#getValue()} 为选中的卡牌 instanceId
     */
    public ResponseResult<Long> selectCardWithConfirm(GameMatch match,
                                                       GamePlayer player,
                                                       SelectCardConfig config) {
        // ── 获取所有玩家 ID（用于广播 PLAYER_THINKING） ──
        List<String> allPlayerIds = match.getPlayers().stream()
                .map(GamePlayer::getPlayerId)
                .collect(Collectors.toList());

        // ── 后端兜底超时 ──
        int backendTimeout = config.getTimeout() + 5;

        // ── 推送 HAND_STATUS ──
        pushHandStatus(match, player, config.getFilter());

        // ── 循环：选牌 → 确认 → 取消重选 ──
        while (true) {
            // ── 阶段 1：选牌 ──
            Map<String, Object> selectMsg = buildSelectMessage(config);

            broadcastThinking(allPlayerIds, player.getPlayerId(),
                    config.getTimeout(), config.getThinkingDescription());

            Map<String, Object> selectResp = interactionStack.pushAndAwait(
                    match.getRoomId(), player.getPlayerId(),
                    selectMsg, sessionManager, backendTimeout);

            // 处理选牌响应
            Long selectedId = parseSelectedCardId(selectResp);
            if (selectedId == null) {
                // 取消/超时 → 视为放弃
                log.debug("[ResponseHandler] 玩家 {} 未选牌或取消", player.getPlayerId());
                return toLongResult(selectResp);
            }

            // ── 不需确认 → 直接返回选中结果 ──
            if (!config.isRequireConfirm()) {
                return ResponseResult.confirmed(selectedId, selectResp);
            }

            // ── 阶段 2：确认 ──
            Map<String, Object> confirmMsg = buildConfirmMessage(config, selectedId);

            broadcastThinking(allPlayerIds, player.getPlayerId(),
                    config.getTimeout(), config.getThinkingDescription());

            Map<String, Object> confirmResp = interactionStack.pushAndAwait(
                    match.getRoomId(), player.getPlayerId(),
                    confirmMsg, sessionManager, backendTimeout);

            ResponseStatus confirmStatus = parseConfirmStatus(confirmResp);

            if (confirmStatus == ResponseStatus.CONFIRMED) {
                return ResponseResult.confirmed(selectedId, confirmResp);
            }

            if (confirmStatus == ResponseStatus.TIMEOUT
                    || confirmStatus == ResponseStatus.INTERRUPTED) {
                return new ResponseResult<>(confirmStatus, null, confirmResp);
            }

            // CANCEL → 回到循环开始，重新选牌
            log.debug("[ResponseHandler] 玩家 {} 取消确认，重新选牌", player.getPlayerId());
        }
    }

    /**
     * 单阶段交互：选牌即确认（无确认步骤）
     *
     * <p>适用于选牌即生效的场景，如某些技能选择目标牌。</p>
     *
     * @param match  当前对局
     * @param player 交互目标玩家
     * @param config 配置参数
     * @return 响应结果，{@link ResponseResult#getValue()} 为选中的卡牌 instanceId
     */
    public ResponseResult<Long> selectCard(GameMatch match,
                                            GamePlayer player,
                                            SelectCardConfig config) {
        // 复用两阶段方法，但关闭 requireConfirm
        SelectCardConfig noConfirm = SelectCardConfig.builder()
                .selectDescription(config.getSelectDescription())
                .filter(config.getFilter())
                .requireConfirm(false)
                .timeout(config.getTimeout())
                .selectActions(config.getSelectActions())
                .thinkingDescription(config.getThinkingDescription())
                .build();

        return selectCardWithConfirm(match, player, noConfirm);
    }

    /**
     * 通用决策请求 — 发送自定义按钮交互消息，等待玩家点击
     *
     * <p>适用于需要玩家选择操作（如"出杀"/"取消"）但无需选牌的场景。</p>
     *
     * @param match       当前对局
     * @param playerId    目标玩家 ID
     * @param description 提示文案
     * @param actions     按钮列表（如 {@code List.of(Map.of("text", "确定", "value", "confirm", "type", "primary"))}）
     * @param timeout     超时秒数
     * @return 响应结果，{@link ResponseResult#getValue()} 为原始响应 Map
     */
    public ResponseResult<Map<String, Object>> requestDecision(GameMatch match,
                                                                String playerId,
                                                                String description,
                                                                List<Map<String, Object>> actions,
                                                                int timeout) {
        int backendTimeout = timeout + 5;

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "ACTION_DECISION");
        message.put("timeout", timeout);
        message.put("description", description);
        message.put("actions", actions);
        message.put("handSelectable", false);
        message.put("targetSelectable", false);

        Map<String, Object> response = interactionStack.pushAndAwait(
                match.getRoomId(), playerId, message, sessionManager, backendTimeout);

        return ResponseResult.fromRaw(response);
    }

    /**
     * 纯确认交互 — 发送"确定/取消"消息
     *
     * @param match       当前对局
     * @param playerId    目标玩家 ID
     * @param description 提示文案
     * @param timeout     超时秒数
     * @return 响应状态
     */
    public ResponseStatus confirmAction(GameMatch match,
                                         String playerId,
                                         String description,
                                         int timeout) {
        List<Map<String, Object>> actions = List.of(
                Map.of("text", "确定", "value", "confirm", "type", "primary"),
                Map.of("text", "取消", "value", "cancel", "type", "default"));

        ResponseResult<Map<String, Object>> result = requestDecision(
                match, playerId, description, actions, timeout);

        return result.getStatus();
    }

    /**
     * 推送手牌可用性状态（HAND_STATUS）
     *
     * <p>根据过滤器标记每张手牌为 PLAYABLE 或 NOT_SELECTABLE，
     * 发送到前端后，前端会以不同样式展示（可选牌高亮、不可选牌灰显并显示原因）。</p>
     *
     * @param match  当前对局
     * @param player 目标玩家
     * @param filter 手牌过滤器
     */
    public void pushHandStatus(GameMatch match, GamePlayer player, HandCardFilter filter) {
        List<Map<String, Object>> cardStatusList = new ArrayList<>();

        for (CardInstance handCard : player.getHandCards()) {
            CardCheckResult check = filter.check(handCard, player, match);
            Map<String, Object> cardMap = new LinkedHashMap<>();
            cardMap.put("instanceId", handCard.getInstanceId());
            cardMap.put("status", check.getStatus().getCode());
            cardMap.put("statusName", check.getStatus().name());
            cardMap.put("reason", check.getReason());
            cardStatusList.add(cardMap);
        }

        Map<String, Object> handStatus = new LinkedHashMap<>();
        handStatus.put("type", "HAND_STATUS");
        handStatus.put("cards", cardStatusList);
        handStatus.put("phase", match.getCurrentPhase().name());

        GamePlayer curPlayer = match.currentPlayer();
        boolean isMyTurn = curPlayer != null
                && curPlayer.getPlayerId().equals(player.getPlayerId());
        handStatus.put("isMyTurn", isMyTurn);

        sessionManager.sendMessage(player.getPlayerId(), toJson(handStatus));
    }

    /**
     * 广播思考状态（PLAYER_THINKING）给除目标玩家外的所有人
     *
     * <p>在每次 {@code pushAndAwait} 之前调用，让其他玩家知道目标玩家正在决策。</p>
     *
     * @param allPlayerIds 房间内所有玩家 ID 列表
     * @param playerId     正在决策的玩家 ID
     * @param timeout      超时秒数
     * @param description  描述文字（如"玩家【闪】思考中"）
     */
    public void broadcastThinking(List<String> allPlayerIds,
                                   String playerId,
                                   int timeout,
                                   String description) {
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
            log.warn("[ResponseHandler] 广播 PLAYER_THINKING 失败", e);
        }
    }

    /**
     * 获取房间设定的操作超时时间
     *
     * @param roomId 房间 ID
     * @return 超时秒数，兜底 15
     */
    public int getTurnTime(String roomId) {
        try {
            GameRoom room = roomService.getRoom(roomId);
            if (room != null && room.getRoomSettings() != null) {
                Object val = room.getRoomSettings().get("turnTime");
                if (val instanceof Number) {
                    return ((Number) val).intValue();
                }
            }
        } catch (Exception e) {
            log.warn("[ResponseHandler] 读取 turnTime 失败，使用默认 15s", e);
        }
        return 15;
    }

    // ================================================================
    //  内部方法 — 消息构造
    // ================================================================

    /**
     * 构造选牌阶段的消息
     */
    private Map<String, Object> buildSelectMessage(SelectCardConfig config) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "ACTION_DECISION");
        message.put("timeout", config.getTimeout());
        message.put("description", config.getSelectDescription());
        message.put("actions", config.getSelectActions() != null
                ? config.getSelectActions()
                : List.of(Map.of("text", "取消", "value", "cancel", "type", "primary")));
        message.put("handSelectable", true);
        message.put("handSelectMode", "single");
        message.put("targetSelectable", false);
        return message;
    }

    /**
     * 构造确认阶段的消息
     */
    private Map<String, Object> buildConfirmMessage(SelectCardConfig config, long selectedInstanceId) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "ACTION_DECISION");
        message.put("timeout", config.getTimeout());
        message.put("description", config.getConfirmDescription() != null
                ? config.getConfirmDescription()
                : "确定要使用这张牌吗？");
        message.put("actions", config.getConfirmActions() != null
                ? config.getConfirmActions()
                : List.of(
                        Map.of("text", "确定", "value", "confirm", "type", "primary"),
                        Map.of("text", "取消", "value", "cancel", "type", "primary")));
        message.put("handSelectable", false);
        message.put("targetSelectable", false);
        message.put("selectedCardIds", List.of(String.valueOf(selectedInstanceId)));
        return message;
    }

    // ================================================================
    //  内部方法 — 响应解析
    // ================================================================

    /**
     * 从选牌响应中解析选中的卡牌 instanceId
     *
     * @return instanceId，未选牌/取消/超时返回 null
     */
    @SuppressWarnings("unchecked")
    private Long parseSelectedCardId(Map<String, Object> response) {
        if (response == null) return null;

        String action = (String) response.get("action");

        // 超时/中断
        if ("timeout".equals(action) || "TIMEOUT".equals(action)
                || "interrupted".equals(action)) {
            return null;
        }

        // 检查是否选中了手牌
        Object rawIds = response.get("selectedCardIds");
        if (rawIds instanceof List<?> idList && !idList.isEmpty()) {
            Object first = idList.get(0);
            if (first instanceof String s) {
                return Long.parseLong(s);
            } else if (first instanceof Number n) {
                return n.longValue();
            }
        }

        return null; // 未选中任何牌 → 视为取消
    }

    /**
     * 解析确认阶段的响应状态
     */
    private ResponseStatus parseConfirmStatus(Map<String, Object> response) {
        if (response == null) return ResponseStatus.TIMEOUT;

        String action = (String) response.get("action");
        String type = (String) response.get("type");

        if ("confirm".equals(action)) {
            return ResponseStatus.CONFIRMED;
        }
        if ("cancel".equals(action)) {
            return ResponseStatus.CANCEL;
        }
        if ("timeout".equals(action) || "TIMEOUT".equals(action)
                || "interrupted".equals(action)) {
            return ResponseStatus.TIMEOUT;
        }
        if ("CANCELLED".equals(type)) {
            return ResponseStatus.INTERRUPTED;
        }

        // 未知 action → 视为取消
        return ResponseStatus.CANCEL;
    }

    // ================================================================
    //  辅助方法
    // ================================================================

    /**
     * 将原始响应转换为 {@code ResponseResult<Long>}（值为 null）
     *
     * <p>用于选牌取消/超时场景，此时 value 无意义，仅传递状态。</p>
     */
    private ResponseResult<Long> toLongResult(Map<String, Object> raw) {
        if (raw == null) {
            return new ResponseResult<>(ResponseStatus.INTERRUPTED, null, null);
        }
        String action = (String) raw.get("action");
        String type = (String) raw.get("type");
        if ("cancel".equals(action)) {
            return new ResponseResult<>(ResponseStatus.CANCEL, null, raw);
        }
        if ("timeout".equals(action) || "TIMEOUT".equals(action)
                || "interrupted".equals(action)) {
            return new ResponseResult<>(ResponseStatus.TIMEOUT, null, raw);
        }
        if ("CANCELLED".equals(type)) {
            return new ResponseResult<>(ResponseStatus.INTERRUPTED, null, raw);
        }
        return new ResponseResult<>(ResponseStatus.CANCEL, null, raw);
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.error("[ResponseHandler] JSON 序列化失败", e);
            return "{\"type\":\"ERROR\",\"message\":\"序列化失败\"}";
        }
    }
}