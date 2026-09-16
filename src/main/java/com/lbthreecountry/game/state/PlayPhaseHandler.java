package com.lbthreecountry.game.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lbthreecountry.game.GameMatch;
import com.lbthreecountry.game.GamePlayer;
import com.lbthreecountry.game.event.EventBus;
import com.lbthreecountry.game.event.EventPriority;
import com.lbthreecountry.game.event.GameEvent;
import com.lbthreecountry.game.event.GameEventType;
import com.lbthreecountry.game.event.PlayerDecision;
import com.lbthreecountry.model.enums.impl.GamePhase;
import com.lbthreecountry.websocket.WebSocketSessionManager;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 出牌阶段处理器 — 监听"出牌阶段进行中"事件，执行出牌循环
 *
 * <p>当 {@link PlayerTurnStateMachine} 进入 PLAY 阶段（State 5）时，
 * 会发布 {@code PHASE.ACTIVE.PLAY} 事件。本组件监听此事件后，
 * 进入出牌决策循环，直到玩家主动结束回合。</p>
 *
 * <h3>出牌生命周期</h3>
 * <pre>
 * PlayerTurnStateMachine.enterPhase()
 *   └── publish("PHASE.ACTIVE.PLAY")
 *         │
 *         └── PlayPhaseHandler.onPlayPhase()          ← 本处理器
 *               │
 *               ┌── repeat ──────────────────────────┐
 *               │    ├── publish("CARD.SELECT.ACTIVE") │  ← 卡牌检测模块监听
 *               │    ├── 发送 ACTION_DECISION 到前端    │
 *               │    ├── 等待玩家决策（阻塞）             │
 *               │    ├── [play_card] 处理出牌           │
 *               │    └── [end_turn]  退出循环           │
 *               └────────────────────────────────────┘
 * </pre>
 *
 * <h3>前端消息格式（ACTION_DECISION）</h3>
 * <pre>{@code
 * {
 *   "type": "ACTION_DECISION",
 *   "timeout": 15,
 *   "description": "出牌阶段，请选择要使用的牌",
 *   "mode": "none",
 *   "selectableOptions": [],
 *   "actions": [
 *     { "text": "确定",    "value": "confirm" },
 *     { "text": "取消",    "value": "cancel" },
 *     { "text": "结束回合", "value": "end_turn" }
 *   ]
 * }
 * }</pre>
 */
@Component
public class PlayPhaseHandler {

    private static final Logger log = LoggerFactory.getLogger(PlayPhaseHandler.class);

    private final EventBus eventBus;
    private final WebSocketSessionManager sessionManager;
    private final PendingDecisionManager decisionManager;
    private final ObjectMapper objectMapper;

