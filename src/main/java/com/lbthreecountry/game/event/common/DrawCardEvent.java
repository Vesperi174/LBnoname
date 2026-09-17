package com.lbthreecountry.game.event.common;

import com.lbthreecountry.game.GameMatch;
import com.lbthreecountry.game.GamePlayer;
import com.lbthreecountry.game.card.CardManager;
import com.lbthreecountry.game.event.EventBus;
import com.lbthreecountry.game.event.EventPriority;
import com.lbthreecountry.game.event.GameEvent;
import com.lbthreecountry.game.event.GameEventType;
import com.lbthreecountry.model.card.CardInstance;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 摸牌事件 — 监听 {@code CARD.DRAW} 触发钩子，执行摸牌生命周期
 *
 * <h3>摸牌生命周期</h3>
 * <pre>
 * CARD.DRAW (触发钩子)
 *   ├── CARD.DRAW.BEFORE  (摸牌开始前，可修改 count / 可取消)
 *   ├── CARD.DRAW.ACTIVE  (摸牌开始时，可修改 count / 可取消)
 *   ├── cardManager.draw()  (实际摸牌)
 *   └── CARD.DRAW.AFTER   (摸牌结束后钩子，仅通知)
 * </pre>
 *
 * <h3>触发事件数据字段</h3>
 * <pre>
 * ┌──────────────┬──────────────┬────────────────────────────────┐
 * │ 字段名        │ 类型          │ 说明                           │
 * ├──────────────┼──────────────┼────────────────────────────────┤
 * │ driver       │ DrawDriver   │ 驱动来源（必填）                  │
 * │ playerId     │ String       │ 摸牌玩家 ID（必填）               │
 * │ count        │ int          │ 请求摸牌数量（默认 0）             │
 * ├──────────────┴──────────────┴────────────────────────────────┤
 * │ 处理完成后，以下字段会写入事件数据：                             │
 * ├──────────────┬──────────────┬────────────────────────────────┤
 * │ actualCount  │ int          │ 实际摸牌数量                      │
 * │ cardIds      │ List&lt;Long&gt;   │ 摸到的卡牌实例 ID 列表             │
 * │ cancelled    │ boolean      │ 是否被取消                       │
 * └──────────────┴──────────────┴────────────────────────────────┘
 * </pre>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // ===== 在摸牌阶段触发摸牌 =====
 * GameEvent event = GameEvent.builder()
 *     .type(GameEventType.CARD_DRAW)
 *     .sourceId(playerId)
 *     .build()
 *     .putData("driver", DrawDriver.DRAW_PHASE)
 *     .putData("playerId", playerId)
 *     .putData("count", 2);
 * eventBus.publish(event, match);
 *
 * // ===== 技能监听：修改摸牌数量（BEFORE 或 ACTIVE） =====
 * eventBus.register("CARD.DRAW.BEFORE", EventPriority.SKILL, (ev, m) -> {
 *     int count = ev.getData("count");
 *     ev.putData("count", count + 1); // 英姿 +1
 * });
 *
 * // ===== 技能监听：读取摸牌结果（AFTER） =====
 * eventBus.register("CARD.DRAW.AFTER", EventPriority.SKILL, (ev, m) -> {
 *     int actual = ev.getDataOrDefault("actualCount", 0);
 *     List<Long> ids = ev.getData("cardIds");
 * });
 * }</pre>
 */
@Component
public class DrawCardEvent {

    private static final Logger log = LoggerFactory.getLogger(DrawCardEvent.class);

    // ================================================================
    //  驱动来源枚举
    // ================================================================

    /** 摸牌驱动来源 */
    public enum DrawDriver {
        /** 摸牌阶段摸牌（每回合一次的固定摸牌） */
        DRAW_PHASE,
        /** 游戏牌导致摸牌（如无中生有、五谷丰登） */
        CARD_EFFECT,
        /** 技能导致摸牌（如英姿、闭月） */
        SKILL_EFFECT,
        /** 其他来源 */
        OTHER
    }

