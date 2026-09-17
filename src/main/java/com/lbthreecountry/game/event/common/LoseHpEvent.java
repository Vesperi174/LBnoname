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
 * 失去体力事件 — 监听 {@code LOSE.HP} 触发钩子，执行失去体力生命周期
 *
 * <p>失去体力与造成伤害的区别：失去体力不经过伤害计算流程（如护甲、属性伤害等），
 * 是直接减少体力值的操作，通常由技能或特定效果触发。</p>
 *
 * <h3>失去体力生命周期</h3>
 * <pre>
 * LOSE.HP (触发钩子)
 *   ├── LOSE.HP.BEFORE  (失去体力前，可修改 amount / 可取消)
 *   ├── LOSE.HP.ACTIVE  (失去体力时，可修改 amount / 可取消)
 *   ├── 实际扣减体力
 *   └── LOSE.HP.AFTER   (失去体力后钩子，仅通知)
 * </pre>
 *
 * <h3>触发事件数据字段</h3>
 * <pre>
 * ┌──────────────┬──────────┬────────────────────────────────────────┐
 * │ 字段          │ 类型      │ 说明                                   │
 * ├──────────────┼──────────┼────────────────────────────────────────┤
 * │ playerId     │ String   │ 失去体力的玩家 ID（必填）                 │
 * │ amount       │ int      │ 失去体力点数（默认 1）                    │
 * └──────────────┴──────────┴────────────────────────────────────────┘
 * </pre>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // ===== 触发失去体力 =====
 * GameEvent event = GameEvent.builder()
 *     .type(GameEventType.LOSE_HP)
 *     .sourceId(playerId)
 *     .build()
 *     .putData("playerId", playerId)
 *     .putData("amount", 1);
 * eventBus.publish(event, match);
 *
 * // ===== 技能监听：减少失去的体力值（BEFORE 或 ACTIVE） =====
 * eventBus.register("LOSE.HP.BEFORE", EventPriority.SKILL, (ev, m) -> {
 *     int amount = ev.getData("amount");
 *     ev.putData("amount", amount - 1);
 * });
 * }</pre>
 */
@Component
public class LoseHpEvent {

    private static final Logger log = LoggerFactory.getLogger(LoseHpEvent.class);

    // ================================================================
    //  依赖
    // ================================================================

    private final EventBus eventBus;
    private final WebSocketSessionManager sessionManager;

    public LoseHpEvent(EventBus eventBus, WebSocketSessionManager sessionManager) {
        this.eventBus = eventBus;
        this.sessionManager = sessionManager;
    }

    @PostConstruct
    public void init() {
        eventBus.register(GameEventType.LOSE_HP, EventPriority.ENGINE, this::onLoseHp);
        log.info("[失去体力事件] 已注册 LOSE.HP 监听器 (ENGINE 优先级)");
    }

    // ================================================================
    //  事件回调 — 失去体力生命周期
    // ================================================================

