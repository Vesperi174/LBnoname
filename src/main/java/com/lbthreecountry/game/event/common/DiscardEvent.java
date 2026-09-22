package com.lbthreecountry.game.event.common;

import com.lbthreecountry.game.GameMatch;
import com.lbthreecountry.game.GamePlayer;
import com.lbthreecountry.game.event.EventBus;
import com.lbthreecountry.game.event.EventPriority;
import com.lbthreecountry.game.event.GameEvent;
import com.lbthreecountry.game.event.GameEventType;
import com.lbthreecountry.game.interaction.InteractionMessageStack;
import com.lbthreecountry.game.interaction.handler.HandCardFilter;
import com.lbthreecountry.game.interaction.handler.ResponseHandler;
import com.lbthreecountry.model.card.CardInstance;
import com.lbthreecountry.model.enums.impl.GameStatus;
import com.lbthreecountry.websocket.WebSocketSessionManager;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 弃牌事件 — 监听 {@code CARD.DISCARD} 触发钩子，执行弃牌生命周期
 *
 * <h3>弃牌生命周期</h3>
 * <pre>
 * CARD.DISCARD (触发钩子)
 *   ├── CARD.DISCARD.BEFORE  (弃牌开始前，可修改 count / 可取消)
 *   ├── CARD.DISCARD.ACTIVE  (弃牌进行中，可修改 count / 可取消)
 *   ├── 前端选牌交互
 *   │     ├── 推送 HAND_STATUS（所有手牌可选）
 *   │     ├── 发送 ACTION_DECISION "请选择x张牌弃置"
 *   │     └── pushAndAwait() 等待玩家选择
 *   ├── 实际弃牌操作（CardManager.discardAll）
 *   └── CARD.DISCARD.AFTER   (弃牌结束后钩子，仅通知)
 * </pre>
 *
 * <h3>触发事件数据字段</h3>
 * <pre>
 * ┌──────────────┬──────────┬────────────────────────────────────────┐
 * │ 字段          │ 类型      │ 说明                                   │
 * ├──────────────┼──────────┼────────────────────────────────────────┤
 * │ playerId     │ String   │ 弃牌玩家 ID（必填）                      │
 * │ player       │ GamePlayer │ 弃牌玩家对象（可选）                   │
 * │ count        │ int      │ 需要弃牌的数量（默认 0）                  │
 * └──────────────┴──────────┴────────────────────────────────────────┘
 * </pre>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // ===== 触发弃牌 =====
 * GameEvent event = GameEvent.builder()
 *     .type(GameEventType.CARD_DISCARD)
 *     .sourceId(playerId)
 *     .build()
 *     .putData("playerId", playerId)
 *     .putData("count", 2);
 * eventBus.publish(event, match);
 *
 * // ===== 技能监听：修改弃牌数量（BEFORE 或 ACTIVE） =====
 * eventBus.register("CARD.DISCARD.BEFORE", EventPriority.SKILL, (ev, m) -> {
 *     int count = ev.getData("count");
 *     ev.putData("count", count - 1); // 减少弃牌数量
 * });
 *
 * // ===== 技能监听：读取弃牌结果（AFTER） =====
 * eventBus.register("CARD.DISCARD.AFTER", EventPriority.SKILL, (ev, m) -> {
 *     int actual = ev.getDataOrDefault("actualCount", 0);
 * });
 * }</pre>
 */
@Component
public class DiscardEvent {

    private static final Logger log = LoggerFactory.getLogger(DiscardEvent.class);

    /** 弃牌交互默认超时秒数 */
    private static final int DEFAULT_DISCARD_TIMEOUT = 15;

    // ================================================================
    //  依赖
    // ================================================================

    private final EventBus eventBus;
    private final InteractionMessageStack interactionStack;
    private final WebSocketSessionManager sessionManager;
    private final ResponseHandler responseHandler;

    public DiscardEvent(EventBus eventBus,
                        InteractionMessageStack interactionStack,
                        WebSocketSessionManager sessionManager,
                        ResponseHandler responseHandler) {
        this.eventBus = eventBus;
        this.interactionStack = interactionStack;
        this.sessionManager = sessionManager;
        this.responseHandler = responseHandler;
    }

    @PostConstruct
    public void init() {
        eventBus.register(GameEventType.CARD_DISCARD, EventPriority.ENGINE, this::onDiscard);
    }