    // ================================================================
    //  依赖
    // ================================================================

    private final EventBus eventBus;
    private final CardManager cardManager;

    public DrawCardEvent(EventBus eventBus, CardManager cardManager) {
        this.eventBus = eventBus;
        this.cardManager = cardManager;
    }

    @PostConstruct
    public void init() {
        eventBus.register(GameEventType.CARD_DRAW, EventPriority.ENGINE, this::onDraw);
        log.info("[摸牌事件] 已注册 CARD.DRAW 监听器 (ENGINE 优先级)");
    }

    // ================================================================
    //  事件回调 — 摸牌生命周期
    // ================================================================

    /**
     * {@code CARD.DRAW} 事件回调 — 执行摸牌生命周期
     *
     * <p>从事件数据中读取摸牌参数，依次执行：</p>
     * <ol>
     *   <li>{@code CARD.DRAW.BEFORE} — 摸牌开始前（初始数据，监听器可修改 {@code count} 或取消）</li>
     *   <li>{@code CARD.DRAW.ACTIVE} — 摸牌开始时（BEFORE 修改后的数据，监听器可修改 {@code count} 或取消）</li>
     *   <li><b>实际摸牌</b> — 使用 ACTIVE 修改后的数据执行摸牌</li>
     *   <li>{@code CARD.DRAW.AFTER} — 摸牌结束后钩子（实际使用的数据，仅通知）</li>
     * </ol>
     */
    private void onDraw(GameEvent event, GameMatch match) {
        // ── 读取调用方传入的参数 ──
        GamePlayer player = event.getData("player");
        if (player == null) {
            String playerId = event.getData("playerId");
            if (playerId == null) {
                log.warn("[摸牌事件] 事件中无 player，忽略");
                return;
            }
            player = match.findPlayer(playerId);
            if (player == null) {
                log.warn("[摸牌事件] 玩家 {} 不存在，忽略", playerId);
                return;
            }
        }

        int count = event.getDataOrDefault("count", 0);
        if (count <= 0) {
            log.warn("[摸牌事件] count={}，无需摸牌", count);
            return;
        }

        DrawDriver driver = event.getData("driver");
        if (driver == null) {
            driver = DrawDriver.OTHER;
        }

        log.info("[摸牌事件] {} 触发的摸牌 — 玩家 {} 摸 {} 张", driver, player.getPlayerId(), count);

        // ── 构造可修改的临时数据对象 ──
        HookData data = new HookData(player, count, driver);

        // ── 1) 摸牌开始前（初始数据） ──
        data.publishAndSync(GameEventType.CARD_DRAW_BEFORE, event, match, eventBus);
        if (data.cancelled) {
            log.info("[摸牌事件] BEFORE 钩子已取消 — {} 的摸牌被取消", player.getPlayerId());
            writeResult(event, data);
            return;
        }

        // ── 2) 摸牌开始时（BEFORE 修改后的数据） ──
        data.publishAndSync(GameEventType.CARD_DRAW_ACTIVE, event, match, eventBus);
        if (data.cancelled) {
            log.info("[摸牌事件] ACTIVE 钩子已取消 — {} 的摸牌被取消", player.getPlayerId());
            writeResult(event, data);
            return;
        }

        // ── 3) 实际摸牌操作（ACTIVE 修改后的数据） ──
        if (data.count <= 0) {
            log.info("[摸牌事件] 摸牌数量为 0，跳过后续流程");
            writeResult(event, data);
            return;
        }

        List<CardInstance> drawn = cardManager.draw(match, player, data.count);
        data.actualCount = drawn.size();
        data.cardIds = drawn.stream()
                .map(CardInstance::getInstanceId)
                .toList();

        log.info("[摸牌事件] {} 实际摸到 {} 张牌", player.getPlayerId(), data.actualCount);

        // ── 4) 摸牌结束后钩子（实际使用的数据） ──
        data.publishAfterOnly(event, match, eventBus);

        // ── 写入结果到触发事件 ──
        writeResult(event, data);

        // ── 兼容旧版 CARD.DRAWN 事件 ──
        publishLegacyEvent(event, match, player, data, drawn);
    }