    /**
     * {@code LOSE.HP} 事件回调 — 执行失去体力生命周期
     *
     * <p>从事件数据中读取失去体力参数，依次执行：</p>
     * <ol>
     *   <li>{@code LOSE.HP.BEFORE} — 失去体力前（初始数据，监听器可修改 {@code amount} 或取消）</li>
     *   <li>{@code LOSE.HP.ACTIVE} — 失去体力时（BEFORE 修改后的数据，监听器可修改 {@code amount} 或取消）</li>
     *   <li><b>实际扣减体力</b> — 使用 ACTIVE 修改后的数据执行扣减</li>
     *   <li>{@code LOSE.HP.AFTER} — 失去体力后钩子（实际使用的数据，仅通知）</li>
     * </ol>
     */
    private void onLoseHp(GameEvent event, GameMatch match) {
        // ── 读取调用方传入的参数 ──
        GamePlayer target = event.getData("target");
        if (target == null) {
            String playerId = event.getData("playerId");
            if (playerId == null) {
                log.warn("[失去体力事件] 事件中无 target，忽略");
                return;
            }
            target = match.findPlayer(playerId);
            if (target == null) {
                log.warn("[失去体力事件] 玩家 {} 不存在，忽略", playerId);
                return;
            }
        }

        int amount = event.getDataOrDefault("amount", 1);
        if (amount <= 0) {
            log.warn("[失去体力事件] amount={}，无需扣减体力", amount);
            return;
        }

        log.info("[失去体力事件] 玩家 {} 失去 {} 点体力 (当前体力: {}/{})",
                target.getPlayerId(), amount, target.getCurrentHp(), target.getMaxHp());

        // ── 构造可修改的临时数据对象 ──
        HookData data = new HookData(target, amount);

        // ── 1) 失去体力前（初始数据） ──
        data.publishAndSync(GameEventType.BEFORE_LOSE_HP, event, match, eventBus);
        if (data.cancelled) {
            log.info("[失去体力事件] BEFORE 钩子已取消 — {} 的失去体力被取消", target.getPlayerId());
            writeResult(event, data);
            return;
        }

        // ── 2) 失去体力时（BEFORE 修改后的数据） ──
        data.publishAndSync(GameEventType.LOSE_HP_ACTIVE, event, match, eventBus);
        if (data.cancelled) {
            log.info("[失去体力事件] ACTIVE 钩子已取消 — {} 的失去体力被取消", target.getPlayerId());
            writeResult(event, data);
            return;
        }

        // ── 3) 实际扣减体力（ACTIVE 修改后的数据） ──
        if (data.amount <= 0) {
            log.info("[失去体力事件] 失去体力点数为 0，跳过后续流程");
            writeResult(event, data);
            return;
        }

        // 实际失去的体力不能超过当前体力值
        int actualAmount = Math.min(data.amount, target.getCurrentHp());
        target.setCurrentHp(target.getCurrentHp() - actualAmount);
        log.info("[失去体力事件] 玩家 {} 实际失去 {} 点体力 (剩余体力: {}/{})",
                target.getPlayerId(), actualAmount, target.getCurrentHp(), target.getMaxHp());

        // ── 前端通信（预留） ──
        // TODO: 在此处推送失去体力结果到前端，包含以下信息：
        //       - playerId: 失去体力的玩家 ID
        //       - amount: 实际失去的体力值
        //       - remainingHp: 剩余体力
        //       - maxHp: 最大体力
        //       参考 DamageEvent 的推送模式：
        //       sessionManager.sendMessage(playerId, json);
        //       sessionManager.broadcastToRoom(allPlayerIds, json, playerId);

        // 失去体力后数据使用实际值
        data.amount = actualAmount;

        // ── 4) 失去体力后钩子（实际使用的数据） ──
        data.publishAfterOnly(event, match, eventBus);

        // ── 写入结果到触发事件 ──
        writeResult(event, data);
    }

    // ================================================================
    //  内部方法
    // ================================================================

    /** 将失去体力结果写回触发事件 */
    private void writeResult(GameEvent event, HookData data) {
        event.putData("amount", data.amount);
        event.putData("cancelled", data.cancelled);
    }

    // ================================================================
    //  HookData — 临时数据容器
    // ================================================================

    /**
     * 临时数据容器 — 发布钩子后从事件中回读可能被修改的数据
     */
    private static class HookData {
        final GamePlayer target;
        int amount;
        boolean cancelled;

        HookData(GamePlayer target, int amount) {
            this.target = target;
            this.amount = amount;
        }

        /**
         * 发布钩子事件并同步数据回主事件
         * <p>用于 {@code BEFORE} / {@code ACTIVE} 钩子，监听器可修改 {@code amount} 或取消。</p>
         */
        void publishAndSync(String hookType, GameEvent originalEvent,
                            GameMatch match, EventBus eventBus) {
            GameEvent hookEvent = GameEvent.builder()
                    .type(hookType)
                    .sourceId(originalEvent.getSourceId())
                    .build();
            hookEvent.putData("target", target)
                    .putData("playerId", target.getPlayerId())
                    .putData("amount", amount);
            eventBus.publish(hookEvent, match);

            // 检查钩子事件是否被监听器取消
            if (hookEvent.isCancelled()) {
                this.cancelled = true;
                return; // 被取消，不再回读数据
            }

            // 回读监听器可能修改后的值
            Integer newAmount = hookEvent.getData("amount");
            if (newAmount != null) this.amount = newAmount;
        }

        /**
         * 发布 {@code AFTER} 钩子事件（仅通知，不回读数据）
         */
        void publishAfterOnly(GameEvent originalEvent, GameMatch match, EventBus eventBus) {
            GameEvent hookEvent = GameEvent.builder()
                    .type(GameEventType.AFTER_LOSE_HP)
                    .sourceId(originalEvent.getSourceId())
                    .build();
            hookEvent.putData("target", target)
                    .putData("playerId", target.getPlayerId())
                    .putData("amount", amount)
                    .putData("cancelled", false);
            eventBus.publish(hookEvent, match);
        }
    }
}