    // ================================================================
    //  事件回调 — 弃牌生命周期
    // ================================================================

    /**
     * {@code CARD.DISCARD} 事件回调 — 执行弃牌生命周期
     *
     * <p>从事件数据中读取弃牌参数，依次执行：</p>
     * <ol>
     *   <li>{@code CARD.DISCARD.BEFORE} — 弃牌开始前（初始数据，监听器可修改 {@code count} 或取消）</li>
     *   <li>{@code CARD.DISCARD.ACTIVE} — 弃牌进行中（BEFORE 修改后的数据，监听器可修改 {@code count} 或取消）</li>
     *   <li><b>前端选牌交互</b> — 推送 HAND_STATUS + ACTION_DECISION，等待玩家选牌</li>
     *   <li><b>实际弃牌操作</b> — 调用 CardManager.discardAll 执行弃牌</li>
     *   <li>{@code CARD.DISCARD.AFTER} — 弃牌结束后钩子（实际使用的数据，仅通知）</li>
     * </ol>
     */
    @SuppressWarnings("unchecked")
    private void onDiscard(GameEvent event, GameMatch match) {
        // ── 读取调用方传入的参数 ──
        GamePlayer player = event.getData("player");
        if (player == null) {
            String playerId = event.getData("playerId");
            if (playerId == null) {
                log.warn("[弃牌事件] 事件中无 player，忽略");
                return;
            }
            player = match.findPlayer(playerId);
            if (player == null) {
                log.warn("[弃牌事件] 玩家 {} 不存在，忽略", playerId);
                return;
            }
        }

        int count = event.getDataOrDefault("count", 0);
        if (count <= 0) {
            log.warn("[弃牌事件] count={}，无需弃牌", count);
            return;
        }

        String playerId = player.getPlayerId();
        log.info("[弃牌事件] 玩家 {} 需要弃 {} 张牌", playerId, count);

        // ── 构造可修改的临时数据对象 ──
        HookData data = new HookData(player, count);

        // ── 1) 弃牌开始前（初始数据） ──
        data.publishAndSync(GameEventType.CARD_DISCARD_BEFORE, event, match, eventBus);
        if (data.cancelled) {
            log.info("[弃牌事件] BEFORE 钩子已取消 — {} 的弃牌被取消", playerId);
            writeResult(event, data);
            return;
        }

        // ── 2) 弃牌进行中（BEFORE 修改后的数据） ──
        data.publishAndSync(GameEventType.CARD_DISCARD_ACTIVE, event, match, eventBus);
        if (data.cancelled) {
            log.info("[弃牌事件] ACTIVE 钩子已取消 — {} 的弃牌被取消", playerId);
            writeResult(event, data);
            return;
        }

        if (data.count <= 0) {
            log.info("[弃牌事件] 弃牌数量为 0，跳过后续流程");
            writeResult(event, data);
            return;
        }

        // ── 3) 前端选牌交互 ──
        List<String> selectedCardIds = doDiscardInteraction(match, player, data.count);
        if (selectedCardIds == null) {
            log.info("[弃牌事件] 玩家 {} 选牌交互未完成，跳过弃牌", playerId);
            writeResult(event, data);
            return;
        }

        // 如果选择的牌数量不足，随机补选
        if (selectedCardIds.size() < data.count) {
            int shortage = data.count - selectedCardIds.size();
            log.info("[弃牌事件] 玩家 {} 只选了 {} 张牌，还需随机补选 {} 张",
                    playerId, selectedCardIds.size(), shortage);
            Set<String> alreadySelected = new HashSet<>(selectedCardIds);
            List<String> fillUp = player.getHandCards().stream()
                    .map(c -> String.valueOf(c.getInstanceId()))
                    .filter(id -> !alreadySelected.contains(id))
                    .collect(Collectors.toList());
            Collections.shuffle(fillUp, new Random());
            fillUp.stream().limit(shortage).forEach(selectedCardIds::add);
            log.info("[弃牌事件] 随机补选完成，最终弃牌列表: {}", selectedCardIds);
        }

        // ── 4) 实际弃牌操作：通过 CARD.MOVE 事件移入弃牌堆 ──
        Set<Long> selectedIdSet = selectedCardIds.stream()
                .map(Long::valueOf)
                .collect(Collectors.toSet());
        List<CardInstance> cardsToDiscard = player.getHandCards().stream()
                .filter(c -> selectedIdSet.contains(c.getInstanceId()))
                .collect(Collectors.toList());

        if (!cardsToDiscard.isEmpty()) {
            GameEvent moveEvent = GameEvent.builder()
                    .type(GameEventType.CARD_MOVE)
                    .sourceId(playerId)
                    .build()
                    .putData("cards", cardsToDiscard)
                    .putData("destination", "DISCARD_PILE")
                    .putData("player", player);
            eventBus.publish(moveEvent, match);
            data.actualCount = cardsToDiscard.size();
            log.info("[弃牌事件] 玩家 {} 弃掉 {} 张牌: {}",
                    playerId, data.actualCount, selectedCardIds);
        } else {
            log.warn("[弃牌事件] 玩家 {} 选择的牌不在手牌中: {}", playerId, selectedCardIds);
        }

        // ── 前端通信（预留） ──
        // TODO: 在此处推送弃牌结果到前端，包含以下信息：
        //       - playerId: 弃牌玩家 ID
        //       - count: 实际弃牌数量
        //       - cardIds: 弃掉的卡牌实例 ID 列表
        //       参考 DamageEvent 的推送模式：
        //       sessionManager.sendMessage(playerId, json);
        //       sessionManager.broadcastToRoom(allPlayerIds, json, playerId);

        // ── 5) 弃牌结束后钩子（实际使用的数据） ──
        data.publishAfterOnly(event, match, eventBus);

        // ── 写入结果到触发事件 ──
        writeResult(event, data);
    }

