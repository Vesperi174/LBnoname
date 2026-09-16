package com.lbthreecountry.game.event;

import com.lbthreecountry.game.GameMatch;
import com.lbthreecountry.game.GamePlayer;
import com.lbthreecountry.game.card.CardManager;
import com.lbthreecountry.model.card.CardInstance;

import java.util.Collections;
import java.util.List;

/**
 * 摸牌事件 — 封装摸牌完整生命周期
 *
 * <p>提供标准化的摸牌流程，包含四个阶段：</p>
 * <pre>
 * ┌──────────────────────────────────────────────────────────────────┐
 * │                        摸牌事件生命周期                          │
 * ├──────────┬──────────┬──────────┬───────────────────────────────┤
 * │  BEFORE  │  ACTIVE  │   DRAW   │            AFTER              │
 * │ (可修改)  │ (可修改)  │ (摸牌操作)│       (可修改)                │
 * └──────────┴──────────┴──────────┴───────────────────────────────┘
 * </pre>
 *
 * <h3>事件类型</h3>
 * <ul>
 *   <li>{@link GameEventType#CARD_DRAW_BEFORE CARD.DRAW.BEFORE} — 摸牌开始前钩子，监听器可修改摸牌数量等</li>
 *   <li>{@link GameEventType#CARD_DRAW_ACTIVE CARD.DRAW.ACTIVE} — 摸牌开始时钩子，监听器可修改摸牌数量等</li>
 *   <li>{@link GameEventType#CARD_DRAW_AFTER CARD.DRAW.AFTER}  — 摸牌结束后钩子，数据可被其他事件监听、调用、修改</li>
 * </ul>
 *
 * <h3>事件数据字段</h3>
 * <pre>
 * ┌──────────────┬───────────┬────────────────────────────────┐
 * │ 字段名        │ 类型      │ 说明                           │
 * ├──────────────┼───────────┼────────────────────────────────┤
 * │ roomId       │ String    │ 房间 ID                        │
 * │ playerId     │ String    │ 摸牌玩家 ID                     │
 * │ playerName   │ String    │ 摸牌玩家名称                    │
 * │ count        │ int       │ 请求摸牌数量（所有阶段可修改）    │
 * │ actualCount  │ int       │ 实际摸牌数量                     │
 * │ cardIds      │ Long[]    │ 摸到的卡牌实例ID列表             │
 * │ gameSeat     │ int       │ 摸牌玩家座位号                   │
 * └──────────────┴───────────┴────────────────────────────────┘
 * </pre>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // ===== 技能监听：修改摸牌数量（在 BEFORE 或 ACTIVE 钩子中） =====
 * eventBus.register("CARD.DRAW.BEFORE", EventPriority.SKILL, (event, match) -> {
 *     int count = event.getData("count");
 *     event.putData("count", count + 1); // 摸牌数 +1（英姿效果）
 * });
 *
 * // ===== 技能监听：读取摸牌结果（AFTER 可修改） =====
 * eventBus.register("CARD.DRAW.AFTER", EventPriority.SKILL, (event, match) -> {
 *     int actualCount = event.getData("actualCount");        // 实际摸了几张
 *     List<Long> cardIds = event.getData("cardIds");         // 摸到了哪些牌
 *     String playerName = event.getData("playerName");        // 谁摸的
 *     // 可以在 AFTER 中继续修改事件数据，影响后续监听器
 *     event.putData("extraInfo", "摸完了");
 * });
 *
 * // ===== 在服务层触发摸牌 =====
 * DrawCardEvent.DrawResult result = DrawCardEvent.execute(
 *     match, player, 2, eventBus, cardManager
 * );
 * List<CardInstance> drawn = result.getDrawn(); // 实际摸到的牌
 * }</pre>
 */
public final class DrawCardEvent {

    private DrawCardEvent() {
        // 工具类，禁止实例化
    }

