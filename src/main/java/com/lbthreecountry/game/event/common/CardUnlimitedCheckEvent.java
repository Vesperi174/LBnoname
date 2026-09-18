package com.lbthreecountry.game.event.common;

import com.lbthreecountry.game.GameMatch;
import com.lbthreecountry.game.GamePlayer;
import com.lbthreecountry.game.card.CardLibrary;
import com.lbthreecountry.game.event.EventBus;
import com.lbthreecountry.game.event.EventPriority;
import com.lbthreecountry.game.event.GameEvent;
import com.lbthreecountry.game.event.GameEventType;
import com.lbthreecountry.model.card.CardInstance;
import com.lbthreecountry.model.card.def.CardDef;
import com.lbthreecountry.model.card.def.CardRules;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 不限次数检查事件 — 监听 {@code CARD.UNLIMITED_CHECK} 触发钩子，执行卡牌不限次数检查
 *
 * <p>调用方需传入 {@code player} 和 {@code card} 两个必填对象，
 * 事件处理完成后通过 {@code unlimited} 字段获取结果。</p>
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
 * │ unlimited    │ boolean      │ 是否不限次数（默认 false）            │
 * └──────────────┴──────────────┴──────────────────────────────────────┘
 * </pre>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // ===== 检查玩家是否不限次数使用某张牌 =====
 * GameEvent event = GameEvent.builder()
 *     .type(GameEventType.CARD_UNLIMITED_CHECK)
 *     .sourceId(player.getPlayerId())
 *     .build()
 *     .putData("player", player)
 *     .putData("card", cardInstance);
 * eventBus.publish(event, match);
 *
 * boolean unlimited = event.getDataOrDefault("unlimited", false);
 * if (unlimited) {
 *     // 该玩家使用这张牌不限次数
 * }
 *
 * // ===== 技能监听：让【杀】不限次数 =====
 * eventBus.register("CARD.UNLIMITED.MODIFY", EventPriority.SKILL, (ev, m) -> {
 *     CardInstance card = ev.getData("card");
 *     if ("sha".equals(card.getDefId())) {
 *         ev.putData("unlimited", true);
 *     }
 * });
 * }</pre>
 */
@Component
public class CardUnlimitedCheckEvent {

    private static final Logger log = LoggerFactory.getLogger(CardUnlimitedCheckEvent.class);

    // ================================================================
    //  依赖
    // ================================================================

    private final EventBus eventBus;
    private final CardLibrary cardLibrary;

    public CardUnlimitedCheckEvent(EventBus eventBus, CardLibrary cardLibrary) {
        this.eventBus = eventBus;
        this.cardLibrary = cardLibrary;
    }

    @PostConstruct
    public void init() {
        eventBus.register(GameEventType.CARD_UNLIMITED_CHECK, EventPriority.ENGINE, this::onCheck);
        
    }

    // ================================================================
    //  事件回调 — 不限次数检查
    // ================================================================

    /**
     * {@code CARD.UNLIMITED_CHECK} 事件回调 — 执行不限次数检查
     *
     * <p>从事件数据中读取检查参数，执行以下流程：</p>
     * <ol>
     *   <li>读取调用方传入的 {@code player} 和 {@code card}</li>
     *   <li>通过 {@link CardLibrary} 获取卡牌定义 {@link CardDef}</li>
     *   <li>根据 {@link CardRules#getMaxUseCount()} 判断默认是否不限次数</li>
     *   <li>发布 {@code CARD.UNLIMITED.MODIFY} 修正钩子，监听器可修改 {@code unlimited}</li>
     *   <li>将最终结果写回触发事件供调用方读取</li>
     * </ol>
     */
    private void onCheck(GameEvent event, GameMatch match) {
        // ── 读取调用方传入的参数（必填） ──
        GamePlayer player = event.getData("player");
        if (player == null) {
            log.warn("[不限次数事件] 事件中无 player，忽略");
            return;
        }

        CardInstance card = event.getData("card");
        if (card == null) {
            log.warn("[不限次数事件] 事件中无 card，忽略");
            return;
        }

        // ── 通过 CardLibrary 获取卡牌定义 ──
        CardDef cardDef = cardLibrary.getDef(card.getDefId());
        if (cardDef == null) {
            log.warn("[不限次数事件] 未找到卡牌定义: {}", card.getDefId());
            event.putData("unlimited", false);
            return;
        }

        // ── 根据卡牌规则判断默认是否不限次数 ──
        //   maxUseCount == null 表示不限次数（JSON 中配置）
        boolean unlimited = isUnlimitedByRules(cardDef.getRules());

        // ── 发布修正钩子，监听器可根据 player + card 修改 unlimited ──
        GameEvent modifyEvent = GameEvent.builder()
                .type(GameEventType.CARD_UNLIMITED_MODIFY)
                .sourceId(player.getPlayerId())
                .build();
        modifyEvent.putData("player", player);
        modifyEvent.putData("card", card);
        modifyEvent.putData("unlimited", unlimited);
        eventBus.publish(modifyEvent, match);

        // ── 回读监听器可能修改后的值 ──
        unlimited = modifyEvent.getDataOrDefault("unlimited", false);

        // ── 将最终结果写回触发事件 ──
        event.putData("unlimited", unlimited);

        log.debug("[不限次数事件] 玩家 {} 使用卡牌 {} 是否不限次数: {}",
                player.getPlayerId(), card.getDefId(), unlimited);
    }

    // ================================================================
    //  内部方法
    // ================================================================

    /**
     * 根据卡牌规则判断默认是否不限次数
     *
     * @param rules 卡牌使用规则
     * @return 如果 {@code maxUseCount == null} 则视为不限次数
     */
    private boolean isUnlimitedByRules(CardRules rules) {
        if (rules == null) {
            return false;
        }
        // maxUseCount == null 表示不限次数（JSON 中 "maxUseCount": null）
        // maxUseCount 有具体数值（如 1）表示每回合有限次
        return rules.getMaxUseCount() == null;
    }
}