    // ================================================================
    //  选牌交互
    // ================================================================

    /**
     * 执行前端选牌交互：推送手牌状态 → 发送 ACTION_DECISION → 等待响应 → 置灰手牌
     *
     * <p>交互成功后，该方法内部已在返回前将 HAND_STATUS 置为全部不可选，
     * 调用方无需重复调用。</p>
     *
     * @param match        当前对局
     * @param player       弃牌玩家
     * @param discardCount 需要弃置的牌数
     * @return 玩家选中的卡牌 instanceId 列表，交互失败（如游戏结束）返回 {@code null}
     */
    @SuppressWarnings("unchecked")
    private List<String> doDiscardInteraction(GameMatch match, GamePlayer player, int discardCount) {
        String playerId = player.getPlayerId();
        int timeout = DEFAULT_DISCARD_TIMEOUT;
        int backendTimeout = timeout + 5;

        // ── 推送 HAND_STATUS：全部手牌标记为可选 ──
        responseHandler.pushHandStatus(match, player, HandCardFilter.all());

        // ── 广播思考状态给其他玩家 ──
        List<String> allPlayerIds = match.getPlayers().stream()
                .map(GamePlayer::getPlayerId)
                .collect(Collectors.toList());
        responseHandler.broadcastThinking(allPlayerIds, playerId,
                timeout, timeout, "弃牌阶段思考中");

        // ── 构造 ACTION_DECISION 消息 ──
        Map<String, Object> message = buildDiscardMessage(discardCount, timeout);

        log.info("[弃牌事件] ⏳ 等待玩家 {} 选择 {} 张牌弃置...", playerId, discardCount);
        Map<String, Object> response = interactionStack.pushAndAwait(
                match.getRoomId(), playerId, message, sessionManager, backendTimeout);

        // ── 处理响应 ──
        String action = response != null ? (String) response.get("action") : null;
        String respType = response != null ? (String) response.get("type") : null;
        List<String> selectedCardIds = response != null
                ? (List<String>) response.get("selectedCardIds")
                : null;

        // 游戏已结束或房间已销毁
        if (match.getStatus() == GameStatus.FINISHED || "CANCELLED".equals(respType)) {
            log.info("[弃牌事件] 游戏已结束或房间已销毁");
            return null;
        }

        // ── 前端已返回 → 立即置灰手牌 ──
        responseHandler.pushHandStatus(match, player, HandCardFilter.none("弃牌阶段结束"));

        // 超时或中断 → 后端随机选牌弃置
        if ("timeout".equals(action) || "TIMEOUT".equals(action) || "interrupted".equals(action)) {
            log.info("[弃牌事件] 玩家 {} 弃牌超时/中断 → 后端随机选牌弃置", playerId);
            return randomSelectCards(player, discardCount);
        }

        // 玩家确认选择（可能为空列表，后续由 onDiscard 补选兜底）
        if ("discard".equals(action)) {
            if (selectedCardIds == null) {
                selectedCardIds = new ArrayList<>();
            }
            log.info("[弃牌事件] 玩家 {} 选择了 {} 张牌弃置: {}",
                    playerId, selectedCardIds.size(), selectedCardIds);
            return selectedCardIds;
        }

        log.warn("[弃牌事件] 玩家 {} 响应异常: action={}, selectedCardIds={}",
                playerId, action, selectedCardIds);
        return null;
    }

