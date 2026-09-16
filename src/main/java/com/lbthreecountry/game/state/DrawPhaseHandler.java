package com.lbthreecountry.game.state;

import com.lbthreecountry.game.GameMatch;
import com.lbthreecountry.game.GamePlayer;
import com.lbthreecountry.game.event.DrawCardEvent.DrawDriver;
import com.lbthreecountry.game.event.EventBus;
import com.lbthreecountry.game.event.EventPriority;
import com.lbthreecountry.game.event.GameEvent;
import com.lbthreecountry.game.event.GameEventType;
import com.lbthreecountry.model.enums.impl.GamePhase;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 摸牌阶段处理器 — 监听"摸牌阶段进行中"事件，触发摸牌
 *
 * <p>当 {@link PlayerTurnStateMachine} 进入 DRAW 阶段（State 4）时，
 * 会发布 {@code PHASE.ACTIVE.DRAW} 事件。本组件监听此事件后，
 * 发布 {@link GameEventType#CARD_DRAW CARD.DRAW} 触发事件，
 * 由 {@link com.lbthreecountry.game.event.DrawCardEvent DrawCardEvent}
 * 执行完整的摸牌生命周期。</p>
 *
 * <h3>执行顺序</h3>
 * <pre>
 * PlayerTurnStateMachine.enterPhase()
 *   └── publish("PHASE.ACTIVE.DRAW")
 *         │
 *         └── DrawPhaseHandler.onDrawPhase()           ← 本处理器
 *               └── publish("CARD.DRAW", driver=DRAW_PHASE, count=2)
 *                     │
 *                     └── DrawCardEvent.onDraw()       ← 摸牌事件监听器
 *                           ├── publish("CARD.DRAW.BEFORE")
 *                           ├── publish("CARD.DRAW.ACTIVE")
 *                           ├── cardManager.draw()
 *                           └── publish("CARD.DRAW.AFTER")
 * </pre>
 */
@Component
public class DrawPhaseHandler {

    private static final Logger log = LoggerFactory.getLogger(DrawPhaseHandler.class);

    /** 默认摸牌张数 */
    private static final int DEFAULT_DRAW_COUNT = 2;

    private final EventBus eventBus;

    public DrawPhaseHandler(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    @PostConstruct
    public void init() {
        eventBus.register(
                GameEventType.phaseActive(GamePhase.DRAW),
                EventPriority.ENGINE,
                this::onDrawPhase
        );
        log.info("[摸牌阶段处理器] 已注册 PHASE.ACTIVE.DRAW 监听器 (ENGINE 优先级) — 将触发 CARD.DRAW 事件");
    }

    // ================================================================
    //  事件回调
    // ================================================================

    /**
     * PHASE.ACTIVE.DRAW 事件处理 — 发布 CARD.DRAW 触发事件
     *
     * <p>从事件数据中提取当前玩家 ID，构造摸牌触发事件，
     * 由 {@link com.lbthreecountry.game.event.DrawCardEvent DrawCardEvent}
     * 监听到后执行摸牌生命周期。</p>
     */
    private void onDrawPhase(GameEvent event, GameMatch match) {
        // 从事件中获取当前回合玩家
        String playerId = event.getSourceId();
        if (playerId == null) {
            log.warn("[摸牌阶段处理器] 事件中无 sourceId，忽略");
            return;
        }

        GamePlayer player = match.findPlayer(playerId);
        if (player == null) {
            log.warn("[摸牌阶段处理器] 玩家 {} 不存在，忽略", playerId);
            return;
        }

        int round = match.getCurrentRound();
        log.info("[摸牌阶段处理器] 第 {} 轮·玩家 {} 摸牌阶段 — 触发摸牌 {} 张",
                round, playerId, DEFAULT_DRAW_COUNT);

        // ── 发布 CARD.DRAW 触发事件，由 DrawCardEvent 监听到后执行摸牌生命周期 ──
        GameEvent drawTrigger = GameEvent.builder()
                .type(GameEventType.CARD_DRAW)
                .sourceId(playerId)
                .build()
                .putData("driver", DrawDriver.DRAW_PHASE)
                .putData("playerId", playerId)
                .putData("count", DEFAULT_DRAW_COUNT);

        eventBus.publish(drawTrigger, match);

        // 从事件中读取摸牌结果（同步处理，publish 返回后结果已写入）
        boolean cancelled = drawTrigger.getDataOrDefault("cancelled", false);
        int actualCount = drawTrigger.getDataOrDefault("actualCount", 0);

        if (cancelled) {
            log.info("[摸牌阶段处理器] 第 {} 轮·玩家 {} 摸牌被取消", round, playerId);
        } else {
            log.debug("[摸牌阶段处理器] 第 {} 轮·玩家 {} 实际摸到 {} 张牌",
                    round, playerId, actualCount);
        }
    }
}