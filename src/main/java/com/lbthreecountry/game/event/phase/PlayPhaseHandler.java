package com.lbthreecountry.game.event.phase;

import com.lbthreecountry.game.GameMatch;
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
 * 出牌阶段处理器 — 监听"出牌阶段进行中"事件
 *
 * <p>当 {@link PlayerTurnStateMachine} 进入 PLAY 阶段（State 5）时，
 * 会发布 {@code PHASE.ACTIVE.PLAY} 事件。本组件监听此事件后，
 * 可执行出牌阶段相关的初始化逻辑。</p>
 *
 * <h3>执行顺序</h3>
 * <pre>
 * PlayerTurnStateMachine.enterPhase()
 *   └── publish("PHASE.ACTIVE.PLAY")
 *         │
 *         └── PlayPhaseHandler.onPlayPhase()           ← 本处理器
 *               └── (待实现)
 * </pre>
 */
@Component
public class PlayPhaseHandler {

    private static final Logger log = LoggerFactory.getLogger(PlayPhaseHandler.class);

    private final EventBus eventBus;

    public PlayPhaseHandler(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    @PostConstruct
    public void init() {
        eventBus.register(
                GameEventType.phaseActive(GamePhase.PLAY),
                EventPriority.ENGINE,
                this::onPlayPhase
        );
        log.info("[出牌阶段处理器] 已注册 PHASE.ACTIVE.PLAY 监听器 (ENGINE 优先级)");
    }

    // ================================================================
    //  事件回调
    // ================================================================

    /**
     * PHASE.ACTIVE.PLAY 事件处理
     *
     * <p>当状态机进入出牌阶段时触发，可在此执行出牌阶段初始化逻辑，
     * 如重置本回合出牌次数限制、重新检测手牌可用性等。</p>
     */
    private void onPlayPhase(GameEvent event, GameMatch match) {
        String playerId = event.getSourceId();
        if (playerId == null) {
            log.warn("[出牌阶段处理器] 事件中无 sourceId，忽略");
            return;
        }

        int round = match.getCurrentRound();
        log.info("[出牌阶段处理器] 第 {} 轮·玩家 {} 出牌阶段开始", round, playerId);

        // ── 示例：调用交互钩子，让前端弹出 ACTION_DECISION ──
        // eventBus.publish(
        //     GameEvent.builder()
        //         .type(GameEventType.INTERACTION_REQUEST)
        //         .sourceId(playerId)
        //         .build()
        //         .putData("playerId", playerId)
        //         .putData("description", "出牌阶段，请选择一张卡牌")
        //         .putData("actions", List.of(
        //             Map.of("text", "确定", "value", "confirm", "type", "default"),
        //             Map.of("text", "回合结束", "value", "end_turn", "type", "primary")
        //         ))
        //         .putData("handSelectable", true)
        //         .putData("handSelectMode", "single")
        //         .putData("targetSelectable", false)
        //     match
        // );

        // TODO: 出牌阶段初始化逻辑
        // - 重置本回合"杀"的使用次数
        // - 重新检测手牌可用性并推送前端
        // - 其他出牌阶段初始化操作
    }
}