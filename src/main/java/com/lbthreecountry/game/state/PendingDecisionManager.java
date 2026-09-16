package com.lbthreecountry.game.state;

import com.lbthreecountry.game.GamePlayer;
import com.lbthreecountry.game.event.PlayerDecision;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 待处理决策管理器 — 管理出牌阶段中前端决策的异步等待与解析
 *
 * <p>工作流程：</p>
 * <ol>
 *   <li>{@link PlayPhaseHandler} 发送前端消息后，调用 {@link #waitForDecision}
 *       创建待处理决策并阻塞等待</li>
 *   <li>{@code GameWebSocketHandler} 收到前端回复后，
 *       调用 {@link #resolve} 或 {@link #resolveCardPlay} 完成决策</li>
 *   <li>等待线程（botScheduler）被唤醒，继续出牌循环</li>
 * </ol>
 *
 * <h3>机器人自动决策</h3>
 * <p>如果当前玩家是机器人（{@link GamePlayer#isBot()}），
 * {@link #waitForDecision} 会立即以"结束回合"完成决策，无需前端响应。</p>
 */
@Component
public class PendingDecisionManager {

    private static final Logger log = LoggerFactory.getLogger(PendingDecisionManager.class);

    /** roomId → 待处理的决策 Future */
    private final Map<String, CompletableFuture<PlayerDecision>> pendingMap = new ConcurrentHashMap<>();

    // ================================================================
    //  等待决策（由 PlayPhaseHandler 调用，在 botScheduler 线程阻塞）
    // ================================================================

    /**
     * 等待玩家决策（阻塞调用）
     *
     * @param roomId  房间 ID
     * @param player  当前玩家（用于判断是否为机器人）
     * @param timeout 超时秒数
     * @return 玩家决策；超时返回"结束回合"
     */
    public PlayerDecision waitForDecision(String roomId, GamePlayer player, int timeout) {
        // 机器人 → 立即返回"结束回合"
        if (player.isBot()) {
            log.debug("[决策管理器] {} 是机器人，自动结束回合", player.getPlayerName());
            return PlayerDecision.endTurn();
        }

        CompletableFuture<PlayerDecision> future = new CompletableFuture<>();
        pendingMap.put(roomId, future);

        try {
            PlayerDecision decision = future.get(timeout, TimeUnit.SECONDS);
            log.debug("[决策管理器] {} 的决策完成: action={}",
                    player.getPlayerName(), decision.getAction());
            return decision;
        } catch (java.util.concurrent.TimeoutException e) {
            log.warn("[决策管理器] {} 决策超时 ({}s)，自动结束回合",
                    player.getPlayerName(), timeout);
            return PlayerDecision.endTurn();
        } catch (Exception e) {
            log.error("[决策管理器] {} 等待决策异常", player.getPlayerName(), e);
            return PlayerDecision.cancel();
        } finally {
            pendingMap.remove(roomId);
        }
    }

    // ================================================================
    //  解析决策（由 GameWebSocketHandler 调用，在 Netty 线程触发）
    // ================================================================

    /**
     * 解析结束回合决策
     *
     * @param roomId 房间 ID
     * @return 是否成功解析（false 表示没有等待中的决策）
     */
    public boolean resolveEndTurn(String roomId) {
        return resolve(roomId, PlayerDecision.endTurn());
    }

    /**
     * 解析出牌决策
     *
     * @param roomId         房间 ID
     * @param cardInstanceId 使用的卡牌实例 ID
     * @param targetIds      目标玩家 ID 列表
     * @return 是否成功解析（false 表示没有等待中的决策）
     */
    public boolean resolveCardPlay(String roomId, long cardInstanceId, java.util.List<String> targetIds) {
        return resolve(roomId, PlayerDecision.playCard(cardInstanceId, targetIds));
    }

    /**
     * 解析任意决策
     *
     * @param roomId   房间 ID
     * @param decision 决策对象
     * @return 是否成功解析
     */
    public boolean resolve(String roomId, PlayerDecision decision) {
        CompletableFuture<PlayerDecision> future = pendingMap.get(roomId);
        if (future == null) {
            log.warn("[决策管理器] 房间 {} 没有等待中的决策", roomId);
            return false;
        }
        return future.complete(decision);
    }

    // ================================================================
    //  清理
    // ================================================================

    /**
     * 清除指定房间的待处理决策（用于异常恢复或游戏结束）
     */
    public void clear(String roomId) {
        CompletableFuture<PlayerDecision> future = pendingMap.remove(roomId);
        if (future != null && !future.isDone()) {
            future.complete(PlayerDecision.cancel());
        }
    }

    @PreDestroy
    public void shutdown() {
        pendingMap.forEach((roomId, future) -> {
            if (!future.isDone()) {
                future.complete(PlayerDecision.cancel());
            }
        });
        pendingMap.clear();
    }
}