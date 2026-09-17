package com.lbthreecountry.game.event.common;

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
 * 造成伤害事件 — 监听 {@code DAMAGE_CAUSE} 触发钩子，执行造成伤害生命周期
 *
 * <h3>事件数据格式</h3>
 * <pre>
 * ┌──────────────┬──────────┬──────────────────────────────────────────┐
 * │ 字段          │ 类型      │ 说明                                     │
 * ├──────────────┼──────────┼──────────────────────────────────────────┤
 * │ sourceType   │ String   │ 伤害来源类型（必填）                      │
 * │              │          │  BASIC_CARD / STRATEGY_CARD /            │
 * │              │          │  EQUIPMENT_CARD / SKILL / PLAYER         │
 * │ sourceName   │ String   │ 伤害来源具体名称（如 sha / juedou / 技能名）│
 * │ targetId     │ String   │ 受到伤害的玩家 ID（必填）                  │
 * │ damage       │ int      │ 伤害点数（默认 1）                        │
 * │ element      │ String   │ 伤害属性：null(无属性) / FIRE / THUNDER   │
 * └──────────────┴──────────┴──────────────────────────────────────────┘
 * </pre>
 */
@Component
public class DamageEvent {

    private static final Logger log = LoggerFactory.getLogger(DamageEvent.class);

    // ================================================================
    //  伤害来源类型常量
    // ================================================================
    public static final String SOURCE_BASIC_CARD = "BASIC_CARD";
    public static final String SOURCE_STRATEGY_CARD = "STRATEGY_CARD";
    public static final String SOURCE_EQUIPMENT_CARD = "EQUIPMENT_CARD";
    public static final String SOURCE_SKILL = "SKILL";
    /** 玩家直接造成的伤害（非卡牌/技能） */
    public static final String SOURCE_PLAYER = "PLAYER";

    // ================================================================
    //  伤害属性常量
    // ================================================================
    /** 无属性伤害（物理） */
    public static final String ELEMENT_NONE = null;
    /** 火焰伤害 */
    public static final String ELEMENT_FIRE = "FIRE";
    /** 雷电伤害 */
    public static final String ELEMENT_THUNDER = "THUNDER";

    private final EventBus eventBus;
    private final WebSocketSessionManager sessionManager;

    public DamageEvent(EventBus eventBus, WebSocketSessionManager sessionManager) {
        this.eventBus = eventBus;
        this.sessionManager = sessionManager;
    }

    @PostConstruct
    public void init() {
        eventBus.register(GameEventType.DAMAGE_CAUSE, EventPriority.ENGINE, this::onDamageCause);
        log.info("[造成伤害事件] 已注册 DAMAGE_CAUSE 监听器 (ENGINE 优先级)");
    }

