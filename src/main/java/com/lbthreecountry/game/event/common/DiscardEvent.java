package com.lbthreecountry.game.event.common;

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

/**
 * 弃牌事件 — 监听 {@code CARD.DISCARD} 触发钩子，执行弃牌生命周期
 *
 * <h3>弃牌生命周期</h3>
 * <pre>
 * CARD.DISCARD (触发钩子)
 *   ├── CARD.DISCARD.BEFORE  (弃牌开始前，可修改 count / 可取消)
 *   ├── CARD.DISCARD.ACTIVE  (弃牌进行中，可修改 count / 可取消)
 *   ├── 实际弃牌操作
 *   └── CARD.DISCARD.AFTER   (弃牌结束后钩子，仅通知)
 * </pre>
 *
 * <h3>触发事件数据字段</h3>
 * <pre>
 * ┌──────────────┬──────────┬────────────────────────────────────────┐
 * │ 字段          │ 类型      │ 说明                                   │
 * ├──────────────┼──────────┼────────────────────────────────────────┤
 * │ playerId     │ String   │ 弃牌玩家 ID（必填）                      │
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

    // ================================================================
    //  依赖
    // ================================================================

    private final EventBus eventBus;
    private final WebSocketSessionManager sessionManager;

    public DiscardEvent(EventBus eventBus, WebSocketSessionManager sessionManager) {
        this.eventBus = eventBus;
        this.sessionManager = sessionManager;
    }

    @PostConstruct
    public void init() {
        eventBus.register(GameEventType.CARD_DISCARD, EventPriority.ENGINE, this::onDiscard);
        log.info("[弃牌事件] 已注册 CARD.DISCARD 监听器 (ENGINE 优先级)");
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
     *   <li><b>实际弃牌操作</b> — 使用 ACTIVE 修改后的数据执行弃牌</li>
     *   <li>{@code CARD.DISCARD.AFTER} — 弃牌结束后钩子（实际使用的数据，仅通知）</li>
     * </ol>
     */
    private void onDiscard(GameEvent event, GameMatch match) {
        // ── 读取调用方传入的参数 ──
        String playerId = event.getData("playerId");
        if (playerId == null) {
            log.warn("[弃牌事件] 事件中无 playerId，忽略");
            return;
        }

        GamePlayer player = match.findPlayer(playerId);
        if (player == null) {
            log.warn("[弃牌事件] 玩家 {} 不存在，忽略", playerId);
            return;
        }

        int count = event.getDataOrDefault("count", 0);
        if (count <= 0) {
            log.warn("[弃牌事件] count={}，无需弃牌", count);
            return;
        }

        log.info("[弃牌事件] 玩家 {} 需要弃 {} 张牌", playerId, count);

        // ── 构造可修改的临时数据对象 ──
        HookData data = new HookData(playerId, count);

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

        // ── 3) 实际弃牌操作（ACTIVE 修改后的数据） ──
        if (data.count <= 0) {
            log.info("[弃牌事件] 弃牌数量为 0，跳过后续流程");
            writeResult(event, data);
            return;
        }

        // TODO: 实际弃牌逻辑（玩家选择弃哪些牌，然后调用 CardManager.discardAll）
        //       暂记本次弃牌信息供 AFTER 钩子和前端通信使用
        data.actualCount = data.count;

        log.info("[弃牌事件] 玩家 {} 弃掉 {} 张牌", playerId, data.actualCount);

        // ── 前端通信（预留） ──
        // TODO: 在此处推送弃牌结果到前端，包含以下信息：
        //       - playerId: 弃牌玩家 ID
        //       - count: 实际弃牌数量
        //       - cardIds: 弃掉的卡牌实例 ID 列表
        //       参考 DamageEvent 的推送模式：
        //       sessionManager.sendMessage(playerId, json);
        //       sessionManager.broadcastToRoom(allPlayerIds, json, playerId);

        // ── 4) 弃牌结束后钩子（实际使用的数据） ──
        data.publishAfterOnly(event, match, eventBus);

        // ── 写入结果到触发事件 ──
        writeResult(event, data);
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
        final String playerId;
        int count;
        int actualCount;
        boolean cancelled;

        HookData(String playerId, int count) {
            this.playerId = playerId;
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
            hookEvent.putData("playerId", playerId)
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
            hookEvent.putData("playerId", playerId)
                    .putData("count", count)
                    .putData("actualCount", actualCount)
                    .putData("cancelled", false);
            eventBus.publish(hookEvent, match);
        }
    }
}