package com.lbthreecountry.game.event.common;

import com.lbthreecountry.game.GameMatch;
import com.lbthreecountry.game.GamePlayer;
import com.lbthreecountry.game.event.EventBus;
import com.lbthreecountry.game.event.EventPriority;
import com.lbthreecountry.game.event.GameEvent;
import com.lbthreecountry.game.event.GameEventType;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 手牌上限事件 — 监听 {@code HAND_LIMIT.CALC} 触发钩子，执行手牌上限计算
 *
 * <h3>手牌上限计算流程</h3>
 * <pre>
 * HAND_LIMIT.CALC（触发入口）
 *   │  1. 取玩家当前体力值作为基础手牌上限
 *   │  2. 检查 handCardLimit 字段是否有自定义值
 *   └── HAND_LIMIT.MODIFY（修正钩子，技能可修改 limit）
 *         └─ 调用方读取最终 limit
 * </pre>
 *
 * <h3>触发事件数据字段</h3>
 * <pre>
 * ┌──────────────┬──────────┬────────────────────────────────────────┐
 * │ 字段          │ 类型      │ 说明                                   │
 * ├──────────────┼──────────┼────────────────────────────────────────┤
 * │ playerId     │ String   │ 玩家 ID（必填）                        │
 * ├──────────────┼──────────┼────────────────────────────────────────┤
 * │ 处理后写入：   │          │                                        │
 * ├──────────────┼──────────┼────────────────────────────────────────┤
 * │ baseLimit    │ int      │ 基础手牌上限（默认 = 当前体力值）        │
 * │ limit        │ int      │ 最终手牌上限（监听器可在 MODIFY 中修改） │
 * └──────────────┴──────────┴────────────────────────────────────────┘
 * </pre>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // ===== 获取玩家手牌上限 =====
 * GameEvent event = GameEvent.builder()
 *     .type(GameEventType.HAND_LIMIT_CALC)
 *     .sourceId(playerId)
 *     .build()
 *     .putData("playerId", playerId);
 * eventBus.publish(event, match);
 * int limit = event.getDataOrDefault("limit", 0);
 *
 * // ===== 技能监听：修改手牌上限（如克己） =====
 * eventBus.register("HAND_LIMIT.MODIFY", EventPriority.SKILL, (ev, m) -> {
 *     int base = ev.getDataOrDefault("baseLimit", 0);
 *     ev.putData("limit", base + 2); // 手牌上限 +2
 * });
 *
 * // ===== 技能监听：减少手牌上限（如某负面技能） =====
 * eventBus.register("HAND_LIMIT.MODIFY", EventPriority.SKILL, (ev, m) -> {
 *     int current = ev.getDataOrDefault("limit", 0);
 *     ev.putData("limit", Math.max(0, current - 1)); // 手牌上限 -1
 * });
 * }</pre>
 */
@Component
public class HandLimitEvent {

    private static final Logger log = LoggerFactory.getLogger(HandLimitEvent.class);

    private final EventBus eventBus;

    public HandLimitEvent(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    @PostConstruct
    public void init() {
        eventBus.register(GameEventType.HAND_LIMIT_CALC, EventPriority.ENGINE, this::onHandLimitCalc);
    }

    /**
     * {@code HAND_LIMIT.CALC} 事件回调 — 执行手牌上限计算
     *
     * <p>读取玩家当前体力值作为基础手牌上限，然后抛出修正钩子
     * {@code HAND_LIMIT.MODIFY}。监听器可在钩子中修改 {@code limit}，
     * 调用方直接读取事件数据中的最终值即可。</p>
     */
    private void onHandLimitCalc(GameEvent event, GameMatch match) {
        // ── 读取调用方传入的玩家 ID ──
        String playerId = event.getData("playerId");
        if (playerId == null) {
            log.warn("[手牌上限事件] 事件中无 playerId，忽略");
            return;
        }

        // ── 查找玩家 ──
        GamePlayer player = match.findPlayer(playerId);
        if (player == null) {
            log.warn("[手牌上限事件] 玩家 {} 不存在，忽略", playerId);
            return;
        }

        // ── 计算基础手牌上限 ──
        // 默认 = 当前体力值（currentHp），如果玩家有自定义 handCardLimit 则优先使用
        int baseLimit = player.effectiveHandCardLimit();
        int limit = baseLimit;

        event.putData("baseLimit", baseLimit);
        event.putData("limit", limit);

        // ── 抛出修正钩子，监听器可修改 limit ──
        GameEvent modifyEvent = GameEvent.builder()
                .type(GameEventType.HAND_LIMIT_MODIFY)
                .sourceId(playerId)
                .build();
        modifyEvent.putData("playerId", playerId);
        modifyEvent.putData("baseLimit", baseLimit);
        modifyEvent.putData("limit", limit);

        eventBus.publish(modifyEvent, match);

        // ── 读取修正后的最终手牌上限，最低为 0 ──
        limit = Math.max(0, (int) modifyEvent.getDataOrDefault("limit", baseLimit));
        event.putData("limit", limit);

        log.debug("[手牌上限事件] 玩家 {} 手牌上限: base={}, final={}", playerId, baseLimit, limit);
    }
}