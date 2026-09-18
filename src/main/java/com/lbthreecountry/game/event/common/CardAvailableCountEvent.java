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
 * 可使用次数事件 — 监听 {@code CARD.AVAILABLE_COUNT} 触发钩子，获取卡牌每回合最大使用次数
 *
 * <p>调用方需传入 {@code player} 和 {@code card} 两个必填对象，
 * 事件处理完成后通过 {@code availableCount} 字段获取结果。</p>
 *
 * <p><b>使用前提：</b>通常先调用 {@code CARD.UNLIMITED_CHECK} 判断是否不限次数，
 * 如果不限次数则无需调用此事件；如果有限次，再调用此事件获取具体的限制次数。</p>
 *
 * <h3>触发事件数据字段</h3>
 * <pre>
 * ┌─────────────────┬──────────────┬──────────────────────────────────┐
 * │ 字段             │ 类型          │ 说明                             │
 * ├─────────────────┼──────────────┼──────────────────────────────────┤
 * │ player          │ GamePlayer   │ 要检查的玩家（必填）              │
 * │ card            │ CardInstance │ 要检查的卡牌实例（必填）          │
 * ├─────────────────┴──────────────┴──────────────────────────────────┤
 * │ 检查完成后，以下字段会写入事件数据：                               │
 * ├─────────────────┬──────────────┬──────────────────────────────────┤
 * │ availableCount  │ int          │ 每回合最大使用次数（默认 0）      │
 * └─────────────────┴──────────────┴──────────────────────────────────┘
 * </pre>
 *
 * <h3>默认逻辑</h3>
 * <p>从 {@link CardRules#getMaxUseCount()} 获取配置值作为每回合可使用次数，
 * 最后抛出 {@code CARD.AVAILABLE_COUNT.MODIFY} 修正钩子供监听器修改。</p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // ===== 先判断是否不限次数，再获取限制次数 =====
 * GameEvent checkEvent = GameEvent.builder()
 *     .type(GameEventType.CARD_UNLIMITED_CHECK)
 *     .sourceId(player.getPlayerId())
 *     .build()
 *     .putData("player", player)
 *     .putData("card", cardInstance);
 * eventBus.publish(checkEvent, match);
 *
 * boolean unlimited = checkEvent.getDataOrDefault("unlimited", false);
 * if (!unlimited) {
 *     // 有限次，获取具体限制次数
 *     GameEvent countEvent = GameEvent.builder()
 *         .type(GameEventType.CARD_AVAILABLE_COUNT)
 *         .sourceId(player.getPlayerId())
 *         .build()
 *         .putData("player", player)
 *         .putData("card", cardInstance);
 *     eventBus.publish(countEvent, match);
 *
 *     int maxCount = countEvent.getDataOrDefault("availableCount", 0);
 * }
 *
 * // ===== 技能监听：修改【杀】的限制次数为 2 =====
 * eventBus.register("CARD.AVAILABLE_COUNT.MODIFY", EventPriority.SKILL, (ev, m) -> {
 *     CardInstance card = ev.getData("card");
 *     if ("sha".equals(card.getDefId())) {
 *         ev.putData("availableCount", 2);
 *     }
 * });
 * }</pre>
 */
@Component
public class CardAvailableCountEvent {

    private static final Logger log = LoggerFactory.getLogger(CardAvailableCountEvent.class);

    // ================================================================
    //  依赖
    // ================================================================

    private final EventBus eventBus;
    private final CardLibrary cardLibrary;

    public CardAvailableCountEvent(EventBus eventBus, CardLibrary cardLibrary) {
        this.eventBus = eventBus;
        this.cardLibrary = cardLibrary;
    }

    @PostConstruct
    public void init() {
        eventBus.register(GameEventType.CARD_AVAILABLE_COUNT, EventPriority.ENGINE, this::onCheck);
        
    }

    // ================================================================
    //  事件回调 — 获取每回合最大使用次数
    // ================================================================

    /**
     * {@code CARD.AVAILABLE_COUNT} 事件回调 — 获取卡牌每回合最大使用次数
     *
     * <p>从事件数据中读取检查参数，执行以下流程：</p>
     * <ol>
     *   <li>读取调用方传入的 {@code player} 和 {@code card}</li>
     *   <li>通过 {@link CardLibrary} 获取卡牌定义 {@link CardDef}</li>
     *   <li>从 {@link CardRules#getMaxUseCount()} 获取每回合最大使用次数</li>
     *   <li>发布 {@code CARD.AVAILABLE_COUNT.MODIFY} 修正钩子，监听器可修改
     *       {@code availableCount}</li>
     *   <li>将最终结果写回触发事件供调用方读取</li>
     * </ol>
     */
    private void onCheck(GameEvent event, GameMatch match) {
        // ── 读取调用方传入的参数（必填） ──
        GamePlayer player = event.getData("player");
        if (player == null) {
            log.warn("[可使用次数事件] 事件中无 player，忽略");
            return;
        }

        CardInstance card = event.getData("card");
        if (card == null) {
            log.warn("[可使用次数事件] 事件中无 card，忽略");
            return;
        }

        // ── 通过 CardLibrary 获取卡牌定义 ──
        CardDef cardDef = cardLibrary.getDef(card.getDefId());
        if (cardDef == null) {
            log.warn("[可使用次数事件] 未找到卡牌定义: {}", card.getDefId());
            event.putData("availableCount", 0);
            return;
        }

        // ── 从 CardRules 获取每回合最大使用次数 ──
        int availableCount = getMaxUseCount(cardDef.getRules());

        // ── 发布修正钩子，监听器可根据 player + card 修改 availableCount ──
        GameEvent modifyEvent = GameEvent.builder()
                .type(GameEventType.CARD_AVAILABLE_COUNT_MODIFY)
                .sourceId(player.getPlayerId())
                .build();
        modifyEvent.putData("player", player);
        modifyEvent.putData("card", card);
        modifyEvent.putData("availableCount", availableCount);
        eventBus.publish(modifyEvent, match);

        // ── 回读监听器可能修改后的值 ──
        availableCount = modifyEvent.getDataOrDefault("availableCount", 0);

        // ── 将最终结果写回触发事件 ──
        event.putData("availableCount", availableCount);

        log.debug("[可使用次数事件] 玩家 {} 卡牌 {} 每回合最大使用次数: {}",
                player.getPlayerId(), card.getDefId(), availableCount);
    }

    // ================================================================
    //  内部方法
    // ================================================================

    /**
     * 获取每回合最大使用次数
     *
     * <p>注意：此事件默认只应在有限次卡牌上调用，因此直接返回
     * {@code maxUseCount} 的值。如果 {@code maxUseCount == null}
     * （不限次数）或 {@code rules == null}，返回 {@code 0} 作为兜底。</p>
     *
     * @param rules 卡牌使用规则
     * @return 每回合最大使用次数（不为 null 时返回配置值，否则 0）
     */
    private int getMaxUseCount(CardRules rules) {
        if (rules == null) {
            return 0;
        }
        Integer maxUseCount = rules.getMaxUseCount();
        // maxUseCount != null 表示有限次，返回配置的限制次数
        // maxUseCount == null 表示不限次数，返回 0 兜底
        return maxUseCount != null ? maxUseCount : 0;
    }
}