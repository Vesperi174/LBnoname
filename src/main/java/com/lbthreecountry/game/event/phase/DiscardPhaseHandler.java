package com.lbthreecountry.game.event.phase;

import com.lbthreecountry.game.GameMatch;
import com.lbthreecountry.game.GamePlayer;
import com.lbthreecountry.game.event.EventBus;
import com.lbthreecountry.game.event.EventPriority;
import com.lbthreecountry.game.event.GameEvent;
import com.lbthreecountry.game.event.GameEventType;
import com.lbthreecountry.game.state.PlayerTurnStateMachine;
import com.lbthreecountry.model.enums.impl.GamePhase;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 弃牌阶段处理器 — 监听"弃牌阶段进行中"事件，触发弃牌流程
 *
 * <p>当 {@link PlayerTurnStateMachine} 进入 DISCARD 阶段（State 6）时，
 * 会发布 {@code PHASE.ACTIVE.DISCARD} 事件。本组件监听此事件后，
 * 计算应弃牌数，然后发布 {@code CARD.DISCARD} 触发事件，
 * 由 {@link com.lbthreecountry.game.event.common.DiscardEvent DiscardEvent}
 * 执行完整的弃牌生命周期（选牌交互、技能钩子、实际弃牌）。</p>
 *
 * <h3>执行顺序</h3>
 * <pre>
 * PlayerTurnStateMachine.enterPhase()
 *   └── publish("PHASE.ACTIVE.DISCARD")
 *         │
 *         └── DiscardPhaseHandler.onDiscardPhase()     ← 本处理器
 *               ├── ① 计算手牌上限（HAND_LIMIT.CALC）
 *               ├── ② 计算应弃牌数 = 手牌数 - 手牌上限
 *               └── ③ 发布 CARD.DISCARD 触发事件
 *                     └── DiscardEvent.onDiscard()     ← 弃牌事件监听器
 *                           ├── 前置钩子（技能可修改 count / 取消）
 *                           ├── 选牌交互（推送 HAND_STATUS + ACTION_DECISION）
 *                           ├── 实际弃牌操作
 *                           └── 后置钩子（仅通知）
 * </pre>
 */
@Component
public class DiscardPhaseHandler {

    private static final Logger log = LoggerFactory.getLogger(DiscardPhaseHandler.class);

    private final EventBus eventBus;

    public DiscardPhaseHandler(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    @PostConstruct
    public void init() {
        eventBus.register(
                GameEventType.phaseActive(GamePhase.DISCARD),
                EventPriority.ENGINE,
                this::onDiscardPhase
        );
    }

    // ================================================================
    //  事件回调
    // ================================================================

    /**
     * PHASE.ACTIVE.DISCARD 事件处理 — 计算应弃牌数并触发弃牌事件
     *
     * <p>仅负责计算和触发，不包含具体交互逻辑。
     * 实际弃牌的选牌交互生命周期由 {@link com.lbthreecountry.game.event.common.DiscardEvent DiscardEvent} 处理。</p>
     */
    private void onDiscardPhase(GameEvent event, GameMatch match) {
        // ── 1. 获取当前回合玩家 ──
        String playerId = event.getSourceId();
        if (playerId == null) {
            log.warn("[弃牌阶段处理器] 事件中无 sourceId，忽略");
            return;
        }

        GamePlayer player = match.findPlayer(playerId);
        if (player == null) {
            log.warn("[弃牌阶段处理器] 玩家 {} 不存在，忽略", playerId);
            return;
        }

        int round = match.getCurrentRound();
        int handCount = player.getHandCards().size();

        // ── 2. Bot 玩家跳过 ──
        if (player.isBot()) {
            log.info("[弃牌阶段处理器] 🤖 第 {} 轮·Bot 玩家 {} 手牌 {} 张，跳过弃牌阶段",
                    round, playerId, handCount);
            return;
        }

        // ── 3. 计算手牌上限 ──
        GameEvent limitEvent = GameEvent.builder()
                .type(GameEventType.HAND_LIMIT_CALC)
                .sourceId(playerId)
                .build()
                .putData("playerId", playerId);
        eventBus.publish(limitEvent, match);
        int handLimit = limitEvent.getDataOrDefault("limit", player.getCurrentHp());

        // ── 4. 计算应弃牌数 ──
        int discardCount = handCount - handLimit;

        log.info("[弃牌阶段处理器] 第 {} 轮·玩家 {} 弃牌阶段 — 手牌 {} 张，手牌上限 {}，应弃 {} 张",
                round, playerId, handCount, handLimit, discardCount);

        if (discardCount <= 0) {
            log.info("[弃牌阶段处理器] 玩家 {} 无需弃牌，跳过", playerId);
            return;
        }

        // ── 5. 发布 CARD.DISCARD 触发事件，由 DiscardEvent 接管后续流程 ──
        GameEvent discardEvent = GameEvent.builder()
                .type(GameEventType.CARD_DISCARD)
                .sourceId(playerId)
                .build()
                .putData("playerId", playerId)
                .putData("player", player)
                .putData("count", discardCount);
        eventBus.publish(discardEvent, match);

        log.info("[弃牌阶段处理器] 已发布 CARD.DISCARD 事件，玩家 {} 需弃 {} 张牌",
                playerId, discardCount);
    }
}