    /**
     * 构造弃牌阶段的 ACTION_DECISION 消息
     *
     * @param discardCount 需要弃置的牌数
     * @param timeout      前端展示的倒计时秒数
     * @return 交互消息体
     */
    private Map<String, Object> buildDiscardMessage(int discardCount, int timeout) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "ACTION_DECISION");
        message.put("timeout", timeout);
        message.put("totalTimeout", timeout);
        message.put("description", String.format("请选择 %d 张牌弃置", discardCount));
        message.put("actions", List.of(
                Map.of("text", "确定弃置", "value", "discard", "type", "default")));
        message.put("handSelectable", true);
        message.put("handSelectMode", "multi");
        message.put("selectCount", discardCount);
        message.put("targetSelectable", false);
        return message;
    }

    // ================================================================
    //  后端兜底 — 随机选牌
    // ================================================================

    /**
     * 从玩家手牌中随机选择指定数量的卡牌（后端兜底策略）
     * <p>用于以下场景：
     * <ul>
     *   <li>前端超时未响应</li>
     *   <li>玩家选择的牌数不足（配合 {@code onDiscard} 中的补选逻辑）</li>
     * </ul></p>
     *
     * @param player 玩家
     * @param count  需要选择的牌数
     * @return 选中的卡牌 instanceId 列表（String 格式）
     */
    private List<String> randomSelectCards(GamePlayer player, int count) {
        List<CardInstance> handCards = new ArrayList<>(player.getHandCards());
        Collections.shuffle(handCards, new Random());
        return handCards.stream()
                .limit(count)
                .map(c -> String.valueOf(c.getInstanceId()))
                .collect(Collectors.toList());
    }

    // ================================================================
    //  内部方法
    // ================================================================

    /** 将弃牌结果写回触发事件 */
    private void writeResult(GameEvent event, HookData data) {
        event.putData("actualCount", data.actualCount);
        event.putData("cancelled", data.cancelled);
    }

    // ================================================================
    //  HookData — 临时数据容器
    // ================================================================

    /**
     * 临时数据容器 — 发布钩子后从事件中回读可能被修改的数据
     */
    private static class HookData {
        final GamePlayer player;
        int count;
        int actualCount;
        boolean cancelled;

        HookData(GamePlayer player, int count) {
            this.player = player;
            this.count = count;
        }

        /**
         * 发布钩子事件并同步数据回主事件
         * <p>用于 {@code BEFORE} / {@code ACTIVE} 钩子，监听器可修改 {@code count} 或取消。</p>
         */
        void publishAndSync(String hookType, GameEvent originalEvent,
                            GameMatch match, EventBus eventBus) {
            GameEvent hookEvent = GameEvent.builder()
                    .type(hookType)
                    .sourceId(originalEvent.getSourceId())
                    .build();
            hookEvent.putData("player", player)
                    .putData("playerId", player.getPlayerId())
                    .putData("count", count);
            eventBus.publish(hookEvent, match);

            // 检查钩子事件是否被监听器取消
            if (hookEvent.isCancelled()) {
                this.cancelled = true;
                return; // 被取消，不再回读数据
            }

            // 回读监听器可能修改后的值
            Integer newCount = hookEvent.getData("count");
            if (newCount != null) this.count = newCount;
        }

        /**
         * 发布 {@code AFTER} 钩子事件（仅通知，不回读数据）
         */
        void publishAfterOnly(GameEvent originalEvent, GameMatch match, EventBus eventBus) {
            GameEvent hookEvent = GameEvent.builder()
                    .type(GameEventType.CARD_DISCARD_AFTER)
                    .sourceId(originalEvent.getSourceId())
                    .build();
            hookEvent.putData("player", player)
                    .putData("playerId", player.getPlayerId())
                    .putData("count", count)
                    .putData("actualCount", actualCount)
                    .putData("cancelled", false);
            eventBus.publish(hookEvent, match);
        }
    }
}