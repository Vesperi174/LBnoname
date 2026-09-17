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

import java.util.Collections;
import java.util.List;

/**
 * 移牌事件 — 监听 {@code CARD.MOVE}，触发 {@code CARD.MOVE.BEFORE → 移牌 → CARD.MOVE.AFTER} 生命周期
 *
 * <p>纯数据转移，不包含任何额外逻辑。BEFORE 钩子可以修改数据或取消，AFTER 钩子可修改数据。</p>
 *
 * <h3>触发事件数据字段</h3>
 * <pre>
 * ┌──────────────┬──────────────────────┬──────────────────────────────┐
 * │ 字段名        │ 类型                  │ 说明                           │
 * ├──────────────┼──────────────────────┼──────────────────────────────┤
 * │ cards        │ List&lt;CardInstance&gt;   │ 要移动的卡牌列表                  │
 * │ card         │ CardInstance         │ 单张卡牌（与 cards 二选一）       │
 * │ destination  │ String               │ 目标区域（必填）                 │
 * │ player       │ GamePlayer           │ 归属玩家（默认 null）            │
 * └──────────────┴──────────────────────┴──────────────────────────────┘
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 移入弃牌堆（单张）
 * eventBus.publish(GameEvent.builder()
 *     .type(GameEventType.CARD_MOVE)
 *     .sourceId(player.getPlayerId())
 *     .build()
 *     .putData("card", card)
 *     .putData("destination", "DISCARD_PILE"), match);
 *
 * // 批量移入弃牌堆
 * eventBus.publish(GameEvent.builder()
 *     .type(GameEventType.CARD_MOVE)
 *     .sourceId(player.getPlayerId())
 *     .build()
 *     .putData("cards", cards)
 *     .putData("destination", "DISCARD_PILE"), match);
 *
 * // 监听 BEFORE 钩子（可取消）
 * eventBus.register("CARD.MOVE.BEFORE", EventPriority.SKILL, (ev, m) -> {
 *     ev.putData("destination", "REMOVED");
 *     ev.cancel();
 * });
 *
 * // 监听 AFTER 钩子
 * eventBus.register("CARD.MOVE.AFTER", EventPriority.SKILL, (ev, m) -> {
 *     int actual = ev.getDataOrDefault("actualCount", 0);
 * });
 * }</pre>
 */
@Component
public class MoveCardEvent {

    private static final Logger log = LoggerFactory.getLogger(MoveCardEvent.class);

    private final EventBus eventBus;
    private final CardManager cardManager;

    public MoveCardEvent(EventBus eventBus, CardManager cardManager) {
        this.eventBus = eventBus;
        this.cardManager = cardManager;
    }

    @PostConstruct
    public void init() {
        eventBus.register(GameEventType.CARD_MOVE, EventPriority.ENGINE, this::onMove);
        log.info("[移牌事件] 已注册 CARD.MOVE 监听器 (ENGINE 优先级)");
    }

    /**
     * {@code CARD.MOVE} 事件回调 — 执行 {@code BEFORE → 移牌 → AFTER} 生命周期
     */
    private void onMove(GameEvent event, GameMatch match) {
        // ── 1. 解析基础数据 ──
        List<CardInstance> cards = resolveCards(event);
        if (cards == null || cards.isEmpty()) {
            log.warn("[移牌事件] 未提供 card 或 cards，忽略");
            event.putData("actualCount", 0);
            return;
        }

        String destination = event.getData("destination");
        if (destination == null || destination.isBlank()) {
            log.warn("[移牌事件] destination 为空，忽略");
            event.putData("actualCount", 0);
            return;
        }

        GamePlayer player = event.getData("player");

        HookData data = new HookData(cards, destination, player);

        // ── 2. BEFORE 钩子：可修改数据 / 可取消 ──
        data.publishBefore(event, match, eventBus);
        if (data.cancelled) {
            log.debug("[移牌事件] BEFORE 被取消");
            event.putData("actualCount", 0);
            return;
        }

        if (data.cards == null || data.cards.isEmpty()) {
            log.warn("[移牌事件] BEFORE 将 cards 改为空，取消移牌");
            event.putData("actualCount", 0);
            return;
        }

        // ── 3. 执行移牌 ──
        cardManager.moveAllToZone(match, data.cards, data.destination, data.player);
        data.actualCount = data.cards.size();

        // ── 4. AFTER 钩子：可修改数据 ──
        data.publishAfter(event, match, eventBus);

        // ── 5. 写回结果 ──
        writeResult(event, data);
    }

