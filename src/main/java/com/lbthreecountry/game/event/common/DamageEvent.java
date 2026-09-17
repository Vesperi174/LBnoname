package com.lbthreecountry.game.event.common;

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
 * 造成伤害事件 — 监听 {@code DAMAGE_CAUSE} 触发钩子，执行造成伤害生命周期
 *
 * <h3>触发事件数据字段</h3>
 * <pre>
 * ┌──────────────┬──────────────────────┬──────────────────────────────┐
 * │ 字段          │ 类型                  │ 说明                           │
 * ├──────────────┼──────────────────────┼──────────────────────────────┤
 * │ source       │ GamePlayer / null    │ 伤害来源玩家（谁造成的伤害）    │
 * │ sourceCard   │ CardInstance / String / null │ 伤害来源（CardInstance 牌 / String 技能标识 / null）│
 * │ target       │ GamePlayer           │ 受到伤害的玩家（必填）          │
 * │ damage       │ int                  │ 伤害点数（默认 1）              │
 * │ element      │ String               │ 伤害属性：null / FIRE / THUNDER│
 * └──────────────┴──────────────────────┴──────────────────────────────┘
 * </pre>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 使用【杀】造成 1 点无属性伤害
 * eventBus.publish(GameEvent.builder()
 *     .type(GameEventType.DAMAGE_CAUSE)
 *     .sourceId(player.getPlayerId())
 *     .targetId(target.getPlayerId())
 *     .build()
 *     .putData("source", player)
 *     .putData("sourceCard", card)    // 传入牌实例
 *     .putData("target", target)
 *     .putData("damage", 1), match);
 *
 * // 技能造成伤害（无来源牌）
 * eventBus.publish(GameEvent.builder()
 *     .type(GameEventType.DAMAGE_CAUSE)
 *     .sourceId(sourceId)
 *     .targetId(targetId)
 *     .build()
 *     .putData("source", player)
 *     .putData("target", target)
 *     .putData("damage", 2), match);
 * }</pre>
 */
@Component
public class DamageEvent {

    private static final Logger log = LoggerFactory.getLogger(DamageEvent.class);

    public static final String ELEMENT_FIRE = "FIRE";
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
        GamePlayer source = event.getData("source");
        Object sourceCard = event.getData("sourceCard");
        GamePlayer target = event.getData("target");

        if (target == null) {
            log.warn("[造成伤害事件] 缺少 target，忽略");
            return;
        }

        Integer damage = event.getDataOrDefault("damage", 1);
        String element = event.getData("element");

        log.debug("[造成伤害事件] source={}, sourceCard={}, target={}, damage={}, element={}",
                source != null ? source.getPlayerId() : null,
                sourceCard instanceof CardInstance c ? c.getDefId() : sourceCard,
                target.getPlayerId(), damage, element);

        // ── 构造可修改的临时数据对象 ──
        HookData data = new HookData(source, sourceCard, target, damage, element);

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
        if (data.target == null || !data.target.isAlive()) {
            log.warn("[造成伤害事件] 目标 {} 不存在或已死亡，跳过扣血和 AFTER 钩子", data.target != null ? data.target.getPlayerId() : "null");
            return;
        }

        int actualDamage = Math.min(data.damage, data.target.getCurrentHp());
        data.target.setCurrentHp(data.target.getCurrentHp() - actualDamage);
        log.info("[造成伤害事件] 对 {} 造成 {} 点伤害 (剩余体力: {}/{})",
                data.target.getPlayerId(), actualDamage, data.target.getCurrentHp(), data.target.getMaxHp());

        // ── 前端通信（预留） ──
        // TODO: 在此处推送伤害结果到前端

        // 伤害后数据使用实际值
        data.damage = actualDamage;

        // ── 4) 造成伤害后（实际使用的数据） ──
        data.publishAndSync(GameEventType.AFTER_DAMAGE, event, match, eventBus);
    }

    /**
     * 临时数据容器 — 发布钩子后从事件中回读可能被修改的数据
     */
    private static class HookData {
        GamePlayer source;
        Object sourceCard;
        GamePlayer target;
        int damage;
        String element;
        boolean cancelled;

        HookData(GamePlayer source, Object sourceCard, GamePlayer target, int damage, String element) {
            this.source = source;
            this.sourceCard = sourceCard;
            this.target = target;
            this.damage = damage;
            this.element = element;
        }

        void publishAndSync(String hookType, GameEvent originalEvent,
                            com.lbthreecountry.game.GameMatch match, EventBus eventBus) {
            GameEvent hookEvent = GameEvent.builder()
                    .type(hookType)
                    .sourceId(originalEvent.getSourceId())
                    .targetId(target.getPlayerId())
                    .build();
            hookEvent.putData("source", source)
                    .putData("sourceCard", sourceCard)
                    .putData("target", target)
                    .putData("targetId", target.getPlayerId())
                    .putData("damage", damage)
                    .putData("element", element);
            eventBus.publish(hookEvent, match);

            if (hookEvent.isCancelled()) {
                this.cancelled = true;
                return;
            }

            // 回读监听器可能修改后的值
            GamePlayer newSource = hookEvent.getData("source");
            if (newSource != null) this.source = newSource;

            Object newSourceCard = hookEvent.getData("sourceCard");
            if (newSourceCard != null) this.sourceCard = newSourceCard;

            Integer dmg = hookEvent.getData("damage");
            if (dmg != null) this.damage = dmg;

            String newElement = hookEvent.getData("element");
            if (newElement != null) this.element = newElement;
        }
    }
}