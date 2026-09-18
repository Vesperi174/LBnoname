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
 * 满血检查事件 — 监听 {@code PLAYER.FULL_HP_CHECK} 触发钩子，判断玩家当前是否满血
 *
 * <p>调用方需传入 {@code player} 必填对象，
 * 事件处理完成后通过 {@code fullHp} 字段获取结果。</p>
 *
 * <h3>触发事件数据字段</h3>
 * <pre>
 * ┌──────────────┬──────────────┬──────────────────────────────────────┐
 * │ 字段          │ 类型          │ 说明                                 │
 * ├──────────────┼──────────────┼──────────────────────────────────────┤
 * │ player       │ GamePlayer   │ 要检查的玩家（必填）                  │
 * ├──────────────┴──────────────┴──────────────────────────────────────┤
 * │ 检查完成后，以下字段会写入事件数据：                               │
 * ├──────────────┬──────────────┬──────────────────────────────────────┤
 * │ fullHp       │ boolean      │ 是否满血（默认 false）                │
 * └──────────────┴──────────────┴──────────────────────────────────────┘
 * </pre>
 *
 * <h3>默认逻辑</h3>
 * <p>判断 {@code player.getCurrentHp() >= player.getMaxHp()}，
 * 最后抛出 {@code PLAYER.FULL_HP.MODIFY} 修正钩子供监听器修改。</p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // ===== 判断玩家是否满血 =====
 * GameEvent event = GameEvent.builder()
 *     .type(GameEventType.PLAYER_FULL_HP_CHECK)
 *     .sourceId(player.getPlayerId())
 *     .build()
 *     .putData("player", player);
 * eventBus.publish(event, match);
 *
 * boolean fullHp = event.getDataOrDefault("fullHp", false);
 * if (fullHp) {
 *     // 满血
 * }
 *
 * // ===== 技能监听：强制认为满血 =====
 * eventBus.register("PLAYER.FULL_HP.MODIFY", EventPriority.SKILL, (ev, m) -> {
 *     ev.putData("fullHp", true);
 * });
 * }</pre>
 */
@Component
public class PlayerFullHpEvent {

    private static final Logger log = LoggerFactory.getLogger(PlayerFullHpEvent.class);

    // ================================================================
    //  依赖
    // ================================================================

    private final EventBus eventBus;

    public PlayerFullHpEvent(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    @PostConstruct
    public void init() {
        eventBus.register(GameEventType.PLAYER_FULL_HP_CHECK, EventPriority.ENGINE, this::onCheck);
        log.info("[满血检查事件] 已注册 PLAYER.FULL_HP_CHECK 监听器 (ENGINE 优先级)");
    }

    // ================================================================
    //  事件回调 — 满血检查
    // ================================================================

    /**
     * {@code PLAYER.FULL_HP_CHECK} 事件回调 — 判断玩家是否满血
     *
     * <p>从事件数据中读取检查参数，执行以下流程：</p>
     * <ol>
     *   <li>读取调用方传入的 {@code player}</li>
     *   <li>判断 {@code currentHp >= maxHp}</li>
     *   <li>发布 {@code PLAYER.FULL_HP.MODIFY} 修正钩子，监听器可修改 {@code fullHp}</li>
     *   <li>将最终结果写回触发事件供调用方读取</li>
     * </ol>
     */
    private void onCheck(GameEvent event, GameMatch match) {
        // ── 读取调用方传入的参数（必填） ──
        GamePlayer player = event.getData("player");
        if (player == null) {
            log.warn("[满血检查事件] 事件中无 player，忽略");
            return;
        }

        // ── 判断是否满血 ──
        boolean fullHp = player.getCurrentHp() >= player.getMaxHp();

        // ── 发布修正钩子，监听器可根据 player 修改 fullHp ──
        GameEvent modifyEvent = GameEvent.builder()
                .type(GameEventType.PLAYER_FULL_HP_MODIFY)
                .sourceId(player.getPlayerId())
                .build();
        modifyEvent.putData("player", player);
        modifyEvent.putData("fullHp", fullHp);
        eventBus.publish(modifyEvent, match);

        // ── 回读监听器可能修改后的值 ──
        fullHp = modifyEvent.getDataOrDefault("fullHp", false);

        // ── 将最终结果写回触发事件 ──
        event.putData("fullHp", fullHp);

        log.debug("[满血检查事件] 玩家 {} 是否满血: {} (当前: {}/{})",
                player.getPlayerId(), fullHp, player.getCurrentHp(), player.getMaxHp());
    }
}