package com.lbthreecountry.game.event.common;

import com.lbthreecountry.game.GameMatch;
import com.lbthreecountry.game.GamePlayer;
import com.lbthreecountry.game.event.EventBus;
import com.lbthreecountry.game.event.EventPriority;
import com.lbthreecountry.game.event.GameEvent;
import com.lbthreecountry.game.event.GameEventType;
import com.lbthreecountry.model.card.CardInstance;
import com.lbthreecountry.websocket.WebSocketSessionManager;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 回复体力事件 — 监听 {@code RECOVER.HP} 触发钩子，执行回复体力生命周期
 *
 * <h3>回复体力生命周期</h3>
 * <pre>
 * RECOVER.HP (触发钩子)
 *   ├── RECOVER.HP.BEFORE  (回复体力前，可修改 amount / 可取消)
 *   ├── RECOVER.HP.ACTIVE  (回复体力时，可修改 amount / 可取消)
 *   ├── 实际回复体力
 *   └── RECOVER.HP.AFTER   (回复体力后钩子，仅通知)
 * </pre>
 *
 * <h3>触发事件数据字段</h3>
 * <pre>
 * ┌──────────────┬──────────┬──────────────────────────────────────────┐
 * │ 字段          │ 类型      │ 说明                                     │
 * ├──────────────┼──────────┼──────────────────────────────────────────┤
 * │ source       │ Source   │ 来源对象（必填），含 type 和 name          │
 * │ targetId     │ String   │ 回复体力的玩家 ID（必填）                  │
 * │ amount       │ int      │ 回复体力点数（默认 1）                    │
 * └──────────────┴──────────┴──────────────────────────────────────────┘
 * </pre>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // ===== 触发回复体力 =====
 * GameEvent event = GameEvent.builder()
 *     .type(GameEventType.RECOVER_HP)
 *     .sourceId(targetId)
 *     .build()
 *     .putData("source", new Source(SOURCE_BASIC_CARD, "tao"))
 *     .putData("targetId", targetId)
 *     .putData("amount", 1);
 * eventBus.publish(event, match);
 *
 * // ===== 技能监听：增加回复量（BEFORE 或 ACTIVE） =====
 * eventBus.register("RECOVER.HP.BEFORE", EventPriority.SKILL, (ev, m) -> {
 *     int amount = ev.getData("amount");
 *     ev.putData("amount", amount + 1);
 * });
 *
 * // ===== 技能监听：取消回复 =====
 * eventBus.register("RECOVER.HP.BEFORE", EventPriority.SKILL, (ev, m) -> {
 *     ev.cancel(); // 阻止这次回复
 * });
 * }</pre>
 */
@Component
public class RecoverHpEvent {

    private static final Logger log = LoggerFactory.getLogger(RecoverHpEvent.class);

    // ================================================================
    //  回复来源类型常量
    // ================================================================

    /** 基本牌回复（如桃） */
    public static final String SOURCE_BASIC_CARD = "BASIC_CARD";
    /** 锦囊牌回复（如桃园结义） */
    public static final String SOURCE_STRATEGY_CARD = "STRATEGY_CARD";
    /** 装备牌回复 */
    public static final String SOURCE_EQUIPMENT_CARD = "EQUIPMENT_CARD";
    /** 技能回复 */
    public static final String SOURCE_SKILL = "SKILL";
    /** 玩家直接回复（非卡牌/技能） */
    public static final String SOURCE_PLAYER = "PLAYER";

    // ================================================================
    //  依赖
    // ================================================================

    private final EventBus eventBus;
    private final WebSocketSessionManager sessionManager;

    public RecoverHpEvent(EventBus eventBus, WebSocketSessionManager sessionManager) {
        this.eventBus = eventBus;
        this.sessionManager = sessionManager;
    }

    @PostConstruct
    public void init() {
        eventBus.register(GameEventType.RECOVER_HP, EventPriority.ENGINE, this::onRecoverHp);
        log.info("[回复体力事件] 已注册 RECOVER.HP 监听器 (ENGINE 优先级)");
    }

    // ================================================================
    //  事件回调 — 回复体力生命周期
    // ================================================================