    /**
     * 执行完整的摸牌生命周期
     *
     * <p>按顺序发布事件：</p>
     * <ol>
     *   <li><b>CARD.DRAW.BEFORE</b> — 摸牌开始前，监听器可修改 {@code count} 等数据</li>
     *   <li><b>CARD.DRAW.ACTIVE</b> — 摸牌开始时，监听器可修改 {@code count} 等数据</li>
     *   <li><b>摸牌操作</b> — 通过 {@link CardManager#draw} 执行实际摸牌</li>
     *   <li><b>CARD.DRAW.AFTER</b> — 摸牌结束后钩子，数据可修改</li>
     *   <li><b>CARD.DRAWN</b> — 兼容旧版事件（已废弃）</li>
     * </ol>
     *
     * <p>BEFORE/ACTIVE 中可通过 {@link GameEvent#cancel()} 取消摸牌，
     * 取消后仍会发布 AFTER 钩子，但 {@code actualCount = 0}。</p>
     *
     * @param match       当前对局
     * @param player      摸牌的玩家
     * @param count       请求摸牌数量
     * @param eventBus    事件总线
     * @param cardManager 牌堆管理器
     * @return 摸牌结果封装，包含实际摸到的牌列表和完整摸牌事件数据
     */
    public static DrawResult execute(GameMatch match, GamePlayer player, int count,
                                      EventBus eventBus, CardManager cardManager) {
        // ── 1. 创建摸牌主事件，包含完整上下文信息 ──
        GameEvent drawEvent = GameEvent.builder()
                .type(GameEventType.CARD_DRAW)
                .sourceId(player.getPlayerId())
                .targetId(player.getPlayerId())
                .build();
        drawEvent.putData("roomId", match.getRoomId());
        drawEvent.putData("playerId", player.getPlayerId());
        drawEvent.putData("playerName", player.getPlayerName());
        drawEvent.putData("count", count);
        drawEvent.putData("gameSeat", player.getGameSeat());

        // ================================================================
        //  阶段一：摸牌开始前钩子（BEFORE）
        //  — 附带本次摸牌信息，可被其他事件监听、调用、修改
        // ================================================================
        GameEvent beforeHook = publishAndSync(drawEvent, "BEFORE", eventBus, match);
        if (beforeHook.isCancelled()) {
            // 被取消 → 发布 AFTER 钩子，actualCount = 0
            return finishCancelled(drawEvent, eventBus, match);
        }
        // 读取可能被修改后的 count
        count = beforeHook.getDataOrDefault("count", count);

        // ================================================================
        //  阶段二：摸牌开始时钩子（ACTIVE）
        //  — 附带本次摸牌信息，可被其他事件监听、调用、修改
        // ================================================================
        GameEvent activeHook = publishAndSync(drawEvent, "ACTIVE", eventBus, match);
        if (activeHook.isCancelled()) {
            return finishCancelled(drawEvent, eventBus, match);
        }
        count = activeHook.getDataOrDefault("count", count);

        // ================================================================
        //  阶段三：摸牌行为
        //  — 执行摸牌操作，由调用方在获取 DrawResult 后发送前端动画
        // ================================================================
        List<CardInstance> drawn = cardManager.draw(match, player, count);

        // 将摸牌结果写入主事件
        drawEvent.putData("actualCount", drawn.size());
        drawEvent.putData("cardIds", drawn.stream()
                .map(CardInstance::getInstanceId)
                .toList());

        // ================================================================
        //  阶段四：摸牌结束后钩子（AFTER）
        //  — 附带本次摸牌信息，可被其他事件监听、调用、修改
        // ================================================================
        publishAndSync(drawEvent, "AFTER", eventBus, match);

        // 兼容旧版事件（CARD.DRAWN）
        publishLegacyEvent(drawEvent, player, count, drawn, eventBus, match);

        return DrawResult.success(drawEvent, drawn);
    }

    // ================================================================
    //  内部方法
    // ================================================================

    /**
     * 发布钩子并同步数据回主事件
     *
     * <p>由于 {@link GameEvent#createHook} 创建的是独立数据副本，
     * 监听器在钩子事件上的修改不会自动反映到主事件。
     * 此方法在钩子发布后将钩子的数据写回主事件。</p>
     *
     * @param drawEvent  主事件
     * @param hookSuffix 钩子后缀（"BEFORE"/"ACTIVE"）
     * @param eventBus   事件总线
     * @param match      当前对局
     * @return 已发布的钩子事件
     */
    private static GameEvent publishAndSync(GameEvent drawEvent, String hookSuffix,
                                              EventBus eventBus, GameMatch match) {
        GameEvent hook = drawEvent.createHook(hookSuffix);
        eventBus.publish(hook, match);
        // 将钩子中的数据同步回主事件（监听器的修改生效）
        if (hook.getData() != null) {
            drawEvent.getData().putAll(hook.getData());
        }
        return hook;
    }

