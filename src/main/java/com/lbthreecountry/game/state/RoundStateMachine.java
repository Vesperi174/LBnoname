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
 * 游戏轮次状态机 — 管理每轮游戏的完整生命周期
 *
 * <h3>状态流转</h3>
 * <pre>
 * ┌──────────────────────────────────────────────────────────────┐
 * │  监听 BATTLE_START 事件触发                                    │
 * │                                                              │
 * │   ┌────────────────┐                                         │
 * │   │  ROUND_START   │ ── ① ROUND.ROLL 事件（RoundController 处理轮次递增）│
 * │   │  (第 x 轮开始)  │ ── ② ROUND.START 钩子事件               │
 * │   └───────┬────────┘    ③ 钩子走完 → PLAYER_TURN             │
 * │           │                                                  │
 * │           ▼                                                  │
 * │   ┌────────────────┐    ① ROUND.TURN_START 钩子              │
 * │   │  PLAYER_TURN   │ ── ② 开启回合状态机，等待外部驱动调度     │
 * │   │  (玩家回合状态机) │    ③ nextIndex==0 时触发 onRoundComplete│
 * │   └───────┬────────┘                                        │
 * │           │                                                  │
 * │           ▼                                                  │
 * │   ┌────────────────┐    ① ROUND.END 钩子事件                  │
 * │   │  ROUND_END     │ ── ② 回到 ROUND_START（RoundController 递增轮次）│
 * │   │  (第 x 轮结束)  │                                        │
 * │   └────────────────┘                                        │
 * │           │                                                  │
 * │           ▼  (下一轮)                                         │
 * │   ┌────────────────┐                                        │
 * │   │  ROUND_START   │  ← ROUND.ROLL → ROUND.START            │
 * │   │  (第 x+1 轮)    │    → PLAYER_TURN                        │
 * │   └────────────────┘                                        │
 * └──────────────────────────────────────────────────────────────┘
 *
 * <h3>轮次记录</h3>
 * <p>轮次数值由 {@link RoundController} 独⾃管理，状态机内不维护 round 字段，
 * 如需当前轮次请读取 {@link GameMatch#getCurrentRound()}。</p>
 *
 * <h3>集成说明</h3>
 * <ul>
 *   <li>由 {@link #onBattleStart(GameEvent, GameMatch)} 监听 BATTLE_START 启动第 1 轮</li>
 *   <li>由 {@link #onRoundComplete(GameMatch)} 被 {@code GameServiceImpl.nextTurn()} 调用，
 *       在检测到 {@code nextIndex == 0}（所有存活玩家均完成一轮）时触发</li>
 *   <li>轮次切换在 {@code onRoundComplete()} 内部同步完成：
 *       ROUND.END → 回到 ROUND_START → ROUND.ROLL(RoundController递增) → ROUND.START → PLAYER_TURN</li>
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
        // 监听 BATTLE_START 事件，触发第 1 轮启动
        eventBus.register(GameEventType.BATTLE_START, EventPriority.ENGINE, this::onBattleStart);
        // 监听自己的轮次钩子——通过事件驱动状态转移
        eventBus.register(GameEventType.ROUND_START, EventPriority.ENGINE, this::onRoundStart);
        eventBus.register(GameEventType.ROUND_END,   EventPriority.ENGINE, this::onRoundEnd);
        log.info("[轮次状态机] 已注册 BATTLE_START / ROUND_START / ROUND_END 监听器");
    }

    @PreDestroy
    public void destroy() {
        states.clear();
    }

    // ================================================================
    //  事件回调 — 驱动状态转移
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

    /**
     * ROUND.START 事件回调 — 轮次正式开始
     *
     * <p>当 {@link #enterRoundStart} 发布 ROUND.START 事件后，
     * 此监听器在 {@link EventPriority#ENGINE ENGINE} 优先级下执行
     *（在所有技能、卡牌效果之后），然后转入玩家回合状态机。</p>
     *
     * <p>此钩子只有状态机自己监听，safe。</p>
     */
    private void onRoundStart(GameEvent event, GameMatch match) {
        String roomId = match.getRoomId();
        if (roomId == null) return;

        RoundState state = states.get(roomId);
        if (state == null || state.getPhase() != RoundPhase.ROUND_START) return;

        log.info("[轮次状态机]  ROUND.START 钩子走完 → 转入玩家回合阶段");
        enterPlayerTurn(match, state);
    }

    /**
     * ROUND.END 事件回调 — 轮次正式结束
     *
     * <p>当 {@link #enterRoundEnd} 发布 ROUND.END 事件后，
     * 此监听器在 ENGINE 优先级下执行，然后自动进入下一轮的 ROUND_START。</p>
     */
    private void onRoundEnd(GameEvent event, GameMatch match) {
        String roomId = match.getRoomId();
        if (roomId == null) return;

        RoundState state = states.get(roomId);
        if (state == null || state.getPhase() != RoundPhase.ROUND_END) return;

        log.info("[轮次状态机]  ROUND.END 钩子走完 → 进入第 {} 轮·开始阶段", match.getCurrentRound() + 1);
        enterRoundStart(match, state);
    }

    // ================================================================
    //  状态转移
    // ================================================================

    /**
     * 进入"第 x 轮开始时"状态
     *
     * <p>内部顺序：</p>
     * <ol>
     * <li>发布 {@link GameEventType#ROUND_ROLL} 事件 → {@link RoundController} 完成 round++</li>
     *   <li>发布 {@link GameEventType#ROUND_START} 钩子事件</li>
     *   <li>触发 {@link #onRoundStart} 监听器完成下一状态转移</li>
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

        // ── ② 发布 ROUND.START（同步阻塞）→ onRoundStart → enterPlayerTurn ──
        GameEvent roundStartEvent = GameEvent.builder()
                .type(GameEventType.ROUND_START)
                .sourceId("system")
                .build();
        roundStartEvent.putData("roomId", match.getRoomId());
        roundStartEvent.putData("round", newRound);
        roundStartEvent.putData("totalTurns", match.getTotalTurns());
        eventBus.publish(roundStartEvent, match);
    }

    /**
     * 进入"玩家回合状态机"状态
     *
     * <p>此状态为轮次状态机的第二状态 — 内部包含一个独立的子状态机（玩家回合状态机），
     * 当前暂不实现内部细节，仅做线性流转占位。</p>
     *
     * <p>进入此状态时发布 {@link GameEventType#ROUND_TURN_START} 钩子事件，
     * 监听方可在此钩子中初始化本轮各玩家回合所需的上下文，
     * 然后开始调度第 1 个玩家的回合流程。</p>
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
     * <p>发布 {@link GameEventType#ROUND_END} 钩子事件，所有监听器执行完毕后，
     * 自动回到 {@link #enterRoundStart(GameMatch, RoundState)} 开启下一轮
     *（轮次递增由 {@link RoundController} 在 ROUND.ROLL 事件中处理）。</p>
     */
    private void enterRoundEnd(GameMatch match, RoundState state) {
        state.setPhase(RoundPhase.ROUND_END);
        int round = match.getCurrentRound();

        log.info("[轮次状态机] 🔚 第 {} 轮·结束阶段 — 发布 ROUND.END 事件", round);

        // ── 发布 ROUND.END 事件（同步阻塞，等所有钩子走完） ──
        GameEvent roundEndEvent = GameEvent.builder()
                .type(GameEventType.ROUND_END)
                .sourceId("system")
                .build();
        roundEndEvent.putData("roomId", match.getRoomId());
        roundEndEvent.putData("round", round);
        roundEndEvent.putData("totalTurns", match.getTotalTurns());
        eventBus.publish(roundEndEvent, match);
        // ── publish 返回后，onRoundEnd 监听器已执行完毕
        //    （包括其内的 enterRoundStart → 下一轮 ROUND.START → onRoundStart → enterPlayerTurn）──
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
     *   <li>进入 {@link RoundPhase#ROUND_END} 状态，发布 ROUND.END 钩子</li>
     *   <li>回到 {@link RoundPhase#ROUND_START}（内部完成 round++ → ROUND.START → PLAYER_TURN）</li>
     *   <li>回到 {@link RoundPhase#PLAYER_TURN} 状态等待下一轮驱动</li>
     * </ol>
     *
     * <p><b>注意：</b>调用此方法后，调用方应继续执行新轮次首位玩家的回合初始化逻辑
     *（如设置 currentPlayerIndex、发布 TURN_BEFORE/TURN_ACTIVE 等），
     * 状态机不接管回合级别的逻辑。</p>
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