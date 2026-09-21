package com.lbthreecountry.game.event.card.standard;

import com.lbthreecountry.game.GameMatch;
import com.lbthreecountry.game.GamePlayer;
import com.lbthreecountry.game.event.EventBus;
import com.lbthreecountry.game.event.EventPriority;
import com.lbthreecountry.game.event.GameEvent;
import com.lbthreecountry.game.event.GameEventType;
import com.lbthreecountry.model.card.CardInstance;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import static com.lbthreecountry.game.event.common.RecoverHpEvent.SOURCE_BASIC_CARD;

/**
 * 【桃】卡牌效果 — 监听 {@code CARD.USE.EFFECT} 事件钩子
 *
 * <p>收到执行牌效果事件后，判断 {@code card.defId} 是否为 {@code "tao"}，
 * 若是则执行【桃】的牌面效果：使目标玩家回复 1 点体力。</p>
 *
 * <p>由于出牌阶段的桃只能在自己不满血时使用，且 {@code targetplayer} 默认为自身，
 * 所以此处回复体力的目标即使用牌的玩家自己。</p>
 *
 * <h3>触发事件数据字段</h3>
 * <pre>
 * ┌──────────────┬──────────────┬──────────────────────────────────────┐
 * │ 字段名        │ 类型          │ 说明                                 │
 * ├──────────────┼──────────────┼──────────────────────────────────────┤
 * │ useplayer    │ GamePlayer   │ 使用牌的玩家                          │
 * │ targetplayer │ GamePlayer   │ 目标玩家（桃时 = useplayer）          │
 * │ card         │ CardInstance │ 使用的卡牌实例                        │
 * └──────────────┴──────────────┴──────────────────────────────────────┘
 * </pre>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 由 PlayPhaseHandler + CardUseEffectEvent 自动触发
 * // 无需手动调用
 * }</pre>
 */
@Component
public class TaoCard {

    private static final Logger log = LoggerFactory.getLogger(TaoCard.class);

    private final EventBus eventBus;

    public TaoCard(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    @PostConstruct
    public void init() {
        eventBus.register(GameEventType.CARD_USE_EFFECT_HOOK, EventPriority.SKILL, this::onUseEffect);

    }

    /**
     * {@code CARD.USE.EFFECT} 事件回调 — 执行【桃】的牌面效果
     *
     * <p>【桃】的效果：使目标玩家回复 1 点体力。
     * 通过发布 {@code RECOVER.HP} 事件委托 RecoverHpEvent 处理完整回复生命周期。</p>
     */
    private void onUseEffect(GameEvent event, GameMatch match) {
        // ── 判断是否为本牌 ──
        CardInstance card = event.getData("card");
        if (card == null || !"tao".equals(card.getDefId())) {
            return;
        }

        GamePlayer useplayer = event.getData("useplayer");
        GamePlayer targetplayer = event.getData("targetplayer");

        if (useplayer == null || targetplayer == null) {
            log.warn("[桃] 缺少 useplayer 或 targetplayer，忽略");
            return;
        }

        log.info("[桃] {} 使用【桃】→ 目标 {} 回复体力",
                useplayer.getPlayerId(), targetplayer.getPlayerId());

        // ── 抛出回复体力事件（默认回复 1 点，Source 标记为基本牌桃） ──
        GameEvent recoverEvent = GameEvent.builder()
                .type(GameEventType.RECOVER_HP)
                .sourceId(targetplayer.getPlayerId())
                .build()
                .putData("source", new com.lbthreecountry.game.event.common.Source(SOURCE_BASIC_CARD, "tao"))
                .putData("targetId", targetplayer.getPlayerId())
                .putData("amount", 1);
        eventBus.publish(recoverEvent, match);
    }
}