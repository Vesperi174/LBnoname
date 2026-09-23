package com.lbthreecountry.game.event.card.standard;

import com.lbthreecountry.game.GameMatch;
import com.lbthreecountry.game.GamePlayer;
import com.lbthreecountry.game.event.EventBus;
import com.lbthreecountry.game.event.EventPriority;
import com.lbthreecountry.game.event.GameEvent;
import com.lbthreecountry.game.event.GameEventType;
import com.lbthreecountry.game.event.common.DrawCardEvent.DrawDriver;
import com.lbthreecountry.model.card.CardInstance;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 【无中生有】卡牌效果 — 监听 {@code CARD.USE.EFFECT} 事件钩子
 *
 * <p>收到执行牌效果事件后，判断 {@code card.defId} 是否为 {@code "wuzhong"}，
 * 若是则执行【无中生有】的牌面效果：使使用牌的玩家摸 2 张牌。</p>
 *
 * <p>无中生有不需要选择目标（{@code targetCount = 0}），效果仅作用于使用牌的玩家自身。</p>
 *
 * <h3>触发事件数据字段</h3>
 * <pre>
 * ┌──────────────┬──────────────┬──────────────────────────────────────┐
 * │ 字段名        │ 类型          │ 说明                                 │
 * ├──────────────┼──────────────┼──────────────────────────────────────┤
 * │ useplayer    │ GamePlayer   │ 使用牌的玩家                          │
 * │ targetplayer │ GamePlayer   │ 目标玩家（无中生有时 = useplayer）    │
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
public class WuZhongCard {

    private static final Logger log = LoggerFactory.getLogger(WuZhongCard.class);

    private final EventBus eventBus;

    public WuZhongCard(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    @PostConstruct
    public void init() {
        eventBus.register(GameEventType.CARD_USE_EFFECT_HOOK, EventPriority.CARD, this::onUseEffect);
    }

    /**
     * {@code CARD.USE.EFFECT} 事件回调 — 执行【无中生有】的牌面效果
     *
     * <p>【无中生有】的效果：使使用牌的玩家摸 2 张牌。
     * 流程：</p>
     * <ol>
     *   <li>检测卡牌 defId 是否为 {@code "wuzhong"}</li>
     *   <li>发布 {@link GameEventType#CARD_STRATEGY_EFFECT_BEFORE 锦囊牌即将生效} 钩子
     *       — 供【无懈可击】等响应牌拦截</li>
     *   <li>若未被取消，发布 {@code CARD.DRAW} 事件委托 DrawCardEvent 处理摸牌生命周期</li>
     * </ol>
     */
    private void onUseEffect(GameEvent event, GameMatch match) {
        // ── 判断是否为本牌 ──
        CardInstance card = event.getData("card");
        if (card == null || !"wuzhong".equals(card.getDefId())) {
            return;
        }

        GamePlayer useplayer = event.getData("useplayer");

        if (useplayer == null) {
            log.warn("[无中生有] 缺少 useplayer，忽略");
            return;
        }

        log.info("[无中生有] {} 使用【无中生有】，发布锦囊牌即将生效钩子",
                useplayer.getPlayerId());

        // ── 1) 发布锦囊牌即将生效钩子（供无懈可击等响应牌拦截） ──
        GamePlayer targetplayer = event.getData("targetplayer");
        GameEvent strategyHook = GameEvent.builder()
                .type(GameEventType.CARD_STRATEGY_EFFECT_BEFORE)
                .sourceId(useplayer.getPlayerId())
                .build()
                .putData("useplayer", useplayer)
                .putData("targetplayer", targetplayer)
                .putData("card", card);
        eventBus.publish(strategyHook, match);

        // ── 2) 检查是否被无懈可击等取消 ──
        if (strategyHook.isCancelled()) {
            log.info("[无中生有] 锦囊牌效果已被取消 — {} 的【无中生有】被无懈可击",
                    useplayer.getPlayerId());
            return;
        }

        log.info("[无中生有] {} 使用【无中生有】→ 摸 2 张牌",
                useplayer.getPlayerId());

        // ── 3) 抛出摸牌事件（摸 2 张，驱动来源标记为卡牌效果） ──
        GameEvent drawEvent = GameEvent.builder()
                .type(GameEventType.CARD_DRAW)
                .sourceId(useplayer.getPlayerId())
                .build()
                .putData("driver", DrawDriver.CARD_EFFECT)
                .putData("player", useplayer)
                .putData("count", 2);
        eventBus.publish(drawEvent, match);
    }
}