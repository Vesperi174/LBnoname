package com.lbthreecountry.game.event.common;

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

/**
 * 已使用次数事件 — 监听 {@code CARD.USED_COUNT} 触发钩子，获取卡牌在本回合已使用的次数
 *
 * <p>调用方需传入 {@code player} 和 {@code card} 两个必填对象，
 * 事件处理完成后通过 {@code usedCount} 字段获取结果。</p>
 *
 * <h3>触发事件数据字段</h3>
 * <pre>
 * ┌──────────────┬──────────────┬──────────────────────────────────────┐
 * │ 字段          │ 类型          │ 说明                                 │
 * ├──────────────┼──────────────┼──────────────────────────────────────┤
 * │ player       │ GamePlayer   │ 要检查的玩家（必填）                  │
 * │ card         │ CardInstance │ 要检查的卡牌实例（必填）              │
 * ├──────────────┴──────────────┴──────────────────────────────────────┤
 * │ 检查完成后，以下字段会写入事件数据：                               │
 * ├──────────────┬──────────────┬──────────────────────────────────────┤
 * │ usedCount    │ int          │ 本回合已使用次数（默认 0）            │
 * └──────────────┴──────────────┴──────────────────────────────────────┘
 * </pre>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // ===== 获取玩家某张牌本回合已使用次数 =====
 * GameEvent event = GameEvent.builder()
 *     .type(GameEventType.CARD_USED_COUNT)
 *     .sourceId(player.getPlayerId())
 *     .build()
 *     .putData("player", player)
 *     .putData("card", cardInstance);
 * eventBus.publish(event, match);
 *
 * int usedCount = event.getDataOrDefault("usedCount", 0);
 *
 * // ===== 技能监听：让某张牌已使用次数减 1（如"无双"的效果） =====
 * eventBus.register("CARD.USED_COUNT.MODIFY", EventPriority.SKILL, (ev, m) -> {
 *     CardInstance card = ev.getData("card");
 *     if ("sha".equals(card.getDefId())) {
 *         int count = ev.getDataOrDefault("usedCount", 0);
 *         ev.putData("usedCount", Math.max(0, count - 1));
 *     }
 * });
 * }</pre>
 */
@Component
public class CardUsedCountEvent {

    private static final Logger log = LoggerFactory.getLogger(CardUsedCountEvent.class);

    // ================================================================
    //  依赖
    // ================================================================

    private final EventBus eventBus;

    public CardUsedCountEvent(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    @PostConstruct
    public void init() {
        eventBus.register(GameEventType.CARD_USED_COUNT, EventPriority.ENGINE, this::onCheck);
        
    }

    // ================================================================
    //  事件回调 — 已使用次数检查
    // ================================================================

    /**
     * {@code CARD.USED_COUNT} 事件回调 — 获取卡牌本回合已使用次数
     *
     * <p>从事件数据中读取检查参数，执行以下流程：</p>
     * <ol>
     *   <li>读取调用方传入的 {@code player} 和 {@code card}</li>
     *   <li>从 {@link CardInstance#getUsedCount()} 获取默认已使用次数</li>
     *   <li>发布 {@code CARD.USED_COUNT.MODIFY} 修正钩子，监听器可修改 {@code usedCount}</li>
     *   <li>将最终结果写回触发事件供调用方读取</li>
     * </ol>
     */
    private void onCheck(GameEvent event, GameMatch match) {
        // ── 读取调用方传入的参数（必填） ──
        GamePlayer player = event.getData("player");
        if (player == null) {
            log.warn("[已使用次数事件] 事件中无 player，忽略");
            return;
        }

        CardInstance card = event.getData("card");
        if (card == null) {
            log.warn("[已使用次数事件] 事件中无 card，忽略");
            return;
        }

        // ── 从 CardInstance 获取默认本回合已使用次数 ──
        int usedCount = card.getUsedCount();

        // ── 发布修正钩子，监听器可根据 player + card 修改 usedCount ──
        GameEvent modifyEvent = GameEvent.builder()
                .type(GameEventType.CARD_USED_COUNT_MODIFY)
                .sourceId(player.getPlayerId())
                .build();
        modifyEvent.putData("player", player);
        modifyEvent.putData("card", card);
        modifyEvent.putData("usedCount", usedCount);
        eventBus.publish(modifyEvent, match);

        // ── 回读监听器可能修改后的值 ──
        usedCount = modifyEvent.getDataOrDefault("usedCount", 0);

        // ── 将最终结果写回触发事件 ──
        event.putData("usedCount", usedCount);

        log.debug("[已使用次数事件] 玩家 {} 卡牌 {} 本回合已使用次数: {}",
                player.getPlayerId(), card.getDefId(), usedCount);
    }
}