    /**
     * 发布摸牌结束后钩子
     *
     * <p>与 BEFORE/ACTIVE 一样，钩子数据可被监听器修改，
     * 修改会通过 {@link #publishAndSync} 同步回主事件。</p>
     */
    private static void publishAfterHook(GameEvent drawEvent, EventBus eventBus, GameMatch match) {
        publishAndSync(drawEvent, "AFTER", eventBus, match);
    }

    /**
     * 处理被取消的摸牌
     *
     * <p>仍然发布 AFTER 钩子，但 actualCount = 0。</p>
     */
    private static DrawResult finishCancelled(GameEvent drawEvent, EventBus eventBus, GameMatch match) {
        drawEvent.putData("actualCount", 0);
        drawEvent.putData("cardIds", List.of());
        publishAfterHook(drawEvent, eventBus, match);
        return DrawResult.cancelled(drawEvent);
    }

    /**
     * 发布旧版 CARD.DRAWN 事件（兼容已有监听器）
     */
    private static void publishLegacyEvent(GameEvent drawEvent, GamePlayer player,
                                            int count, List<CardInstance> drawn,
                                            EventBus eventBus, GameMatch match) {
        GameEvent legacyEvent = GameEvent.builder()
                .type(GameEventType.CARD_DRAWN)
                .sourceId(player.getPlayerId())
                .targetId(player.getPlayerId())
                .build();
        legacyEvent.putData("roomId", match.getRoomId());
        legacyEvent.putData("playerId", player.getPlayerId());
        legacyEvent.putData("playerName", player.getPlayerName());
        legacyEvent.putData("count", count);
        legacyEvent.putData("actualCount", drawn.size());
        legacyEvent.putData("cardIds", drawn.stream()
                .map(CardInstance::getInstanceId)
                .toList());
        legacyEvent.putData("gameSeat", player.getGameSeat());
        eventBus.publish(legacyEvent, match);
    }

    // ================================================================
    //  摸牌结果
    // ================================================================

    /**
     * 摸牌结果 — 封装摸牌操作的执行结果
     *
     * <p>由 {@link #execute} 返回，包含：</p>
     * <ul>
     *   <li>完整摸牌事件对象（含所有上下文数据）</li>
     *   <li>实际摸到的卡牌列表</li>
     *   <li>摸牌是否被取消</li>
     * </ul>
     */
    public static final class DrawResult {

        private final GameEvent event;
        private final List<CardInstance> drawn;
        private final boolean cancelled;

        private DrawResult(GameEvent event, List<CardInstance> drawn, boolean cancelled) {
            this.event = event;
            this.drawn = drawn != null ? Collections.unmodifiableList(drawn) : List.of();
            this.cancelled = cancelled;
        }

        static DrawResult success(GameEvent event, List<CardInstance> drawn) {
            return new DrawResult(event, drawn, false);
        }

        static DrawResult cancelled(GameEvent event) {
            return new DrawResult(event, List.of(), true);
        }

        /** 摸牌事件对象（包含所有上下文数据，可从中获取 roomId/playerId/count 等） */
        public GameEvent getEvent() {
            return event;
        }

        /** 实际摸到的卡牌列表（不可修改） */
        public List<CardInstance> getDrawn() {
            return drawn;
        }

        /** 摸牌是否被取消（BEFORE/ACTIVE 钩子中调用 {@link GameEvent#cancel()} 后为 true） */
        public boolean isCancelled() {
            return cancelled;
        }

        /** 实际摸牌张数 */
        public int getActualCount() {
            return drawn.size();
        }

        /** 摸牌玩家 ID */
        public String getPlayerId() {
            return event != null ? event.getSourceId() : null;
        }

        /** 摸牌玩家名称 */
        public String getPlayerName() {
            return event != null ? event.getData("playerName") : null;
        }

        /** 请求摸牌张数 */
        public int getRequestedCount() {
            return event != null ? event.getDataOrDefault("count", 0) : 0;
        }
    }
}