    /**
     * {@code RECOVER.HP} 事件回调 — 执行回复体力生命周期
     *
     * <p>从事件数据中读取回复体力参数，依次执行：</p>
     * <ol>
     *   <li>{@code RECOVER.HP.BEFORE} — 回复体力前（初始数据，监听器可修改 {@code amount} 或取消）</li>
     *   <li>{@code RECOVER.HP.ACTIVE} — 回复体力时（BEFORE 修改后的数据，监听器可修改 {@code amount} 或取消）</li>
     *   <li><b>实际回复体力</b> — 使用 ACTIVE 修改后的数据执行回复，不超过最大体力</li>
     *   <li>{@code RECOVER.HP.AFTER} — 回复体力后钩子（实际使用的数据，仅通知）</li>
     * </ol>
     */
    private void onRecoverHp(GameEvent event, GameMatch match) {
        // ── 读取调用方传入的参数 ──
        Source source = event.getData("source");
        if (source == null) {
            // 兼容旧版：从独立字符串构造，并尝试附加原始对象
            String sourceType = event.getData("sourceType");
            String sourceName = event.getData("sourceName");
            if (sourceType != null) {
                Object origin = null;
                CardInstance card = event.getData("card");
                if (card != null) origin = card;
                source = new Source(sourceType, sourceName, origin);
            }
        }

        GamePlayer target = event.getData("target");
        if (target == null) {
            String targetId = event.getData("targetId");
            if (targetId == null) {
                log.warn("[回复体力事件] 缺少 target，忽略");
                return;
            }
            target = match.findPlayer(targetId);
            if (target == null) {
                log.warn("[回复体力事件] 目标 {} 不存在，忽略", targetId);
                return;
            }
        }

        Integer amount = event.getData("amount");
        if (amount == null) amount = 1;

        if (source == null) {
            log.warn("[回复体力事件] 缺少 source，忽略");
            return;
        }
        if (amount <= 0) {
            log.warn("[回复体力事件] amount={}，无需回复", amount);
            return;
        }

        log.info("[回复体力事件] {}:{} 对 {} 回复 {} 点体力 (当前体力: {}/{})",
                source.getType(), source.getName(), target.getPlayerId(), amount,
                target.getCurrentHp(), target.getMaxHp());

        // ── 构造可修改的临时数据对象 ──
        HookData data = new HookData(source, target, amount);

        // ── 1) 回复体力前（初始数据） ──
        data.publishAndSync(GameEventType.BEFORE_RECOVER_HP, event, match, eventBus);
        if (data.cancelled) {
            log.info("[回复体力事件] BEFORE 钩子已取消 — {} 的回复被取消", target.getPlayerId());
            writeResult(event, data);
            return;
        }

        // ── 2) 回复体力时（BEFORE 修改后的数据） ──
        data.publishAndSync(GameEventType.RECOVER_HP_ACTIVE, event, match, eventBus);
        if (data.cancelled) {
            log.info("[回复体力事件] ACTIVE 钩子已取消 — {} 的回复被取消", target.getPlayerId());
            writeResult(event, data);
            return;
        }

        // ── 3) 实际回复体力（ACTIVE 修改后的数据） ──
        if (data.amount <= 0) {
            log.info("[回复体力事件] 回复点数为 0，跳过后续流程");
            writeResult(event, data);
            return;
        }

        // 实际回复量不能超过最大体力上限
        int maxCanRecover = target.getMaxHp() - target.getCurrentHp();
        if (maxCanRecover <= 0) {
            log.info("[回复体力事件] 目标 {} 体力已满，无需回复", target.getPlayerId());
            data.amount = 0;
            writeResult(event, data);
            return;
        }

        int actualAmount = Math.min(data.amount, maxCanRecover);
        target.setCurrentHp(target.getCurrentHp() + actualAmount);
        log.info("[回复体力事件] 对 {} 实际回复 {} 点体力 (当前体力: {}/{})",
                target.getPlayerId(), actualAmount, target.getCurrentHp(), target.getMaxHp());

        // ── 前端通信（预留） ──
        // TODO: 在此处推送回复体力结果到前端，包含以下信息：
        //       - targetId: 回复体力的玩家 ID
        //       - sourceType: 回复来源类型
        //       - sourceName: 回复来源名称
        //       - amount: 实际回复量
        //       - remainingHp: 回复后剩余体力
        //       - maxHp: 最大体力
        //       参考 DamageEvent 的推送模式：
        //       sessionManager.sendMessage(targetId, json);
        //       sessionManager.broadcastToRoom(allPlayerIds, json, targetId);

        // 回复后数据使用实际值
        data.amount = actualAmount;

        // ── 4) 回复体力后钩子（实际使用的数据） ──
        data.publishAfterOnly(event, match, eventBus);

        // ── 写入结果到触发事件 ──
        writeResult(event, data);
    }

    // ================================================================
    //  内部方法
    // ================================================================

    /** 将回复体力结果写回触发事件 */
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
        final Source source;
        final GamePlayer target;
        int amount;
        boolean cancelled;

        HookData(Source source, GamePlayer target, int amount) {
            this.source = source;
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
                    .targetId(target.getPlayerId())
                    .build();
            hookEvent.putData("source", source)
                    .putData("sourceType", source.getType())
                    .putData("sourceName", source.getName())
                    .putData("target", target)
                    .putData("targetId", target.getPlayerId())
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
                    .type(GameEventType.AFTER_RECOVER_HP)
                    .sourceId(originalEvent.getSourceId())
                    .targetId(target.getPlayerId())
                    .build();
            hookEvent.putData("source", source)
                    .putData("sourceType", source.getType())
                    .putData("sourceName", source.getName())
                    .putData("target", target)
                    .putData("targetId", target.getPlayerId())
                    .putData("amount", amount)
                    .putData("cancelled", false);
            eventBus.publish(hookEvent, match);
        }
    }
}