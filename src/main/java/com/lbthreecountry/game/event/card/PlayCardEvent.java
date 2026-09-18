package com.lbthreecountry.game.event.card;

import com.lbthreecountry.game.GameMatch;
import com.lbthreecountry.game.GamePlayer;
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
 * 打出牌事件 — 监听 {@code CARD.PLAY} 触发钩子，执行打出牌生命周期
 *
 * <h3>打出牌生命周期</h3>
 * <pre>
 * CARD.PLAY (触发钩子)
 *   ├── CARD.PLAY.BEFORE  (打出前，可修改 / 可取消)
 *   ├── CARD.PLAY.ACTIVE  (打出时，可修改 / 可取消)
 *   ├── CARD.MOVE         (执行移牌，由 MoveCardEvent 处理完整移牌生命周期)
 *   └── CARD.PLAY.AFTER   (打出后钩子，仅通知)
 * </pre>
 *
 * <h3>触发事件数据字段</h3>
 * <pre>
 * ┌──────────────┬──────────────────────┬──────────────────────────────┐
 * │ 字段名        │ 类型                  │ 说明                           │
 * ├──────────────┼──────────────────────┼──────────────────────────────┤
 * │ player       │ GamePlayer           │ 打出牌的玩家（必填）              │
 * │ cards        │ List&lt;CardInstance&gt;   │ 打出的卡牌列表（必填）             │
 * │ destination  │ String               │ 打出后去向，默认 "DISCARD"       │
 * ├──────────────┴──────────────────────┴──────────────────────────────┤
 * │ 处理完成后，以下字段写入事件数据：                                     │
 * ├──────────────┬──────────────────────┬──────────────────────────────┤
 * │ actualCount  │ int                  │ 实际打出牌数量                   │
 * │ cardIds      │ List&lt;Long&gt;           │ 打出的卡牌实例 ID 列表            │
 * │ cancelled    │ boolean              │ 是否被取消                     │
 * └──────────────┴──────────────────────┴──────────────────────────────┘
 * </pre>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // ===== 触发打出牌 =====
 * GameEvent event = GameEvent.builder()
 *     .type(GameEventType.CARD_PLAY)
 *     .sourceId(player.getPlayerId())
 *     .build()
 *     .putData("player", player)
 *     .putData("cards", List.of(card))
 *     .putData("destination", "DISCARD");
 * eventBus.publish(event, match);
 *
 * // ===== 技能监听：打出前修改或取消 =====
 * eventBus.register("CARD.PLAY.BEFORE", EventPriority.SKILL, (ev, m) -> {
 *     List<CardInstance> cards = ev.getData("cards");
 *     // 可以修改 cards 或取消
 * });
 *
 * // ===== 技能监听：打出后通知 =====
 * eventBus.register("CARD.PLAY.AFTER", EventPriority.SKILL, (ev, m) -> {
 *     int actual = ev.getDataOrDefault("actualCount", 0);
 * });
 * }</pre>
 */
@Component
public class PlayCardEvent {

    private static final Logger log = LoggerFactory.getLogger(PlayCardEvent.class);

    /** 默认去向 — 弃牌堆（与 MoveCardEvent 的 DISCARD_PILE 一致） */
    public static final String DEST_DISCARD = "DISCARD_PILE";

    // ================================================================
    //  依赖
    // ================================================================

    private final EventBus eventBus;