    // ================================================================
    //  内部方法
    // ================================================================

    private void writeResult(GameEvent event, HookData data) {
        event.putData("cards", data.cards);
        event.putData("destination", data.destination);
        event.putData("player", data.player);
        event.putData("actualCount", data.actualCount);
        event.putData("cancelled", data.cancelled);
    }

    /**
     * 优先取 {@code cards}（列表），其次取 {@code card}（单张）
     */
    @SuppressWarnings("unchecked")
    private List<CardInstance> resolveCards(GameEvent event) {
        List<CardInstance> cards = event.getData("cards");
        if (cards != null && !cards.isEmpty()) {
            return cards;
        }
        CardInstance single = event.getData("card");
        if (single != null) {
            return Collections.singletonList(single);
        }
        return null;
    }

    // ================================================================
    //  HookData — 临时数据容器
    // ================================================================

    /**
     * 临时数据容器 — 发布钩子后从事件中回读可能被修改的数据
     */
    private static class HookData {
        List<CardInstance> cards;
        String destination;
        GamePlayer player;
        int actualCount;
        List<Long> cardIds;
        boolean cancelled;

        HookData(List<CardInstance> cards, String destination, GamePlayer player) {
            this.cards = cards;
            this.destination = destination;
            this.player = player;
            this.cardIds = cards.stream()
                    .map(CardInstance::getInstanceId)
                    .toList();
        }

        /**
         * 发布 BEFORE 钩子 — 监听器可修改数据或取消
         */
        void publishBefore(GameEvent originalEvent, GameMatch match, EventBus eventBus) {
            GameEvent hookEvent = GameEvent.builder()
                    .type(GameEventType.CARD_MOVE_BEFORE)
                    .sourceId(originalEvent.getSourceId())
                    .build()
                    .putData("cards", cards)
                    .putData("destination", destination)
                    .putData("player", player)
                    .putData("cardIds", cardIds);
            eventBus.publish(hookEvent, match);

            if (hookEvent.isCancelled()) {
                this.cancelled = true;
                return;
            }

            // 回读监听器可能修改后的值
            List<CardInstance> newCards = hookEvent.getData("cards");
            if (newCards != null) this.cards = newCards;

            String newDest = hookEvent.getData("destination");
            if (newDest != null) this.destination = newDest;

            GamePlayer newPlayer = hookEvent.getData("player");
            if (newPlayer != null) this.player = newPlayer;
        }

        /**
         * 发布 AFTER 钩子 — 监听器可修改数据（不可取消）
         */
        void publishAfter(GameEvent originalEvent, GameMatch match, EventBus eventBus) {
            GameEvent hookEvent = GameEvent.builder()
                    .type(GameEventType.CARD_MOVE_AFTER)
                    .sourceId(originalEvent.getSourceId())
                    .build()
                    .putData("cards", cards)
                    .putData("destination", destination)
                    .putData("player", player)
                    .putData("cardIds", cardIds)
                    .putData("actualCount", actualCount)
                    .putData("cancelled", false);
            eventBus.publish(hookEvent, match);

            // 回读监听器可能修改后的值
            List<CardInstance> newCards = hookEvent.getData("cards");
            if (newCards != null) this.cards = newCards;

            String newDest = hookEvent.getData("destination");
            if (newDest != null) this.destination = newDest;

            GamePlayer newPlayer = hookEvent.getData("player");
            if (newPlayer != null) this.player = newPlayer;

            Integer newActual = hookEvent.getData("actualCount");
            if (newActual != null) this.actualCount = newActual;
        }
    }
}