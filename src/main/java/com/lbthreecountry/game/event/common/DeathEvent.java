package com.lbthreecountry.game.event.common;

import com.lbthreecountry.game.GameMatch;
import com.lbthreecountry.game.GamePlayer;
import com.lbthreecountry.game.event.EventBus;
import com.lbthreecountry.game.event.EventPriority;
import com.lbthreecountry.game.event.GameEvent;
import com.lbthreecountry.game.event.GameEventType;
import com.lbthreecountry.model.enums.impl.PlayerStatus;
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
import java.util.stream.Collectors;

/**
 * 玩家死亡事件 — 监听 {@code PLAYER.DEAD} 触发死亡处理流程
 *
 * <p>当濒死流程结束后玩家仍未脱离濒死，由 {@link DyingEvent#handleDyingFailure} 发布
 * {@code PLAYER.DEAD} 事件，本组件监听到后执行死亡处理：</p>
 * <ol>
 *   <li>将玩家状态设为 {@link PlayerStatus#DEAD}</li>
 *   <li>广播死亡消息到前端（含玩家身份）</li>
 *   <li>抛出 {@code GAME_OVER_CHECK} 钩子（携带存活玩家及身份，供游戏结束判定）</li>
 * </ol>
 *
 * <h3>触发事件数据字段</h3>
 * <pre>
 * ┌──────────────┬────────────┬──────────────────────────────────────┐
 * │ 字段          │ 类型        │ 说明                                 │
 * ├──────────────┼────────────┼──────────────────────────────────────┤
 * │ player       │ GamePlayer │ 死亡的玩家（必填）                    │
 * └──────────────┴────────────┴──────────────────────────────────────┘
 * </pre>
 */
@Component
public class DeathEvent {

    private static final Logger log = LoggerFactory.getLogger(DeathEvent.class);

    /** 游戏结束检测钩子 — 死亡事件广播后触发，监听器检查是否满足游戏结束条件 */
    public static final String GAME_OVER_CHECK = "GAME.OVER.CHECK";

    private final EventBus eventBus;
    private final WebSocketSessionManager sessionManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DeathEvent(EventBus eventBus, WebSocketSessionManager sessionManager) {
        this.eventBus = eventBus;
        this.sessionManager = sessionManager;
    }

    @PostConstruct
    public void init() {
        eventBus.register(GameEventType.PLAYER_DEAD, EventPriority.ENGINE, this::onPlayerDead);
    }

    /**
     * {@code PLAYER.DEAD} 事件回调 — 执行死亡处理流程
     *
     * <p>从事件数据中读取死亡玩家，依次执行：</p>
     * <ol>
     *   <li>将玩家状态设为 {@code DEAD}</li>
     *   <li>广播 {@code PLAYER_DEAD} 消息到前端</li>
     *   <li>抛出 {@code GAME_OVER_CHECK} 钩子（携带存活玩家及身份，供游戏结束判定）</li>
     * </ol>
     */
    private void onPlayerDead(GameEvent event, GameMatch match) {
        // ── 读取调用方传入的参数 ──
        GamePlayer player = event.getData("player");
        if (player == null) {
            log.warn("[死亡事件] 缺少 player，忽略");
            return;
        }

        log.info("[死亡事件] 玩家 {} 死亡", player.getPlayerId());

        // ── ① 设置死亡状态 ──
        player.setStatus(PlayerStatus.DEAD);

        // ── ② 广播死亡消息到前端 ──
        broadcastPlayerDead(match, player);

        // ── ③ 抛出游戏结束检测钩子 ──
        fireGameOverCheck(match);
    }

    /**
     * 抛出游戏结束检测钩子
     *
     * <p>携带当前所有存活玩家及其身份信息，供监听器（如 GameOverCheckEvent）判断游戏是否结束。</p>
     */
    private void fireGameOverCheck(GameMatch match) {
        List<Map<String, Object>> alivePlayers = match.getPlayers().stream()
                .filter(GamePlayer::isAlive)
                .map(p -> {
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("playerId", p.getPlayerId());
                    info.put("playerName", p.getPlayerName());
                    info.put("heroId", p.getHeroId());
                    RoleType role = p.getRole();
                    info.put("role", role != null ? role.getCode() : null);
                    info.put("roleName", role != null ? role.getDescription() : null);
                    return info;
                })
                .collect(Collectors.toList());

        GameEvent checkEvent = GameEvent.builder()
                .type(GAME_OVER_CHECK)
                .build()
                .putData("alivePlayers", alivePlayers);
        eventBus.publish(checkEvent, match);

        log.debug("[死亡事件] 抛出 GAME_OVER_CHECK 钩子 (存活 {} 人)", alivePlayers.size());
    }

    // ================================================================
    //  前端通信
    // ================================================================

    /**
     * 广播玩家死亡消息到前端
     *
     * <p>通知所有玩家，谁已死亡及其身份。</p>
     */
    private void broadcastPlayerDead(GameMatch match, GamePlayer player) {
        try {
            List<String> allPlayerIds = match.getPlayers().stream()
                    .map(GamePlayer::getPlayerId)
                    .collect(Collectors.toList());

            RoleType role = player.getRole();

            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("type", "PLAYER_DEAD");
            msg.put("playerId", player.getPlayerId());
            msg.put("playerName", player.getPlayerName());
            msg.put("heroId", player.getHeroId());
            msg.put("role", role != null ? role.getCode() : null);
            msg.put("roleName", role != null ? role.getDescription() : null);

            String json = objectMapper.writeValueAsString(msg);
            sessionManager.broadcastToRoom(allPlayerIds, json, null);
            log.info("[死亡事件] 广播 PLAYER_DEAD → 玩家 {} (身份:{}) 已死亡",
                    player.getPlayerId(), role != null ? role.getDescription() : "未知");
        } catch (Exception e) {
            log.warn("[死亡事件] 广播 PLAYER_DEAD 失败", e);
        }
    }
}