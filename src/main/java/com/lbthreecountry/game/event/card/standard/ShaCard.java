package com.lbthreecountry.game.event.card.standard;

import com.lbthreecountry.game.GameMatch;
import com.lbthreecountry.game.GamePlayer;
import com.lbthreecountry.game.card.CardLibrary;
import com.lbthreecountry.game.event.EventBus;
import com.lbthreecountry.game.event.EventPriority;
import com.lbthreecountry.game.event.GameEvent;
import com.lbthreecountry.game.event.GameEventType;
import com.lbthreecountry.model.card.CardInstance;
import com.lbthreecountry.model.card.def.CardDef;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 【杀】卡牌效果 — 监听 {@code CARD.USE.EFFECT} 事件钩子
 *
 * <p>收到执行牌效果事件后，判断 {@code card.defId} 是否为 {@code "sha"}，
 * 若是则执行【杀】的牌面效果。</p>
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
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // ===== 触发使用牌生命周期（由 CardUseEffectEvent 处理） =====
 * GameEvent event = GameEvent.builder()
 *     .type(GameEventType.CARD_USE)
 *     .sourceId(usePlayer.getPlayerId())
 *     .build()
 *     .putData("useplayer", usePlayer)
 *     .putData("targetplayer", targetPlayer)
 *     .putData("card", card);
 * eventBus.publish(event, match);
 * }</pre>
 */
@Component
public class ShaCard {

    private static final Logger log = LoggerFactory.getLogger(ShaCard.class);

    private final EventBus eventBus;
    private final CardLibrary cardLibrary;

    public ShaCard(EventBus eventBus, CardLibrary cardLibrary) {
        this.eventBus = eventBus;
        this.cardLibrary = cardLibrary;
    }

    @PostConstruct
    public void init() {
        eventBus.register(GameEventType.CARD_USE_EFFECT_HOOK, EventPriority.CARD, this::onUseEffect);
        
    }

    /**
     * {@code CARD.USE.EFFECT} 事件回调 — 执行【杀】的牌面效果
     *
     * <p>【杀】的效果：对目标造成 1 点无属性伤害。
     * 通过发布 {@code DAMAGE_CAUSE} 事件委托 DamageEvent 处理完整伤害生命周期。</p>
     */
    private void onUseEffect(GameEvent event, GameMatch match) {
        // ── 判断是否为本牌 ──
        CardInstance card = event.getData("card");
        if (card == null || !"sha".equals(card.getDefId())) {
            return;
        }

        GamePlayer useplayer = event.getData("useplayer");
        GamePlayer targetplayer = event.getData("targetplayer");

        if (useplayer == null || targetplayer == null) {
            log.warn("[杀] 缺少 useplayer 或 targetplayer，忽略");
            return;
        }

        log.info("[杀] {} 对 {} 使用了【杀】", useplayer.getPlayerId(), targetplayer.getPlayerId());

        // ── 从卡牌定义获取伤害值 ──
        CardDef cardDef = cardLibrary.getDef(card.getDefId());
        if (cardDef == null) {
            log.warn("[杀] 找不到卡牌定义：{}", card.getDefId());
            return;
        }
        int damage = cardDef.getDamage() != null ? cardDef.getDamage() : 0;
        if (damage <= 0) {
            log.warn("[杀] 卡牌伤害值为 {}，不造成伤害", damage);
            return;
        }

        // ── 抛出伤害事件 ──
        GameEvent damageEvent = GameEvent.builder()
                .type(GameEventType.DAMAGE_CAUSE)
                .sourceId(useplayer.getPlayerId())
                .targetId(targetplayer.getPlayerId())
                .build()
                .putData("source", useplayer)
                .putData("sourceCard", card)
                .putData("target", targetplayer)
                .putData("damage", damage);
        eventBus.publish(damageEvent, match);
    }
}