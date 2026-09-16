package com.lbthreecountry.game.event;

import com.lbthreecountry.game.GameMatch;
import com.lbthreecountry.game.GamePlayer;
import com.lbthreecountry.game.card.CardManager;
import com.lbthreecountry.model.card.CardInstance;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 摸牌事件 — 监听 {@code CARD.DRAW} 触发钩子，执行摸牌生命周期
 *
 * <p><b>设计原则：</b>摸牌事件是一个事件监听器，不对外暴露静态调用方法。
 * 其他模块想触发摸牌，只需发布 {@link GameEventType#CARD_DRAW CARD.DRAW} 事件
 * 并在事件数据中写明摸牌信息即可。</p>
 *
 * <h3>摸牌生命周期</h3>
 * <pre>
 * CARD.DRAW (触发钩子)
 *   ├── CARD.DRAW.BEFORE  (摸牌开始前，可修改 count / 可取消)
 *   ├── CARD.DRAW.ACTIVE  (摸牌开始时，可修改 count / 可取消)
 *   ├── cardManager.draw()  (实际摸牌 + 广播 DRAW_CARD)
 *   └── CARD.DRAW.AFTER   (摸牌结束后钩子)
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
 * │ roomId       │ String       │ 房间 ID（自动从 match 获取）       │
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
 * // publish 返回后，event 中已有 actualCount / cancelled
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

    // ================================================================
    //  注册监听器
    // ================================================================

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
     * <p>从事件数据中读取摸牌信息，依次执行：</p>
     * <ol>
     *   <li><b>BEFORE 钩子</b> — 监听器可修改 {@code count} 或取消</li>
     *   <li><b>ACTIVE 钩子</b> — 监听器可修改 {@code count} 或取消</li>
     *   <li><b>实际摸牌</b> — {@code cardManager.draw()}</li>
     *   <li><b>AFTER 钩子</b> — 摸牌后逻辑</li>
     * </ol>
     *
     * <p>处理结果写入主事件：{@code actualCount}、{@code cardIds}、{@code cancelled}。</p>
     */
    private void onDraw(GameEvent event, GameMatch match) {
        // ── 1. 读取触发事件中的摸牌参数 ──
        String playerId = event.getData("playerId");
        if (playerId == null) {
            log.warn("[摸牌事件] 事件中无 playerId，忽略");
            return;
        }

        GamePlayer player = match.findPlayer(playerId);
        if (player == null) {
            log.warn("[摸牌事件] 玩家 {} 不存在，忽略", playerId);
            return;
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

        // 补全事件中的玩家信息
        event.putData("roomId", match.getRoomId());
        event.putData("playerName", player.getPlayerName());
        event.putData("gameSeat", player.getGameSeat());

        // 记录谁驱动了这次摸牌
        event.putData("driver", driver);

        log.info("[摸牌事件] {} 触发的摸牌 — 玩家 {} 摸 {} 张",
                driver, playerId, count);

        // ================================================================
        //  阶段一：摸牌开始前钩子（BEFORE）
        // ================================================================
        GameEvent beforeHook = publishHook("BEFORE", event, match);
        if (beforeHook.isCancelled()) {
            log.info("[摸牌事件] BEFORE 钩子取消 — {} 的摸牌被取消", playerId);
            finishCancelled(event, match);
            return;
        }
        // 读取监听器可能修改后的 count
        count = beforeHook.getDataOrDefault("count", count);

        // ================================================================
        //  阶段二：摸牌开始时钩子（ACTIVE）
        // ================================================================
        GameEvent activeHook = publishHook("ACTIVE", event, match);
        if (activeHook.isCancelled()) {
            log.info("[摸牌事件] ACTIVE 钩子取消 — {} 的摸牌被取消", playerId);
            finishCancelled(event, match);
            return;
        }
        count = activeHook.getDataOrDefault("count", count);

        // ================================================================
        //  阶段三：实际摸牌操作
        // ================================================================
        List<CardInstance> drawn = cardManager.draw(match, player, count);

        // 将摸牌结果写入主事件
        event.putData("actualCount", drawn.size());
        event.putData("cardIds", drawn.stream()
                .map(CardInstance::getInstanceId)
                .toList());
        event.putData("cancelled", false);

        log.debug("[摸牌事件] {} 实际摸到 {} 张牌", playerId, drawn.size());

        // ================================================================
        //  阶段四：摸牌结束后钩子（AFTER）
        // ================================================================
        publishHook("AFTER", event, match);

        // 兼容旧版 CARD.DRAWN 事件
        publishLegacyEvent(event, match, player, count, drawn);
    }

    // ================================================================
    //  内部方法
    // ================================================================

    /**
     * 发布钩子事件并同步数据回主事件
     *
     * @param suffix    钩子后缀（"BEFORE"/"ACTIVE"/"AFTER"）
     * @param mainEvent 主事件
     * @param match     当前对局
     * @return 已发布的钩子事件
     */
    private GameEvent publishHook(String suffix, GameEvent mainEvent, GameMatch match) {
        GameEvent hook = mainEvent.createHook(suffix);
        eventBus.publish(hook, match);
        // 将钩子中的数据同步回主事件（监听器的修改生效）
        if (hook.getData() != null) {
            mainEvent.getData().putAll(hook.getData());
        }
        return hook;
    }

    /**
     * 处理被取消的摸牌 — 发布 AFTER 钩子，actualCount = 0
     */
    private void finishCancelled(GameEvent event, GameMatch match) {
        event.putData("actualCount", 0);
        event.putData("cardIds", List.of());
        event.putData("cancelled", true);
        publishHook("AFTER", event, match);
    }

    /**
     * 发布旧版 CARD.DRAWN 事件（兼容已有监听器）
     */
    private void publishLegacyEvent(GameEvent mainEvent, GameMatch match,
                                     GamePlayer player, int count,
                                     List<CardInstance> drawn) {
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
        legacyEvent.putData("driver", mainEvent.getData("driver"));
        eventBus.publish(legacyEvent, match);
    }
}