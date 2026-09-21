package com.lbthreecountry.game.event.common;

import com.lbthreecountry.game.GameMatch;
import com.lbthreecountry.game.GamePlayer;
import com.lbthreecountry.game.event.EventBus;
import com.lbthreecountry.game.event.EventPriority;
import com.lbthreecountry.game.event.GameEvent;
import com.lbthreecountry.game.interaction.InteractionMessageStack;
import com.lbthreecountry.model.enums.impl.GameStatus;
import com.lbthreecountry.model.enums.impl.RoleType;
import com.lbthreecountry.websocket.WebSocketSessionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 游戏结束检测事件 — 监听 {@code GAME_OVER_CHECK} 钩子，判断是否满足游戏结束条件
 *
 * <p>由 {@link DeathEvent#fireGameOverCheck} 在玩家死亡后抛出，本组件根据存活玩家及其身份判定胜负：</p>
 *
 * <h3>胜负判定规则</h3>
 * <table border="1">
 *   <tr><th>条件</th><th>结果</th></tr>
 *   <tr><td>主公死亡，且仅剩一名内奸存活</td><td>内奸获胜</td></tr>
 *   <tr><td>主公死亡，其他情况</td><td>反贼获胜</td></tr>
 *   <tr><td>仅剩主公和忠臣存活</td><td>主公与忠臣获胜</td></tr>
 *   <tr><td>其他情况</td><td>游戏继续</td></tr>
 * </table>
 *
 * <h3>游戏结束清理</h3>
 * <p>结束游戏时会自动执行以下清理操作：</p>
 * <ol>
 *   <li>清空结算栈 — 终止所有未完成的事件结算（{@link #clearSettlementStack}）</li>
 *   <li>清空交互消息栈 — 释放所有等待前端响应的阻塞线程（{@link InteractionMessageStack#clearRoom}）</li>
 *   <li>设置 {@code match.status = FINISHED}</li>
 *   <li>广播 {@code GAME_OVER} 消息到前端</li>
 * </ol>
 */
@Component
public class GameOverCheckEvent {

    private static final Logger log = LoggerFactory.getLogger(GameOverCheckEvent.class);

    private final EventBus eventBus;
    private final WebSocketSessionManager sessionManager;
    private final InteractionMessageStack interactionStack;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GameOverCheckEvent(EventBus eventBus, WebSocketSessionManager sessionManager,
                              InteractionMessageStack interactionStack) {
        this.eventBus = eventBus;
        this.sessionManager = sessionManager;
        this.interactionStack = interactionStack;
    }

    @PostConstruct
    public void init() {
        eventBus.register(DeathEvent.GAME_OVER_CHECK, EventPriority.ENGINE, this::onGameOverCheck);
    }

    /**
     * {@code GAME_OVER_CHECK} 事件回调 — 执行游戏结束判定
     */
    private void onGameOverCheck(GameEvent event, GameMatch match) {
        List<GamePlayer> alivePlayers = match.getPlayers().stream()
                .filter(GamePlayer::isAlive)
                .collect(Collectors.toList());

        int aliveCount = alivePlayers.size();

        // ── 按身份分组 ──
        Set<RoleType> aliveRoles = alivePlayers.stream()
                .map(GamePlayer::getRole)
                .collect(Collectors.toSet());

        boolean lordAlive = alivePlayers.stream().anyMatch(p -> p.getRole() == RoleType.LORD);

        // ════════════════════════════════════════════
        // 情况 1：主公死亡
        // ════════════════════════════════════════════
        if (!lordAlive) {
            if (aliveCount == 1 && aliveRoles.contains(RoleType.INTRUDER)) {
                // 仅剩一名内奸 → 内奸获胜
                GamePlayer intruder = alivePlayers.get(0);
                log.info("[游戏结束] 主公死亡，仅剩内奸【{}】存活 → 内奸获胜！", intruder.getPlayerId());
                endGame(match, RoleType.INTRUDER, alivePlayers.stream()
                        .filter(p -> p.getRole() == RoleType.INTRUDER)
                        .collect(Collectors.toList()));
                return;
            }
            // 其他情况（有反贼存活，或仅有忠臣/内奸等多方存活）→ 反贼获胜
            List<GamePlayer> rebels = alivePlayers.stream()
                    .filter(p -> p.getRole() == RoleType.REBEL)
                    .collect(Collectors.toList());
            log.info("[游戏结束] 主公死亡 → 反贼获胜！(存活者: {})",
                    aliveRoles.stream().map(RoleType::getDescription).collect(Collectors.joining(",")));
            endGame(match, RoleType.REBEL, rebels);
            return;
        }

        // ════════════════════════════════════════════
        // 情况 2：主公存活
        // ════════════════════════════════════════════
        // 仅剩主公 + 忠臣（无反贼、无内奸）
        boolean hasRebel = aliveRoles.contains(RoleType.REBEL);
        boolean hasIntruder = aliveRoles.contains(RoleType.INTRUDER);

        if (!hasRebel && !hasIntruder) {
            // 只有主公和忠臣 → 主公与忠臣获胜
            List<GamePlayer> winners = alivePlayers.stream()
                    .filter(p -> p.getRole() == RoleType.LORD || p.getRole() == RoleType.MINION)
                    .collect(Collectors.toList());
            log.info("[游戏结束] 仅剩主公和忠臣 → 主公与忠臣获胜！(存活: {})", aliveCount);
            endGame(match, RoleType.LORD, winners);
            return;
        }

        // ════════════════════════════════════════════
        // 情况 3：其他情况 → 游戏继续
        // ════════════════════════════════════════════
        log.debug("[游戏结束检测] 游戏继续 (存活 {} 人, 身份: {})",
                aliveCount, aliveRoles.stream().map(RoleType::getDescription).collect(Collectors.joining(",")));
    }

    /**
     * 结束游戏
     *
     * <p>按顺序执行：清空结算栈 → 清空交互栈 → 设置游戏状态 → 广播结果。</p>
     *
     * @param match     当前对局
     * @param winnerRole 获胜阵营
     * @param winners    获胜玩家列表
     */
    private void endGame(GameMatch match, RoleType winnerRole, List<GamePlayer> winners) {
        String roomId = match.getRoomId();
        log.info("[游戏结束] {} 获胜！(房间: {})", winnerRole.getDescription(), roomId);

        // ── ① 清空结算栈：终止所有未完成的事件结算 ──
        clearSettlementStack(match);

        // ── ② 清空交互消息栈：释放所有等待前端响应的阻塞线程 ──
        interactionStack.clearRoom(roomId);

        // ── ③ 设置游戏状态 ──
        match.setStatus(GameStatus.FINISHED);

        // ── ④ 广播 GAME_OVER 消息 ──
        broadcastGameOver(match, winnerRole, winners);
    }

    // ================================================================
    //  清理方法
    // ================================================================

    /**
     * 清空结算栈
     *
     * <p>将 {@code match} 的结算栈中所有未完成的帧标记为 completed 并弹出，
     * 阻止任何后续的事件监听器执行。</p>
     */
    private void clearSettlementStack(GameMatch match) {
        var stack = match.getSettlementStack();
        if (!stack.isEmpty()) {
            log.debug("[游戏结束] 清空结算栈 (共 {} 帧)", stack.size());
            stack.clear();
        }
    }

    // ================================================================
    //  前端通信
    // ================================================================

    /**
     * 广播游戏结束消息到前端
     */
    private void broadcastGameOver(GameMatch match, RoleType winnerRole, List<GamePlayer> winners) {
        try {
            List<String> allPlayerIds = match.getPlayers().stream()
                    .map(GamePlayer::getPlayerId)
                    .collect(Collectors.toList());

            List<Map<String, Object>> winnerList = winners.stream()
                    .map(p -> {
                        Map<String, Object> info = new LinkedHashMap<>();
                        info.put("playerId", p.getPlayerId());
                        info.put("playerName", p.getPlayerName());
                        info.put("heroId", p.getHeroId());
                        info.put("role", p.getRole() != null ? p.getRole().getCode() : null);
                        info.put("roleName", p.getRole() != null ? p.getRole().getDescription() : null);
                        return info;
                    })
                    .collect(Collectors.toList());

            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("type", "GAME_OVER");
            msg.put("winnerRole", winnerRole.getCode());
            msg.put("winnerRoleName", winnerRole.getDescription());
            msg.put("winners", winnerList);

            String json = objectMapper.writeValueAsString(msg);
            sessionManager.broadcastToRoom(allPlayerIds, json, null);
            log.info("[游戏结束] {} 获胜！", winnerRole.getDescription());
        } catch (Exception e) {
            log.warn("[游戏结束] 广播 GAME_OVER 失败", e);
        }
    }
}