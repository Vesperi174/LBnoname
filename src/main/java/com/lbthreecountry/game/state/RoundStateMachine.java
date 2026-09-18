package com.lbthreecountry.game.state;

import com.lbthreecountry.game.GameMatch;
import com.lbthreecountry.game.event.EventBus;
import com.lbthreecountry.game.event.EventPriority;
import com.lbthreecountry.game.event.GameEvent;
import com.lbthreecountry.game.event.GameEventType;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 游戏轮次状态机 — 管理每轮游戏的完整生命周期（自然驱动）
 *
 * <h3>状态流转</h3>
 * <pre>
 * ┌──────────────────────────────────────────────────────────┐
 * │  监听 BATTLE_START 事件触发                               │
 * │                                                          │
 * │   ┌──────────────────┐                                    │
 * │   │ ROUND_START      │ ── ① ROUND.ROLL（RoundController   │
 * │   │ (第 x 轮开始)     │     完成 round++）                   │
 * │   └───────┬──────────┘ ── ② ROUND.START（对外发布钩子事件）  │
 * │           │              ③ 直接调用 → PLAYER_TURN          │
 * │           ▼                                                │
 * │   ┌──────────────────┐                                    │
 * │   │ PLAYER_TURN      │ ── ROUND.TURN_START（启动回合状态机） │
 * │   │ (玩家回合状态机)   │     等待外部 nextTurn() 驱动调度     │
 * │   └───────┬──────────┘     nextIndex==0 → onRoundComplete  │
 * │           │                                                │
 * │           ▼                                                │
 * │   ┌──────────────────┐                                    │
 * │   │ ROUND_END        │ ── ① ROUND.END（对外发布钩子事件）    │
 * │   │ (第 x 轮结束)     │ ── ② 直接调用 → ROUND_START（下一轮）│
 * │   └──────────────────┘                                    │
 * └──────────────────────────────────────────────────────────┘
 *
 * <p><b>设计原则：</b>与 {@link PlayerTurnStateMachine} 一致，状态机
 * <b>不监听自己发布的事件</b>。每个状态处理完自身逻辑后，直接调用
 * 下一个状态的入口方法（自然驱动），而非靠发布事件来触发自身监听器。</p>
 *
 * <h3>轮次记录</h3>
 * <p>轮次数值由 {@link RoundController} 管理，状态机内不维护 round 字段，
 * 如需当前轮次请读取 {@link GameMatch#getCurrentRound()}。</p>
 *
 * <h3>集成说明</h3>
 * <ul>
 *   <li>由 {@link #onBattleStart(GameEvent, GameMatch)} 监听 BATTLE_START 启动第 1 轮</li>
 *   <li>由 {@link #onRoundComplete(GameMatch)} 被 {@code GameServiceImpl.nextTurn()} 调用</li>
 *   <li>轮次切换始终在状态机方法调用链中同步完成，不依赖事件回调</li>
 * </ul>
 *
 * <h3>终止条件</h3>
 * <p>在 {@link RoundPhase#PLAYER_TURN} 状态下将会引入终止条件检测
 * （如某方阵营达成胜利条件），当前暂未实现。</p>
 */
@Component
public class RoundStateMachine {

    private static final Logger log = LoggerFactory.getLogger(RoundStateMachine.class);

    private final EventBus eventBus;

    /** roomId → 轮次状态 */
    private final Map<String, RoundState> states = new ConcurrentHashMap<>();

    public RoundStateMachine(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    // ================================================================
    //  状态定义
    // ================================================================

    /**
     * 轮次阶段枚举
     */
    public enum RoundPhase {
        /** 初始状态，等待 BATTLE_START 事件触发 */
        IDLE,
        /** 第 x 轮开始时 — 发布 ROUND.START 钩子事件 */
        ROUND_START,
        /** 玩家回合状态机 — 内部子状态机（暂不实现），线性执行所有玩家回合 */
        PLAYER_TURN,
        /** 第 x 轮结束时 — 发布 ROUND.END 钩子事件 */
        ROUND_END
    }

    /**
     * 单个房间的轮次状态
     */
    @Data
    private static class RoundState {
        /** 当前轮次阶段 */
        private RoundPhase phase = RoundPhase.IDLE;
    }

    // ================================================================
    //  初始化 & 销毁
    // ================================================================

    @PostConstruct
    public void init() {
        // 只监听外部事件 BATTLE_START，不监听自己发布的事件
        eventBus.register(GameEventType.BATTLE_START, EventPriority.ENGINE, this::onBattleStart);
        
    }

    @PreDestroy
    public void destroy() {
        states.clear();
    }

    // ================================================================
    //  事件回调 — 仅监听外部事件
    // ================================================================

    /**
     * BATTLE_START 事件处理 — 启动第 1 轮
     *
     * <p>轮次初始化由 {@link RoundController} 在 ROUND.ROLL 事件中处理，
     * 此处仅创建状态并进入 {@link RoundPhase#ROUND_START}。</p>
     */
    private void onBattleStart(GameEvent event, GameMatch match) {
        String roomId = match.getRoomId();
        if (roomId == null) return;

        RoundState state = getOrCreateState(roomId);

        log.info("[轮次状态机]  BATTLE_START → 进入第 1 轮·开始阶段");
        enterRoundStart(match, state);
    }

    // ================================================================
    //  状态转移 — 自然驱动（每个方法执行完毕后直接调用下一状态）
    // ================================================================

    /**
     * 进入"第 x 轮开始时"状态
     *
     * <p>内部顺序：</p>
     * <ol>
     *   <li>发布 {@link GameEventType#ROUND_ROLL} 事件 → {@link RoundController} 完成 round++</li>
     *   <li>发布 {@link GameEventType#ROUND_START} 钩子事件（对外通知轮次开始）</li>
     *   <li>直接调用 {@link #enterPlayerTurn} 进入下一状态（自然驱动）</li>
     * </ol>
     */
    private void enterRoundStart(GameMatch match, RoundState state) {
        state.setPhase(RoundPhase.ROUND_START);

        int round = match.getCurrentRound();
        log.info("[轮次状态机]  第 {} 轮前·推进阶段 — 发布 ROUND.ROLL 事件", round + 1);

        // ── ① 发布 ROUND.ROLL（同步阻塞）→ RoundController round++ ──
        GameEvent rollEvent = GameEvent.builder()
                .type(GameEventType.ROUND_ROLL)
                .sourceId("system")
                .build();
        rollEvent.putData("roomId", match.getRoomId());
        rollEvent.putData("round", round);
        eventBus.publish(rollEvent, match);

        int newRound = match.getCurrentRound();
        log.info("[轮次状态机]  第 {} 轮·开始阶段 — 发布 ROUND.START 事件", newRound);

        // ── ② 发布 ROUND.START（同步阻塞，对外发通知，不依赖自身监听器） ──
        GameEvent roundStartEvent = GameEvent.builder()
                .type(GameEventType.ROUND_START)
                .sourceId("system")
                .build();
        roundStartEvent.putData("roomId", match.getRoomId());
        roundStartEvent.putData("round", newRound);
        roundStartEvent.putData("totalTurns", match.getTotalTurns());
        eventBus.publish(roundStartEvent, match);

        // ── ③ 自然驱动：直接进入下一状态，不靠事件回调 ──
        enterPlayerTurn(match, state);
    }

    /**
     * 进入"玩家回合状态机"状态
     *
     * <p>发布 {@link GameEventType#ROUND_TURN_START} 钩子事件，
     * {@link PlayerTurnStateMachine} 监听此事件后启动玩家回合序列。</p>
     *
     * <p>状态停留在此处，由外部 {@code GameServiceImpl.nextTurn()} 驱动每个玩家的回合，
     * 直到所有存活玩家都完成一轮后，通过 {@link #onRoundComplete(GameMatch)} 继续流转。</p>
     */
    private void enterPlayerTurn(GameMatch match, RoundState state) {
        state.setPhase(RoundPhase.PLAYER_TURN);
        int round = match.getCurrentRound();

        log.info("[轮次状态机] 🎮 第 {} 轮·玩家回合阶段 — 发布 ROUND.TURN_START 事件", round);

        // ── 发布 ROUND.TURN_START 事件（用于开启回合状态机） ──
        GameEvent turnStartEvent = GameEvent.builder()
                .type(GameEventType.ROUND_TURN_START)
                .sourceId("system")
                .build();
        turnStartEvent.putData("roomId", match.getRoomId());
        turnStartEvent.putData("round", round);
        turnStartEvent.putData("totalTurns", match.getTotalTurns());
        eventBus.publish(turnStartEvent, match);

        // ── 所有钩子走完，等待外部驱动调度玩家回合 ──
        log.info("[轮次状态机] 🎮 第 {} 轮·玩家回合阶段 — 等待玩家回合驱动", round);
    }

    /**
     * 进入"第 x 轮结束时"状态
     *
     * <p>内部顺序：</p>
     * <ol>
     *   <li>发布 {@link GameEventType#ROUND_END} 钩子事件（对外通知轮次结束）</li>
     *   <li>直接调用 {@link #enterRoundStart} 进入下一轮（自然驱动）</li>
     * </ol>
     */
    private void enterRoundEnd(GameMatch match, RoundState state) {
        state.setPhase(RoundPhase.ROUND_END);
        int round = match.getCurrentRound();

        log.info("[轮次状态机] 🔚 第 {} 轮·结束阶段 — 发布 ROUND.END 事件", round);

        // ── ① 发布 ROUND.END 事件（同步阻塞，对外发通知） ──
        GameEvent roundEndEvent = GameEvent.builder()
                .type(GameEventType.ROUND_END)
                .sourceId("system")
                .build();
        roundEndEvent.putData("roomId", match.getRoomId());
        roundEndEvent.putData("round", round);
        roundEndEvent.putData("totalTurns", match.getTotalTurns());
        eventBus.publish(roundEndEvent, match);

        // ── ② 自然驱动：直接进入下一轮的 ROUND_START ──
        //     下一轮的 round++ 由 enterRoundStart → ROUND.ROLL 完成
        enterRoundStart(match, state);
    }

    // ================================================================
    //  外部回调 — 由 GameServiceImpl 调用
    // ================================================================

    /**
     * 轮次完成回调 — 由 {@code GameServiceImpl.nextTurn()} 在检测到
     * {@code nextIndex == 0}（所有存活玩家均完成一轮）时调用。
     *
     * <p>此方法负责：</p>
     * <ol>
     *   <li>检查状态机是否处于 {@link RoundPhase#PLAYER_TURN} 状态</li>
     *   <li>进入 {@link RoundPhase#ROUND_END} → 发布 ROUND.END（对外通知）</li>
     *   <li>自然驱动进入下一轮 {@link RoundPhase#ROUND_START} → ROUND.ROLL → ROUND.START → PLAYER_TURN</li>
     * </ol>
     *
     * <p>整个切换在调用链中同步完成（自然驱动，不依赖事件回调）。</p>
     *
     * <p><b>注意：</b>调用此方法返回后，新轮次已进入 PLAYER_TURN 阶段并发布了
     * ROUND.TURN_START 事件（触发了 {@link PlayerTurnStateMachine}）。
     * 调用方无需再手动初始化新轮次的首位玩家回合。</p>
     *
     * @param match 当前对局
     * @return true — 轮次切换成功；false — 状态不匹配，切换被忽略
     */
    public boolean onRoundComplete(GameMatch match) {
        String roomId = match.getRoomId();
        if (roomId == null) return false;

        RoundState state = states.get(roomId);
        if (state == null) {
            log.warn("[轮次状态机] 房间 {} 无状态记录，忽略 onRoundComplete", roomId);
            return false;
        }

        if (state.getPhase() != RoundPhase.PLAYER_TURN) {
            log.warn("[轮次状态机] 房间 {} 当前状态为 {}，不在 PLAYER_TURN 阶段，忽略轮次切换",
                    roomId, state.getPhase());
            return false;
        }

        log.info("[轮次状态机] ✅ 第 {} 轮所有玩家回合完成 → 进入结束阶段",
                match.getCurrentRound());

        enterRoundEnd(match, state);
        return true;
    }

    // ================================================================
    //  状态查询
    // ================================================================

    /**
     * 获取指定房间的当前轮次阶段
     *
     * @param roomId 房间 ID
     * @return 轮次阶段，如果房间不存在则返回 {@link RoundPhase#IDLE}
     */
    public RoundPhase getCurrentPhase(String roomId) {
        RoundState state = states.get(roomId);
        return state != null ? state.getPhase() : RoundPhase.IDLE;
    }

    /**
     * 清理指定房间的状态（对局结束时调用）
     *
     * @param roomId 房间 ID
     */
    public void clearState(String roomId) {
        RoundState removed = states.remove(roomId);
        if (removed != null) {
            log.info("[轮次状态机] 🧹 已清理房间 {} 的状态", roomId);
        }
    }

    // ================================================================
    //  内部工具
    // ================================================================

    private RoundState getOrCreateState(String roomId) {
        return states.computeIfAbsent(roomId, k -> new RoundState());
    }
}