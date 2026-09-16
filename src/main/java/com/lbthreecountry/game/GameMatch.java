package com.lbthreecountry.game;

import com.lbthreecountry.game.event.GameEvent;
import com.lbthreecountry.game.event.SettlementFrame;
import com.lbthreecountry.model.card.CardInstance;
import com.lbthreecountry.model.enums.impl.GamePhase;
import com.lbthreecountry.model.enums.impl.GameStatus;
import lombok.*;
import lombok.EqualsAndHashCode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 对局实例 — 一局三国杀游戏的全部状态
 *
 * <p>对局与房间一对一绑定，不单独设计对局 ID。
 * 所有游戏状态（玩家状态、牌堆、回合）都保存在此对象中。</p>
 *
 * <p><b>牌堆体系：</b></p>
 * <ul>
 *   <li>{@link #drawPile 摸牌堆} — 当前可摸的牌</li>
 *   <li>{@link #discardPile 弃牌堆} — 已使用/弃置的牌</li>
 *   <li>{@link #gameOuterPile 游戏外牌堆} — 不在游戏中的牌（扩展包、备用牌等）</li>
 *   <li>{@link #otherPile 其他牌堆} — 预留，供后续自定义扩展使用</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GameMatch {

    /** 关联的房间 ID（对局与房间一对一绑定） */
    private String roomId;

    // ============ 玩家 ============

    /**
     * 所有玩家（按 {@link GamePlayer#gameSeat 游戏座位号} 升序排列）
     * <p>游戏座位号在游戏开始时随机分配，与房间座位号无关。
     * 列表下标与 gameSeat 一一对应：players[0].gameSeat == 0。</p>
     */
    @Builder.Default
    private List<GamePlayer> players = new ArrayList<>();

    /** 当前行动玩家在 players 列表中的下标 */
    private int currentPlayerIndex;

    /** 当前游戏阶段 */
    @Builder.Default
    private GamePhase currentPhase = GamePhase.PREPARE;

    // ============ 牌堆 ============

    /** 摸牌堆（牌堆顶 = list末端，便于高效取牌） */
    @Builder.Default
    private List<CardInstance> drawPile = new ArrayList<>();

    /** 弃牌堆（已使用的牌放入此处） */
    @Builder.Default
    private List<CardInstance> discardPile = new ArrayList<>();

    /**
     * 游戏外牌堆 — 不在游戏中的牌
     * <p>可用于：扩展包牌、模式专属牌、暂不加入游戏的牌等。
     * 玩家可在此选择是否将某些牌加入本局游戏。</p>
     */
    @Builder.Default
    private List<CardInstance> gameOuterPile = new ArrayList<>();

    /**
     * 其他牌堆 — 预留扩展
     * <p>用于后续自定义扩展（如自创武将牌、自定义模式牌等）。</p>
     */
    @Builder.Default
    private List<CardInstance> otherPile = new ArrayList<>();

    // ============ 回合轮次 ============

    /**
     * 当前轮次（从 1 开始）
     * <p>所有存活玩家都完成一个回合视为一轮。
     * 例如 8 人局：轮次 1 = 第 1~8 回合，轮次 2 = 第 9~16 回合，以此类推。</p>
     */
    @Builder.Default
    private int currentRound = 0;

    /**
     * 累计回合数（从 0 开始，每开始一个新回合 +1）
     */
    @Builder.Default
    private int totalTurns = 0;

    // ============ 状态 ============

    /** 游戏状态（初始化/进行中/已结束） */
    @Builder.Default
    private GameStatus status = GameStatus.INIT;

    // ============ 并发锁 ============

    /**
     * 对局锁 — 确保同一时刻只有一个线程操作本对局
     * <p>所有修改 {@link GameMatch} 状态的操作（摸牌、出牌、伤害结算等）
     * 必须先调用 {@link #lock()}，操作完成后调用 {@link #unlock()}。</p>
     */
    @Builder.Default
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private transient ReentrantLock gameLock = new ReentrantLock();

    /** 加锁（如果已被其他线程锁定则等待） */
    public void lock() {
        gameLock.lock();
    }

    /** 解锁 */
    public void unlock() {
        gameLock.unlock();
    }

    // ============ 结算栈 ============

    /**
     * 事件结算栈（LIFO）
     * <p>每次 {@link com.lbthreecountry.game.event.EventBus#publish(GameEvent, GameMatch)}
     * 会压入一个 {@link SettlementFrame}，由 {@code EventBus.settle()} 循环
     * 按 LIFO 顺序执行。栈数据结构和调度逻辑分离，栈仅作存储。</p>
     */
    @Builder.Default
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private transient Deque<SettlementFrame> settlementStack = new ArrayDeque<>();

    public Deque<SettlementFrame> getSettlementStack() {
        if (settlementStack == null) {
            settlementStack = new ArrayDeque<>();
        }
        return settlementStack;
    }

    // ============ 便捷方法 ============

    /** 当前行动玩家 */
    public GamePlayer currentPlayer() {
        if (players == null || players.isEmpty()) return null;
        if (currentPlayerIndex < 0 || currentPlayerIndex >= players.size()) return null;
        return players.get(currentPlayerIndex);
    }

    /** 获取存活玩家数量 */
    public int aliveCount() {
        return (int) players.stream().filter(GamePlayer::isAlive).count();
    }

    /**
     * 下一名存活玩家的下标（按 gameSeat 顺序循环查找）
     * <p>从 fromIndex 开始，按游戏座位号顺序查找下一个存活的玩家。</p>
     */
    public int nextAlivePlayerIndex(int fromIndex) {
        int size = players.size();
        for (int i = 1; i <= size; i++) {
            int idx = (fromIndex + i) % size;
            if (players.get(idx).isAlive()) return idx;
        }
        return fromIndex; // 只剩自己
    }

    /** 当前回合数模运算（用于判定是第几轮的第几回合） */
    public int turnIndexInRound() {
        return currentPlayerIndex;
    }

    /**
     * 根据玩家 ID 查找玩家
     *
     * @param playerId 玩家 ID
     * @return 对应的 GamePlayer，未找到返回 null
     */
    public GamePlayer findPlayer(String playerId) {
        if (playerId == null || players == null) return null;
        return players.stream()
                .filter(p -> playerId.equals(p.getPlayerId()))
                .findFirst()
                .orElse(null);
    }

    // ============ 距离计算 ============

    /**
     * 计算两名玩家间的座次距离
     *
     * <p>存活玩家按 {@link GamePlayer#gameSeat 座位号} 围成一圈，
     * 相邻存活玩家距离为 1，每隔一人 +1。
     * 若顺时针和逆时针两个方向算出不同距离，取最小值。</p>
     *
     * <p><b>示例</b>（○ 死亡，● 存活）：</p>
     * <pre>
     * 座位:  0     1     2     3     4     5
     * 存活:  ●     ○     ●     ●     ●     ●
     * 到 5:  1           3     2     1     0   (距离)
     * </pre>
     * 从座位 0 到座位 4 距离为 1（直接邻接），
     * 从座位 1 到座位 4 距离为 3（逆时针：1→0→5→4 = 3，顺时针需绕过 1）。
     *
     * @param from 起始玩家
     * @param to   目标玩家
     * @return 座次距离；若两玩家相同返回 0；若任一玩家不在存活列表中返回 {@code Integer.MAX_VALUE}
     */
    public int calculateDistance(GamePlayer from, GamePlayer to) {
        if (from == null || to == null) return Integer.MAX_VALUE;
        if (from.getPlayerId().equals(to.getPlayerId())) return 0;

        // 按 gameSeat 升序提取存活玩家（players 列表本身就是 gameSeat 升序）
        List<GamePlayer> alive = new ArrayList<>(players.size());
        for (GamePlayer p : players) {
            if (p.isAlive()) alive.add(p);
        }

        int n = alive.size();
        if (n <= 1) return 0;

        // 在存活列表中定位两个玩家
        int fromPos = -1;
        int toPos = -1;
        for (int i = 0; i < n; i++) {
            if (alive.get(i).getPlayerId().equals(from.getPlayerId())) fromPos = i;
            if (alive.get(i).getPlayerId().equals(to.getPlayerId())) toPos = i;
        }
        if (fromPos == -1 || toPos == -1) return Integer.MAX_VALUE;

        int clockwise = (toPos - fromPos + n) % n;
        int counterClockwise = n - clockwise;
        return Math.min(clockwise, counterClockwise);
    }
}