    // ================================================================
    //  内部方法
    // ================================================================

    /** 将摸牌结果写回触发事件 */
    private void writeResult(GameEvent event, HookData data) {
        event.putData("actualCount", data.actualCount);
        event.putData("cardIds", data.cardIds);
        event.putData("cancelled", data.cancelled);
    }

    /**
     * 发布旧版 {@code CARD.DRAWN} 事件（兼容已有监听器）
     */
    private void publishLegacyEvent(GameEvent mainEvent, GameMatch match,
                                     GamePlayer player, HookData data,
                                     List<CardInstance> drawn) {
        GameEvent legacyEvent = GameEvent.builder()
                .type(GameEventType.CARD_DRAWN)
                .sourceId(player.getPlayerId())
                .targetId(player.getPlayerId())
                .build();
        legacyEvent.putData("roomId", match.getRoomId());
        legacyEvent.putData("playerId", player.getPlayerId());
        legacyEvent.putData("playerName", player.getPlayerName());
        legacyEvent.putData("count", data.count);
        legacyEvent.putData("actualCount", data.actualCount);
        legacyEvent.putData("cardIds", drawn.stream()
                .map(CardInstance::getInstanceId)
                .toList());
        legacyEvent.putData("gameSeat", player.getGameSeat());
        legacyEvent.putData("driver", data.driver);
        eventBus.publish(legacyEvent, match);
    }

    // ================================================================
    //  HookData — 临时数据容器
    // ================================================================

    /**
     * 临时数据容器 — 发布钩子后从事件中回读可能被修改的数据
     */
    private static class HookData {
        final GamePlayer player;
        final DrawDriver driver;
        int count;
        int actualCount;
        List<Long> cardIds;
        boolean cancelled;

        HookData(GamePlayer player, int count, DrawDriver driver) {
            this.player = player;
            this.count = count;
            this.driver = driver;
            this.cardIds = List.of();
        }

        /**
         * 发布钩子事件并同步数据回主事件
         * <p>用于 {@code BEFORE} / {@code ACTIVE} 钩子，监听器可修改 {@code count} 或取消。</p>
         */
        void publishAndSync(String hookType, GameEvent originalEvent,
                            GameMatch match, EventBus eventBus) {
            GameEvent hookEvent = GameEvent.builder()
                    .type(hookType)
                    .sourceId(originalEvent.getSourceId())
                    .build();
            hookEvent.putData("player", player)
                    .putData("playerId", player.getPlayerId())
                    .putData("count", count)
                    .putData("driver", driver);
            eventBus.publish(hookEvent, match);

            // 检查钩子事件是否被监听器取消
            if (hookEvent.isCancelled()) {
                this.cancelled = true;
                return; // 被取消，不再回读数据
            }

            // 回读监听器可能修改后的值
            Integer newCount = hookEvent.getData("count");
            if (newCount != null) this.count = newCount;
        }

        /**
         * 发布 {@code AFTER} 钩子事件（仅通知，不回读数据）
         */
        void publishAfterOnly(GameEvent originalEvent, GameMatch match, EventBus eventBus) {
            GameEvent hookEvent = GameEvent.builder()
                    .type(GameEventType.CARD_DRAW_AFTER)
                    .sourceId(originalEvent.getSourceId())
                    .build();
            hookEvent.putData("player", player)
                    .putData("playerId", player.getPlayerId())
                    .putData("count", count)
                    .putData("driver", driver)
                    .putData("actualCount", actualCount)
                    .putData("cardIds", cardIds)
                    .putData("cancelled", false);
            eventBus.publish(hookEvent, match);
        }
    }
}