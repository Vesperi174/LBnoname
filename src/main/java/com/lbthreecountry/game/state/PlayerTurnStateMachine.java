package com.lbthreecountry.game.state;

import com.lbthreecountry.game.GameMatch;
import com.lbthreecountry.game.GamePlayer;
import com.lbthreecountry.game.event.EventBus;
import com.lbthreecountry.game.event.EventPriority;
import com.lbthreecountry.game.event.GameEvent;
import com.lbthreecountry.game.event.GameEventType;
import com.lbthreecountry.model.enums.impl.GamePhase;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家回合状态机 — 管理每轮中所有玩家的回合生命周期
 *
 * <h3>状态流转</h3>
 * <pre>
 * ┌──────────────────────────────────────────────────────────────────────────┐
 * │  监听 ROUND.TURN_START 事件触发（由 RoundStateMachine.enterPlayerTurn 发布）│
 * │                                                                          │
 * │  State 0 ──  LISTENING (监听中)                                           │
 * │     │  监听到 ROUND.TURN_START → 取座位 1 的玩家 ID                         │
 * │     ▼                                                                    │
 * │  State 1 ──  TURN_START (玩家 x 回合开始)                                  │
 * │     │  发布 TURN.BEFORE → TURN.ACTIVE                                     │
 * │     ▼                                                                    │
 * │  State 2 ──  PREPARE (准备阶段)                                            │
 * │     │  发布 PHASE.BEFORE.PREPARE → PHASE.START.PREPARE                    │
 * │     │  → PHASE.ACTIVE.PREPARE → PHASE.END.PREPARE                        │
 * │     │  → PHASE.AFTER.PREPARE                                              │
 * │     ▼                                                                    │
 * │  State 3 ──  JUDGE (判定阶段)                                              │
 * │     │  发布 PHASE.BEFORE.JUDGE → PHASE.START.JUDGE                        │
 * │     │  → PHASE.ACTIVE.JUDGE → PHASE.END.JUDGE                            │
 * │     │  → PHASE.AFTER.JUDGE                                                │
 * │     ▼                                                                    │
 * │  State 4 ──  DRAW (摸牌阶段)                                               │
 * │     │  发布 PHASE.BEFORE.DRAW → PHASE.START.DRAW                          │
 * │     │  → PHASE.ACTIVE.DRAW → PHASE.END.DRAW                              │
 * │     │  → PHASE.AFTER.DRAW                                                 │
 * │     ▼                                                                    │
 * │  State 5 ──  PLAY (出牌阶段)                                               │
 * │     │  发布 PHASE.BEFORE.PLAY → PHASE.START.PLAY                          │
 * │     │  → PHASE.ACTIVE.PLAY → PHASE.END.PLAY                              │
 * │     │  → PHASE.AFTER.PLAY                                                 │
 * │     ▼                                                                    │
 * │  State 6 ──  DISCARD (弃牌阶段)                                            │
 * │     │  发布 PHASE.BEFORE.DISCARD → PHASE.START.DISCARD                    │
 * │     │  → PHASE.ACTIVE.DISCARD → PHASE.END.DISCARD                        │
 * │     │  → PHASE.AFTER.DISCARD                                              │
 * │     ▼                                                                    │
 * │  State 7 ──  END (结束阶段)                                                  │
 * │     │  发布 PHASE.BEFORE.END → PHASE.START.END                            │
 * │     │  → PHASE.ACTIVE.END → PHASE.END.END                                │
 * │     │  → PHASE.AFTER.END                                                  │
 * │     ▼                                                                    │
 * │  State 8 ──  TURN_END (玩家 x 回合结束)                                    │
 * │     │  发布 TURN.END → TURN.AFTER                                         │
 * │     │  取下一存活玩家 ID，回到 State 1                                      │
 * │     │  无下一存活玩家 → State 9                                            │
 * │     ▼                                                                    │
 * │  State 9 ──  FINISHED (生命周期结束)                                       │
 * │    通知 RoundStateMachine.onRoundComplete()                                │
 * └──────────────────────────────────────────────────────────────────────────┘
 *
 * <h3>事件钩子命名</h3>
 * <p>对于每个阶段（Phase），发布 5 个钩子事件：</p>
 * <ul>
 *   <li>{@code PHASE.BEFORE.<PHASE>} — 开始前</li>
 *   <li>{@code PHASE.START.<PHASE>} — 开始时（新增，位于 BEFORE 和 ACTIVE 之间）</li>
 *   <li>{@code PHASE.ACTIVE.<PHASE>} — 进行中</li>
 *   <li>{@code PHASE.END.<PHASE>} — 结束时</li>
 *   <li>{@code PHASE.AFTER.<PHASE>} — 结束后</li>
 * </ul>
 *
 * <h3>轮次接续</h3>
 * <p>当所有存活玩家的回合都执行完毕后，进入 {@link #FINISHED} 状态，
 * 调用 {@link RoundStateMachine#onRoundComplete(GameMatch)} 通知轮次状态机
 * 进入 ROUND_END → 下一轮 ROUND_START → ...</p>
 */
@Component
public class PlayerTurnStateMachine {

    private static final Logger log = LoggerFactory.getLogger(PlayerTurnStateMachine.class);

    private final EventBus eventBus;
    private final RoundStateMachine roundStateMachine;

    /** roomId → 玩家回合状态 */
    private final Map<String, PlayerTurnState> states = new ConcurrentHashMap<>();

    public PlayerTurnStateMachine(EventBus eventBus, RoundStateMachine roundStateMachine) {
        this.eventBus = eventBus;
        this.roundStateMachine = roundStateMachine;
    }

    // ================================================================
    //  状态定义
    // ================================================================

    /**
     * 玩家回合阶段枚举（按执行顺序排列）
     *
     * <p>State 6 为弃牌阶段（DISCARD），State 7 为结束阶段（END）。</p>
     */
    public enum PlayerTurnPhase {
        /** State 0 — 监听中，等待 ROUND.TURN_START 事件 */
        LISTENING,
        /** State 1 — 玩家 x 回合开始 */
        TURN_START,
        /** State 2 — 准备阶段 */
        PREPARE,
        /** State 3 — 判定阶段 */
        JUDGE,
        /** State 4 — 摸牌阶段 */
        DRAW,
        /** State 5 — 出牌阶段 */
        PLAY,
        /** State 6 — 弃牌阶段 */
        DISCARD_1,
        /** State 7 — 结束阶段 */
        END_PHASE,
        /** State 8 — 玩家 x 回合结束 */
        TURN_END,
        /** State 9 — 生命周期结束 */
        FINISHED
    }

    /**
     * 单个房间的玩家回合状态
     */
    @Data
    private static class PlayerTurnState {
        /** 当前阶段 */
        private PlayerTurnPhase phase = PlayerTurnPhase.LISTENING;
        /** 当前行动玩家 ID */
        private String currentPlayerId;
        /** 当前行动玩家在 players 列表中的下标 */
        private int currentPlayerIndex;
        /** 本轮首位玩家的下标（用于检测是否所有玩家已完成一轮） */
        private int firstPlayerIndex;
    }

    // ================================================================
    //  初始化 & 销毁
    // ================================================================

    @PostConstruct
    public void init() {
        // 监听 ROUND.TURN_START 事件（由 RoundStateMachine 在进入 PLAYER_TURN 状态时发布）
        eventBus.register(GameEventType.ROUND_TURN_START, EventPriority.ENGINE, this::onRoundTurnStart);
        log.info("[玩家回合状态机] 已注册 ROUND.TURN_START 监听器");
    }

    @PreDestroy
    public void destroy() {
        states.clear();
    }

    // ================================================================
    //  事件回调 — 驱动状态转移
    // ================================================================

    /**
     * ROUND.TURN_START 事件处理 — 启动玩家回合序列（State 0 → State 1）
     *
     * <p>由 {@code RoundStateMachine.enterPlayerTurn()} 发布此事件，
     * 收到后取座位 1 的玩家作为本轮首位，进入 TURN_START 状态。</p>
     */
    private void onRoundTurnStart(GameEvent event, GameMatch match) {
        String roomId = match.getRoomId();
        if (roomId == null) return;

        PlayerTurnState state = getOrCreateState(roomId);
        if (state.getPhase() != PlayerTurnPhase.LISTENING) {
            log.warn("[玩家回合状态机] 房间 {} 当前状态为 {}，不在 LISTENING 阶段，忽略 ROUND.TURN_START",
                    roomId, state.getPhase());
            return;
        }

        List<GamePlayer> players = match.getPlayers();
        if (players == null || players.isEmpty()) {
            log.warn("[玩家回合状态机] 房间 {} 无玩家数据，忽略 ROUND.TURN_START", roomId);
            return;
        }

        // 取座位 1 的玩家（players 列表按 gameSeat 升序排列，gameSeat 为 0-based，
        // 座位 1 即列表中下标 0 的玩家）
        int firstIdx = 0;
        GamePlayer firstPlayer = players.get(firstIdx);
        state.setCurrentPlayerId(firstPlayer.getPlayerId());
        state.setCurrentPlayerIndex(firstIdx);
        match.setCurrentPlayerIndex(firstIdx);
        state.setFirstPlayerIndex(firstIdx);

        log.info("[玩家回合状态机]  第 {} 轮·玩家回合序列开始 — 首位玩家: {} (座位号: {})",
                match.getCurrentRound(), firstPlayer.getPlayerId(), firstPlayer.getGameSeat());

        // State 0 → State 1
        state.setPhase(PlayerTurnPhase.TURN_START);
        executeCurrentPhase(match, state);
    }

    // ================================================================
    //  状态执行
    // ================================================================

    /**
     * 根据当前阶段执行对应的逻辑，然后自动转移到下一阶段
     */
    private void executeCurrentPhase(GameMatch match, PlayerTurnState state) {
        switch (state.getPhase()) {
            case TURN_START -> enterTurnStart(match, state);
            case PREPARE    -> enterPhase(match, state, GamePhase.PREPARE);
            case JUDGE      -> enterPhase(match, state, GamePhase.JUDGE);
            case DRAW       -> enterPhase(match, state, GamePhase.DRAW);
            case PLAY       -> enterPhase(match, state, GamePhase.PLAY);
            case DISCARD_1  -> enterPhase(match, state, GamePhase.DISCARD);
            case END_PHASE  -> enterPhase(match, state, GamePhase.END);
            case TURN_END   -> enterTurnEnd(match, state);
            case FINISHED   -> enterFinished(match, state);
            default -> {
                // LISTENING 状态不应在此执行
                log.warn("[玩家回合状态机] 意外进入 LISTENING 状态的 executeCurrentPhase");
            }
        }
    }

    // ================================================================
    //  状态转移：State 1 — 回合开始
    // ================================================================

    /**
     * State 1 — 玩家 x 回合开始
     *
     * <p>依次发布：</p>
     * <ol>
     *   <li>{@link GameEventType#TURN_BEFORE TURN.BEFORE} — 回合开始前</li>
     *   <li>{@link GameEventType#TURN_ACTIVE TURN.ACTIVE} — 回合开始时</li>
     * </ol>
     */
    private void enterTurnStart(GameMatch match, PlayerTurnState state) {
        String playerId = state.getCurrentPlayerId();
        int round = match.getCurrentRound();

        log.info("[玩家回合状态机] — State 1 第 {} 轮·玩家 {} 回合开始", round, playerId);

        // ── ① TURN.BEFORE（开始前）──
        GameEvent beforeEvent = buildTurnEvent(GameEventType.TURN_BEFORE, match, state);
        eventBus.publish(beforeEvent, match);

        // ── ② TURN.ACTIVE（开始时）──
        GameEvent activeEvent = buildTurnEvent(GameEventType.TURN_ACTIVE, match, state);
        eventBus.publish(activeEvent, match);

        // 进入下一阶段：State 1 → State 2
        state.setPhase(PlayerTurnPhase.PREPARE);
        executeCurrentPhase(match, state);
    }

    // ================================================================
    //  状态转移：State 2 ~ 7 — 各阶段
    // ================================================================

    /**
     * State 2~7 — 各阶段执行
     *
     * <p>依次发布 5 个钩子事件：</p>
     * <ol>
     *   <li>{@code PHASE.BEFORE.<Phase>} — 开始前</li>
     *   <li>{@code PHASE.START.<Phase>} — 开始时</li>
     *   <li>{@code PHASE.ACTIVE.<Phase>} — 进行中</li>
     *   <li>{@code PHASE.END.<Phase>} — 结束时</li>
     *   <li>{@code PHASE.AFTER.<Phase>} — 结束后</li>
     * </ol>
     *
     * @param phase 要执行的阶段
     */
    private void enterPhase(GameMatch match, PlayerTurnState state, GamePhase phase) {
        String playerId = state.getCurrentPlayerId();
        int round = match.getCurrentRound();

        log.info("[玩家回合状态机] — State {} 第 {} 轮·玩家 {} {}",
                state.getPhase().ordinal(), round, playerId, phase.getDescription());

        // ── 0️⃣ 同步对局当前阶段（事件监听器可通过 match.getCurrentPhase() 获取）──
        match.setCurrentPhase(phase);

        // ── ① PHASE.BEFORE.<Phase>（开始前）──
        GameEvent beforeEvent = buildPhaseEvent(GameEventType.phaseBefore(phase), match, state, phase);
        eventBus.publish(beforeEvent, match);

        // ── ② PHASE.START.<Phase>（开始时）──
        String startType = "PHASE.START." + phase.name();
        GameEvent startEvent = buildPhaseEvent(startType, match, state, phase);
        eventBus.publish(startEvent, match);

        // ── ③ PHASE.ACTIVE.<Phase>（进行中）──
        GameEvent activeEvent = buildPhaseEvent(GameEventType.phaseActive(phase), match, state, phase);
        eventBus.publish(activeEvent, match);

        // ── ④ PHASE.END.<Phase>（结束时）──
        GameEvent endEvent = buildPhaseEvent(GameEventType.phaseEnd(phase), match, state, phase);
        eventBus.publish(endEvent, match);

        // ── ⑤ PHASE.AFTER.<Phase>（结束后）──
        GameEvent afterEvent = buildPhaseEvent(GameEventType.phaseAfter(phase), match, state, phase);
        eventBus.publish(afterEvent, match);

        // 进入下一阶段
        state.setPhase(nextPhase(state.getPhase()));
        executeCurrentPhase(match, state);
    }

    // ================================================================
    //  状态转移：State 8 — 回合结束
    // ================================================================

    /**
     * State 8 — 玩家 x 回合结束
     *
     * <p>依次发布：</p>
     * <ol>
     *   <li>{@link GameEventType#TURN_END TURN.END} — 回合结束时</li>
     *   <li>{@link GameEventType#TURN_AFTER TURN.AFTER} — 回合结束后</li>
     * </ol>
     *
     * <p>然后查找下一存活玩家：</p>
     * <ul>
     *   <li>如果下一存活玩家下标 ≠ 本轮首位玩家下标 → 回到 State 1（下一玩家的回合开始）</li>
     *   <li>如果下一存活玩家下标 == 本轮首位玩家下标 → 进入 State 9（所有玩家完成一轮）</li>
     * </ul>
     */
    private void enterTurnEnd(GameMatch match, PlayerTurnState state) {
        String playerId = state.getCurrentPlayerId();
        int round = match.getCurrentRound();

        log.info("[玩家回合状态机] — State 8 第 {} 轮·玩家 {} 回合结束", round, playerId);

        // ── ① TURN.END（结束时）──
        GameEvent endEvent = buildTurnEvent(GameEventType.TURN_END, match, state);
        eventBus.publish(endEvent, match);

        // ── ② TURN.AFTER（结束后）──
        GameEvent afterEvent = buildTurnEvent(GameEventType.TURN_AFTER, match, state);
        eventBus.publish(afterEvent, match);

        // 查找下一存活玩家（按 gameSeat 顺序）
        int nextIdx = match.nextAlivePlayerIndex(state.getCurrentPlayerIndex());
        GamePlayer nextPlayer = match.getPlayers().get(nextIdx);

        if (nextIdx == state.getFirstPlayerIndex()) {
            // 已回到本轮首位玩家 → 所有存活玩家均完成了一轮
            log.info("[玩家回合状态机] ✅ 第 {} 轮所有存活玩家回合完成 — State 8 → State 9", round);
            state.setPhase(PlayerTurnPhase.FINISHED);
            executeCurrentPhase(match, state);
        } else {
            // 下一玩家继续
            state.setCurrentPlayerIndex(nextIdx);
            state.setCurrentPlayerId(nextPlayer.getPlayerId());
            match.setCurrentPlayerIndex(nextIdx);

            log.info("[玩家回合状态机]  第 {} 轮·轮到下一玩家: {} (座位号: {})",
                    round, nextPlayer.getPlayerId(), nextPlayer.getGameSeat());

            // State 8 → State 1（下一玩家的回合开始）
            state.setPhase(PlayerTurnPhase.TURN_START);
            executeCurrentPhase(match, state);
        }
    }

    // ================================================================
    //  状态转移：State 9 — 结束
    // ================================================================

    /**
     * State 9 — 玩家回合状态机生命周期结束
     *
     * <p>重置自身状态以便下一轮复用，然后设置 {@link GameMatch#roundFinished} 标记。
     * <b>注意：不再直接调用 {@link RoundStateMachine#onRoundComplete}，</b>
     * 以避免轮次间的递归调用导致 {@link StackOverflowError}。</p>
     *
     * <p>轮次切换由外部游戏循环（{@code GameWebSocketHandler}）检查
     * {@code match.isRoundFinished()} 标记后驱动。</p>
     */
    private void enterFinished(GameMatch match, PlayerTurnState state) {
        int round = match.getCurrentRound();

        log.info("[玩家回合状态机] ✅ 第 {} 轮·玩家回合状态机生命周期结束 — 设置 roundFinished 标记", round);

        // 重置为 LISTENING 状态，以便下一轮 ROUND.TURN_START 事件能再次触发
        state.setPhase(PlayerTurnPhase.LISTENING);

        // 设置轮次完成标记（不再直接调用 onRoundComplete，避免递归 StackOverflow）
        match.setRoundFinished(true);
    }

    // ================================================================
    //  阶段转移表
    // ================================================================

    /**
     * 获取当前阶段的下一个阶段
     *
     * <pre>
     * TURN_START → PREPARE → JUDGE → DRAW → PLAY → DISCARD_1 → END_PHASE → TURN_END → FINISHED
     * </pre>
     */
    private PlayerTurnPhase nextPhase(PlayerTurnPhase current) {
        return switch (current) {
            case TURN_START -> PlayerTurnPhase.PREPARE;
            case PREPARE    -> PlayerTurnPhase.JUDGE;
            case JUDGE      -> PlayerTurnPhase.DRAW;
            case DRAW       -> PlayerTurnPhase.PLAY;
            case PLAY       -> PlayerTurnPhase.DISCARD_1;
            case DISCARD_1  -> PlayerTurnPhase.END_PHASE;
            case END_PHASE  -> PlayerTurnPhase.TURN_END;
            default         -> PlayerTurnPhase.FINISHED;
        };
    }

    // ================================================================
    //  事件构建工具
    // ================================================================

    /**
     * 构建回合级事件（TURN.BEFORE / TURN.ACTIVE / TURN.END / TURN.AFTER）
     */
    private GameEvent buildTurnEvent(String eventType, GameMatch match, PlayerTurnState state) {
        return GameEvent.builder()
                .type(eventType)
                .sourceId(state.getCurrentPlayerId())
                .build()
                .putData("roomId", match.getRoomId())
                .putData("round", match.getCurrentRound())
                .putData("playerId", state.getCurrentPlayerId())
                .putData("playerIndex", state.getCurrentPlayerIndex());
    }

    /**
     * 构建阶段级事件（PHASE.BEFORE/START/ACTIVE/END/AFTER.<Phase>）
     */
    private GameEvent buildPhaseEvent(String eventType, GameMatch match,
                                      PlayerTurnState state, GamePhase phase) {
        return GameEvent.builder()
                .type(eventType)
                .sourceId(state.getCurrentPlayerId())
                .build()
                .putData("roomId", match.getRoomId())
                .putData("round", match.getCurrentRound())
                .putData("playerId", state.getCurrentPlayerId())
                .putData("playerIndex", state.getCurrentPlayerIndex())
                .putData("phase", phase)
                .putData("phaseCode", phase.getCode())
                .putData("phaseName", phase.getDescription());
    }

    // ================================================================
    //  状态查询 & 管理
    // ================================================================

    /**
     * 获取指定房间的当前玩家回合阶段
     *
     * @param roomId 房间 ID
     * @return 玩家回合阶段，如果房间不存在则返回 {@link PlayerTurnPhase#LISTENING}
     */
    public PlayerTurnPhase getCurrentPhase(String roomId) {
        PlayerTurnState state = states.get(roomId);
        return state != null ? state.getPhase() : PlayerTurnPhase.LISTENING;
    }

    /**
     * 获取指定房间的当前行动玩家 ID
     *
     * @param roomId 房间 ID
     * @return 当前玩家 ID，如果没有则返回 null
     */
    public String getCurrentPlayerId(String roomId) {
        PlayerTurnState state = states.get(roomId);
        return state != null ? state.getCurrentPlayerId() : null;
    }

    /**
     * 清理指定房间的状态（对局结束时调用）
     *
     * @param roomId 房间 ID
     */
    public void clearState(String roomId) {
        PlayerTurnState removed = states.remove(roomId);
        if (removed != null) {
            log.info("[玩家回合状态机]已清理房间 {} 的状态", roomId);
        }
    }

    // ================================================================
    //  内部工具
    // ================================================================

    private PlayerTurnState getOrCreateState(String roomId) {
        return states.computeIfAbsent(roomId, k -> new PlayerTurnState());
    }
}