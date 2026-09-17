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

import java.util.List;

/**
 * 【杀】卡牌效果 — 监听 {@code CARD.USE_EFFECT} 事件钩子
 *
 * <p>收到执行牌效果事件后，判断 {@code card.defId} 是否为 {@code "sha"}，
 * 若是则执行【杀】的牌面效果。</p>
 *
 * <h3>触发事件数据字段</h3>
 * <pre>
 * ┌──────────────┬────────────────────────────────────┬──────────────────────────────┐
 * │ 字段名        │ 类型                                │ 说明                           │
 * ├──────────────┼────────────────────────────────────┼──────────────────────────────┤
 * │ player       │ GamePlayer                         │ 效果执行者                      │
 * │ card         │ CardInstance                       │ 执行效果的卡牌                   │
 * │ targets      │ GamePlayer / List&lt;GamePlayer&gt; / null │ 效果目标（可为 null / 单个 / 集合）│
 * └──────────────┴────────────────────────────────────┴──────────────────────────────┘
 * </pre>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 对单个目标使用【杀】
 * eventBus.publish(GameEvent.builder()
 *     .type(GameEventType.CARD_USE_EFFECT)
 *     .sourceId(player.getPlayerId())
 *     .build()
 *     .putData("player", player)
 *     .putData("card", card)
 *     .putData("targets", targetPlayer), match);
 *
 * // 使用【杀】（无目标）
 * eventBus.publish(GameEvent.builder()
 *     .type(GameEventType.CARD_USE_EFFECT)
 *     .sourceId(player.getPlayerId())
 *     .build()
 *     .putData("player", player)
 *     .putData("card", card), match);
 * }</pre>
 */
@Component
public class ShaCard {

    private static final Logger log = LoggerFactory.getLogger(ShaCard.class);

    private final EventBus eventBus;

    public ShaCard(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    @PostConstruct
    public void init() {
        eventBus.register(GameEventType.CARD_USE_EFFECT, EventPriority.SKILL, this::onUseEffect);
        log.info("[杀] 已注册 CARD.USE_EFFECT 监听器 (SKILL 优先级)");
    }

    /**
     * {@code CARD.USE_EFFECT} 事件回调 — 执行【杀】的牌面效果
     */
    private void onUseEffect(GameEvent event, GameMatch match) {
        // ── 判断是否为本牌 ──
        CardInstance card = event.getData("card");
        if (card == null || !"sha".equals(card.getDefId())) {
            return;
        }

        GamePlayer player = event.getData("player");
        Object targets = event.getData("targets");

        log.info("[杀] {} 对 {} 使用了【杀】",
                player != null ? player.getPlayerId() : "未知",
                targets);
    }
}