    public PlayCardEvent(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    @PostConstruct
    public void init() {
        eventBus.register(GameEventType.CARD_PLAY, EventPriority.ENGINE, this::onPlayCard);
        
    }

    // ================================================================
    //  事件回调 — 打出牌生命周期
    // ================================================================

    /**
     * {@code CARD.PLAY} 事件回调 — 执行打出牌生命周期
     *
     * <p>从事件数据中读取打出参数，依次执行：</p>
     * <ol>
     *   <li>{@code CARD.PLAY.BEFORE} — 打出前（初始数据，监听器可修改 {@code cards} 或取消）</li>
     *   <li>{@code CARD.PLAY.ACTIVE} — 打出时（BEFORE 修改后的数据，监听器可修改 {@code cards} 或取消）</li>
     *   <li>{@code CARD.MOVE} — 执行移牌（发布 CARD.MOVE，由 MoveCardEvent 处理完整移牌生命周期）</li>
     *   <li>{@code CARD.PLAY.AFTER} — 打出后钩子（实际使用的数据，仅通知）</li>
     * </ol>
     */
    private void onPlayCard(GameEvent event, GameMatch match) {
        // ── 读取调用方传入的参数 ──
        GamePlayer player = event.getData("player");
        if (player == null) {
            String playerId = event.getData("playerId");
            if (playerId == null) {
                log.warn("[打出牌事件] 事件中无 player，忽略");
                return;
            }
            player = match.findPlayer(playerId);
            if (player == null) {
                log.warn("[打出牌事件] 玩家 {} 不存在，忽略", playerId);
                return;
            }
        }

        List<CardInstance> cards = event.getData("cards");
        if (cards == null || cards.isEmpty()) {
            log.warn("[打出牌事件] cards 为空，忽略");
            return;
        }

        String destination = event.getDataOrDefault("destination", DEST_DISCARD);
        if (destination == null) {
            destination = DEST_DISCARD;
        }

        log.info("[打出牌事件] 玩家 {} 打出 {} 张牌 → {}", player.getPlayerId(), cards.size(), destination);

        // ── 构造可修改的临时数据对象 ──
        HookData data = new HookData(player, cards, destination);

        // ── 1) 打出前（初始数据） ──
        data.publishAndSync(GameEventType.CARD_PLAY_BEFORE, event, match, eventBus);
        if (data.cancelled) {
            log.info("[打出牌事件] BEFORE 钩子已取消 — {} 的打出被取消", player.getPlayerId());
            writeResult(event, data);
            return;
        }

        // ── 2) 打出时（BEFORE 修改后的数据） ──
        data.publishAndSync(GameEventType.CARD_PLAY_ACTIVE, event, match, eventBus);
        if (data.cancelled) {
            log.info("[打出牌事件] ACTIVE 钩子已取消 — {} 的打出被取消", player.getPlayerId());
            writeResult(event, data);
            return;
        }

        // ── 3) 打出执行（ACTIVE 修改后的数据） ──
        if (data.cards == null || data.cards.isEmpty()) {
            log.info("[打出牌事件] 打出牌数量为 0，跳过后续流程");
            writeResult(event, data);
            return;
        }

        data.publishExecute(event, match, eventBus);

        log.info("[打出牌事件] 玩家 {} 实际打出 {} 张牌 → {}", player.getPlayerId(), data.actualCount, data.destination);

        // ── 4) 打出后钩子（实际使用的数据） ──
        data.publishAfterOnly(event, match, eventBus);

        // ── 写入结果到触发事件 ──
        writeResult(event, data);
    }

    /** 将打出结果写回触发事件 */
    private void writeResult(GameEvent event, HookData data) {
        event.putData("actualCount", data.actualCount);
        event.putData("cardIds", data.cardIds);
        event.putData("cancelled", data.cancelled);
    }

    // ================================================================
    //  HookData — 临时数据容器
    // ================================================================

    /**
     * 临时数据容器 — 发布钩子后从事件中回读可能被修改的数据
     */
    private static class HookData {
        GamePlayer player;
        String destination;
        List<CardInstance> cards;
        int actualCount;
        List<Long> cardIds;
        boolean cancelled;

        HookData(GamePlayer player, List<CardInstance> cards, String destination) {
            this.player = player;
            this.cards = cards;
            this.destination = destination;
            this.cardIds = cards.stream()
                    .map(CardInstance::getInstanceId)
                    .toList();
        }

        /**
         * 发布钩子事件并同步数据回主事件
         * <p>用于 {@code BEFORE} / {@code ACTIVE} 钩子，监听器可修改
         * {@code player}、{@code cards}、{@code destination} 或取消。</p>
         */
        void publishAndSync(String hookType, GameEvent originalEvent,
                            GameMatch match, EventBus eventBus) {
            GameEvent hookEvent = GameEvent.builder()
                    .type(hookType)
                    .sourceId(originalEvent.getSourceId())
                    .build();
            hookEvent.putData("player", player)
                    .putData("playerId", player.getPlayerId())
                    .putData("cards", cards)
                    .putData("cardIds", cardIds)
                    .putData("destination", destination);
            eventBus.publish(hookEvent, match);

            // 检查钩子事件是否被监听器取消
            if (hookEvent.isCancelled()) {
                this.cancelled = true;
                return; // 被取消，不再回读数据
            }

            // 回读监听器可能修改后的值（三个字段都可修改）
            GamePlayer newPlayer = hookEvent.getData("player");
            if (newPlayer != null) this.player = newPlayer;

            List<CardInstance> newCards = hookEvent.getData("cards");
            if (newCards != null) this.cards = newCards;

            String newDest = hookEvent.getData("destination");
            if (newDest != null) this.destination = newDest;
        }

        /**
         * 发布 {@code AFTER} 钩子事件（仅通知，不回读数据）
         */
        void publishAfterOnly(GameEvent originalEvent, GameMatch match, EventBus eventBus) {
            GameEvent hookEvent = GameEvent.builder()
                    .type(GameEventType.CARD_PLAY_AFTER)
                    .sourceId(originalEvent.getSourceId())
                    .build();
            hookEvent.putData("player", player)
                    .putData("playerId", player.getPlayerId())
                    .putData("cards", cards)
                    .putData("cardIds", cardIds)
                    .putData("destination", destination)
                    .putData("actualCount", actualCount)
                    .putData("cancelled", false);
            eventBus.publish(hookEvent, match);
        }

        /**
         * 发布 {@code CARD.MOVE} 移牌事件
         * <p>委托 MoveCardEvent 处理完整的移牌生命周期（BEFORE → 移牌 → AFTER），
         * 处理完毕后回读 {@code actualCount}。</p>
         */
        void publishExecute(GameEvent originalEvent, GameMatch match, EventBus eventBus) {
            GameEvent hookEvent = GameEvent.builder()
                    .type(GameEventType.CARD_MOVE)
                    .sourceId(originalEvent.getSourceId())
                    .build()
                    .putData("player", player)
                    .putData("cards", cards)
                    .putData("destination", destination);
            eventBus.publish(hookEvent, match);

            // 回读 actualCount（MoveCardEvent 写入的实际处理张数）
            Integer execCount = hookEvent.getData("actualCount");
            if (execCount != null) this.actualCount = execCount;
        }
    }
}