    public PlayPhaseHandler(EventBus eventBus,
                            WebSocketSessionManager sessionManager,
                            PendingDecisionManager decisionManager) {
        this.eventBus = eventBus;
        this.sessionManager = sessionManager;
        this.decisionManager = decisionManager;
        this.objectMapper = new ObjectMapper();
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
    //  事件回调 — 出牌循环
    // ================================================================

    /**
     * PHASE.ACTIVE.PLAY 事件处理 — 进入出牌决策循环
     *
     * <p>重复以下步骤直到玩家结束回合：</p>
     * <ol>
     *   <li>发布 {@link GameEventType#CARD_SELECT_ACTIVE CARD.SELECT.ACTIVE} 钩子
     *       （卡牌检测模块监听此钩子，分析当前可用卡牌并推送前端状态）</li>
     *   <li>发送 {@code ACTION_DECISION} 消息给当前玩家前端</li>
     *   <li>阻塞等待前端返回决策</li>
     *   <li>如果决策是 {@code play_card}，执行出牌逻辑</li>
     *   <li>如果决策是 {@code end_turn} 或 {@code cancel}，退出循环</li>
     * </ol>
     */
    private void onPlayPhase(GameEvent event, GameMatch match) {
        String roomId = match.getRoomId();
        String playerId = event.getSourceId();
        if (playerId == null) {
            log.warn("[出牌阶段处理器] 事件中无 sourceId，忽略");
            return;
        }

        GamePlayer player = match.findPlayer(playerId);
        if (player == null) {
            log.warn("[出牌阶段处理器] 玩家 {} 不存在，忽略", playerId);
            return;
        }

        // 从房间设置读取超时时间
        int turnTime = readTurnTime(match);

        int round = match.getCurrentRound();
        log.info("[出牌阶段处理器] 第 {} 轮·玩家 {} 进入出牌阶段 (超时 {}s)",
                round, player.getPlayerName(), turnTime);

        // ── 出牌决策循环 ──
        boolean turnEnded = false;
        int decisionRound = 0;

        while (!turnEnded) {
            decisionRound++;
            log.debug("[出牌阶段处理器] 第 {} 轮·玩家 {} 决策第 {} 次",
                    round, player.getPlayerName(), decisionRound);

            // ================================================================
            //  1. 发布"选择出牌"事件钩子（卡牌检测模块监听）
            // ================================================================
            GameEvent selectEvent = GameEvent.builder()
                    .type(GameEventType.CARD_SELECT_ACTIVE)
                    .sourceId(playerId)
                    .build();
            selectEvent.putData("roomId", roomId);
            selectEvent.putData("playerId", playerId);
            selectEvent.putData("round", round);
            selectEvent.putData("decisionRound", decisionRound);
            eventBus.publish(selectEvent, match);

            // ================================================================
            //  2. 发送 ACTION_DECISION 消息给当前玩家前端
            // ================================================================
            sendActionDecision(roomId, playerId, turnTime);

            // ================================================================
            //  3. 等待前端决策（阻塞）
            // ================================================================
            PlayerDecision decision = decisionManager.waitForDecision(
                    roomId, player, turnTime
            );

            // ================================================================
            //  4. 处理决策
            // ================================================================
            if (decision.isEndTurn()) {
                log.info("[出牌阶段处理器] 第 {} 轮·玩家 {} 结束出牌阶段 (决策: {})",
                        round, player.getPlayerName(), decision.getAction());
                turnEnded = true;

            } else if (decision.isPlayCard()) {
                handlePlayCard(match, player, decision);

            } else {
                log.warn("[出牌阶段处理器] 未知决策: {}", decision.getAction());
                turnEnded = true;
            }
        }

        log.info("[出牌阶段处理器] 第 {} 轮·玩家 {} 出牌阶段结束",
                round, player.getPlayerName());
    }

    // ================================================================
    //  内部方法
    // ================================================================

    /**
     * 处理出牌决策 — 执行出牌操作
     */
    private void handlePlayCard(GameMatch match, GamePlayer player, PlayerDecision decision) {
        log.info("[出牌阶段处理器] {} 使用卡牌 {} (目标: {})",
                player.getPlayerName(),
                decision.getCardInstanceId(),
                decision.getTargetIds());

        // 注：实际的卡牌使用逻辑由 GameServiceImpl.playCard() 接管，
        // PlayPhaseHandler 只负责触发出牌事件。
        // 前端 PLAY_CARD 消息会直接走 GameWebSocketHandler.handlePlayCard，
        // 此处保留结构以供未来扩展（如 AI 自动出牌）。
    }

    /**
     * 发送 ACTION_DECISION 消息给当前玩家前端
     */
    private void sendActionDecision(String roomId, String playerId, int turnTime) {
        try {
            String json = objectMapper.writeValueAsString(Map.of(
                    "type", "ACTION_DECISION",
                    "timeout", turnTime,
                    "description", "出牌阶段，请选择要使用的牌",
                    "mode", "none",
                    "selectableOptions", List.of(),
                    "actions", List.of(
                            Map.of("text", "确定", "value", "confirm"),
                            Map.of("text", "取消", "value", "cancel"),
                            Map.of("text", "结束回合", "value", "end_turn")
                    )
            ));
            sessionManager.sendMessage(playerId, json);
        } catch (Exception e) {
            log.error("[出牌阶段处理器] 发送 ACTION_DECISION 失败", e);
        }
    }

    /**
     * 从房间设置读取出牌超时时间
     */
    private int readTurnTime(GameMatch match) {
        // 尝试从 match 中读取 roomSettings（GameMatch 可能存有 roomId 可查）
        // 默认 15 秒
        int defaultTime = 15;

        // 通过 match.getRoomSettings() 或类似方式获取
        // 如果 GameMatch 没有直接存储 settings，则返回默认值
        // 未来可通过读取 gameService 或 roomService 获取更精确的值
        return defaultTime;
    }
}