    /**
     * {@code DAMAGE_CAUSE} 事件回调
     *
     * <p>从事件数据中读取伤害参数，依次执行以下流程：</p>
     * <ol>
     *   <li>{@code DAMAGE.BEFORE} — 造成伤害前（初始数据，监听器可修改）</li>
     *   <li>{@code DAMAGE.ACTIVE} — 造成伤害时（BEFORE 修改后的数据，监听器可修改）</li>
     *   <li>实际扣血 — 使用 ACTIVE 修改后的数据执行扣减体力</li>
     *   <li>{@code DAMAGE.AFTER} — 造成伤害后（实际使用的数据，仅通知）</li>
     * </ol>
     */
    private void onDamageCause(GameEvent event, com.lbthreecountry.game.GameMatch match) {
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

        com.lbthreecountry.game.GamePlayer target = event.getData("target");
        if (target == null) {
            String targetId = event.getData("targetId");
            if (targetId == null) {
                log.warn("[造成伤害事件] 缺少 target，忽略");
                return;
            }
            target = match.findPlayer(targetId);
            if (target == null) {
                log.warn("[造成伤害事件] 目标 {} 不存在，忽略", targetId);
                return;
            }
        }
        Integer damage = event.getData("damage");
        String element = event.getData("element");

        if (damage == null) damage = 1;
        if (source == null) {
            log.warn("[造成伤害事件] 缺少 source，忽略");
            return;
        }

        log.debug("[造成伤害事件] source={}, target={}, damage={}, element={}",
                source, target.getPlayerId(), damage, element);

        // ── 构造可修改的临时数据对象 ──
        HookData data = new HookData(source, target, damage, element);

        // ── 1) 造成伤害前（初始数据） ──
        data.publishAndSync(GameEventType.BEFORE_DAMAGE, event, match, eventBus);
        if (data.cancelled) {
            log.info("[造成伤害事件] BEFORE 钩子已取消伤害，跳过");
            return;
        }

        // ── 2) 造成伤害时（BEFORE 修改后的数据） ──
        data.publishAndSync(GameEventType.DAMAGE_ACTIVE, event, match, eventBus);
        if (data.cancelled) {
            log.info("[造成伤害事件] ACTIVE 钩子已取消伤害，跳过");
            return;
        }

        // ── 3) 实际造成伤害行为（ACTIVE 修改后的数据） ──
        if (data.damage <= 0) {
            log.info("[造成伤害事件] 伤害量为 0，跳过扣血和 AFTER 钩子");
            return;
        }
        if (target == null || !target.isAlive()) {
            log.warn("[造成伤害事件] 目标 {} 不存在或已死亡，跳过扣血和 AFTER 钩子", target.getPlayerId());
            return;
        }

        int actualDamage = Math.min(data.damage, target.getCurrentHp());
        target.setCurrentHp(target.getCurrentHp() - actualDamage);
        log.info("[造成伤害事件] 对 {} 造成 {} 点伤害 (剩余体力: {}/{})",
                target.getPlayerId(), actualDamage, target.getCurrentHp(), target.getMaxHp());

        // ── 前端通信（预留） ──
        // TODO: 在此处推送伤害结果到前端，包含以下信息：
        //       - sourceId: 伤害来源玩家 ID
        //       - targetId: 受击玩家 ID
        //       - sourceType: 伤害来源类型（BASIC_CARD / STRATEGY_CARD / SKILL 等）
        //       - sourceName: 伤害来源名称（sha / juedou / 技能名）
        //       - damage: 实际伤害量
        //       - element: 伤害属性
        //       - remainingHp: 受击玩家剩余体力
        //       - maxHp: 受击玩家最大体力
        //       参考 CardManager.draw() 中的推送模式：
        //       sessionManager.sendMessage(targetId, json);
        //       sessionManager.broadcastToRoom(allPlayerIds, json, targetId);

        // 伤害后数据使用实际值
        data.damage = actualDamage;

        // ── 4) 造成伤害后（实际使用的数据） ──
        data.publishAndSync(GameEventType.AFTER_DAMAGE, event, match, eventBus);
    }

    /**
     * 临时数据容器 — 发布钩子后从事件中回读可能被修改的数据
     */
    private static class HookData {
        Source source;
        com.lbthreecountry.game.GamePlayer target;
        int damage;
        String element;
        /** 钩子事件是否被监听器取消 */
        boolean cancelled;

        HookData(Source source, com.lbthreecountry.game.GamePlayer target, int damage, String element) {
            this.source = source;
            this.target = target;
            this.damage = damage;
            this.element = element;
        }

        void publishAndSync(String hookType, GameEvent originalEvent, com.lbthreecountry.game.GameMatch match, EventBus eventBus) {
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
                    .putData("damage", damage)
                    .putData("element", element);
            eventBus.publish(hookEvent, match);

            // 检查钩子事件是否被监听器取消
            if (hookEvent.isCancelled()) {
                this.cancelled = true;
                return;  // 被取消，不再回读数据
            }

            // 回读监听器可能修改后的值
            Integer dmg = hookEvent.getData("damage");
            if (dmg != null) this.damage = dmg;
            this.element = hookEvent.getData("element");
        }
    }
}