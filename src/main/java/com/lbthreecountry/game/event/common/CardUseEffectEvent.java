package com.lbthreecountry.game.event.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lbthreecountry.game.GameMatch;
import com.lbthreecountry.game.GamePlayer;
import com.lbthreecountry.game.event.EventBus;
import com.lbthreecountry.game.event.EventPriority;
import com.lbthreecountry.game.event.GameEvent;
import com.lbthreecountry.game.event.GameEventType;
import com.lbthreecountry.model.card.CardInstance;
import com.lbthreecountry.websocket.WebSocketSessionManager;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 使用牌事件 — 监听 {@code CARD.USE} 触发钩子，执行使用牌生命周期
 *
 * <p>调用方需传入 {@code useplayer}、{@code targetplayer}、{@code card} 三个必填对象。</p>
 *
 * <h3>使用牌生命周期</h3>
 * <pre>
 * CARD.USE (触发钩子)
 *   ├── CARD.USE.BEFORE   (使用牌前，可修改 / 可取消)
 *   ├── CARD.USE.ACTIVE   (使用牌时，可修改 / 可取消)
 *   ├── CARD.USE.EFFECT   (执行牌效果，由具体卡牌监听)
 *   ├── CARD.USE.AFTER    (使用牌后，仅通知)
 *   └── CARD.MOVE         (移入弃牌堆，由 MoveCardEvent 处理完整移牌生命周期)
 * </pre>
 *
 * <h3>触发事件数据字段</h3>
 * <pre>
 * ┌──────────────┬──────────────┬──────────────────────────────────────┐
 * │ 字段          │ 类型          │ 说明                                 │
 * ├──────────────┼──────────────┼──────────────────────────────────────┤
 * │ useplayer    │ GamePlayer   │ 使用牌的玩家（必填）                   │
 * │ targetplayer │ GamePlayer   │ 目标玩家（必填）                       │
 * │ card         │ CardInstance │ 使用的卡牌实例（必填）                  │
 * └──────────────┴──────────────┴──────────────────────────────────────┘
 * </pre>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // ===== 触发使用牌 =====
 * GameEvent event = GameEvent.builder()
 *     .type(GameEventType.CARD_USE)
 *     .sourceId(usePlayer.getPlayerId())
 *     .build()
 *     .putData("useplayer", usePlayer)
 *     .putData("targetplayer", targetPlayer)
 *     .putData("card", cardInstance);
 * eventBus.publish(event, match);
 *
 * // ===== 技能监听：使用牌前修改 =====
 * eventBus.register("CARD.USE.BEFORE", EventPriority.SKILL, (ev, m) -> {
 *     GamePlayer target = ev.getData("targetplayer");
 *     // 可修改 targetplayer 或取消
 * });
 * }</pre>
 */
@Component
public class CardUseEffectEvent {

    private static final Logger log = LoggerFactory.getLogger(CardUseEffectEvent.class);

    /** 默认去向 — 弃牌堆（与 MoveCardEvent 一致） */
    private static final String DEST_DISCARD = "DISCARD_PILE";

    // ================================================================
    //  依赖
    // ================================================================

    private final EventBus eventBus;
    private final WebSocketSessionManager sessionManager;
    private final ObjectMapper objectMapper;

    public CardUseEffectEvent(EventBus eventBus, WebSocketSessionManager sessionManager) {
        this.eventBus = eventBus;
        this.sessionManager = sessionManager;
        this.objectMapper = new ObjectMapper();
    }

    @PostConstruct
    public void init() {
        eventBus.register(GameEventType.CARD_USE, EventPriority.ENGINE, this::onUseCard);
    }

    // ================================================================
    //  事件回调 — 使用牌生命周期
    // ================================================================

    /**
     * {@code CARD.USE} 事件回调 — 执行使用牌生命周期
     *
     * <p>从事件数据中读取使用牌参数，依次执行：</p>
     * <ol>
     *   <li>{@code CARD.USE.BEFORE} — 使用牌前（初始数据，监听器可修改或取消）</li>
     *   <li>{@code CARD.USE.ACTIVE} — 使用牌时（BEFORE 修改后的数据，监听器可修改或取消）</li>
     *   <li>{@code CARD.USE.EFFECT} — 执行牌效果（ACTIVE 修改后的数据，由具体卡牌监听执行效果）</li>
     *   <li>{@code CARD.USE.AFTER} — 使用牌后钩子（实际使用的数据，仅通知）</li>
     *   <li>{@code CARD.MOVE} — 移入弃牌堆（委托 MoveCardEvent 处理完整的移牌生命周期）</li>
     * </ol>
     */
    private void onUseCard(GameEvent event, GameMatch match) {
        // ── 读取调用方传入的参数（必填） ──
        GamePlayer useplayer = event.getData("useplayer");
        GamePlayer targetplayer = event.getData("targetplayer");
        CardInstance card = event.getData("card");

        if (useplayer == null || targetplayer == null || card == null) {
            log.warn("[使用牌事件] 事件中缺少必要参数（useplayer/targetplayer/card），忽略");
            return;
        }

        log.info("[使用牌事件] 玩家 {} 对 {} 使用牌 [{}]",
                useplayer.getPlayerId(), targetplayer.getPlayerId(), card.getDefId());

        // ── 构造可修改的临时数据对象 ──
        HookData data = new HookData(useplayer, targetplayer, card);

        // ── 1) 使用牌前（初始数据） ──
        data.publishAndSync(GameEventType.CARD_USE_BEFORE, event, match, eventBus);
        if (data.cancelled) {
            log.info("[使用牌事件] BEFORE 钩子已取消 — {} 的使用牌被取消", useplayer.getPlayerId());
            writeResult(event, data);
            return;
        }
        // ── 2) 使用牌时（BEFORE 修改后的数据） ──
        data.publishAndSync(GameEventType.CARD_USE_ACTIVE, event, match, eventBus);
        if (data.cancelled) {
            log.info("[使用牌事件] ACTIVE 钩子已取消 — {} 的使用牌被取消", useplayer.getPlayerId());
            writeResult(event, data);
            return;
        }
        // ── 2.1) 通知前端：卡牌已在牌桌中央（使用牌时确认动画） ──
        broadcastCardFlyToTable(match, data.useplayer, data.card, "HAND", "TABLE_CENTER");

        // ── 3) 执行牌效果（ACTIVE 修改后的数据） ──
        data.publishEffect(event, match, eventBus);

        // ── 4) 使用牌后钩子（实际使用的数据，仅通知） ──
        data.publishAfterOnly(event, match, eventBus);

        // ── 5) 移入弃牌堆（委托 MoveCardEvent 处理完整移牌生命周期） ──
        data.publishMoveToDiscard(event, match, eventBus);

        // ── 写入结果到触发事件 ──
        writeResult(event, data);
    }

    // ================================================================
    //  前端通信
    // ================================================================

    /**
     * 广播"卡牌移动"动画消息到前端
     *
     * <p>通知所有玩家，某张卡牌从指定区域飞入另一区域（如从手牌区到牌桌中央），
     * 前端收到后可播放卡牌飞行动画。</p>
     *
     * @param match  当前对局
     * @param player 使用卡牌的玩家
     * @param card   使用的卡牌实例
     * @param from   来源区域（如 "HAND"）
     * @param to     目标区域（如 "TABLE_CENTER"）
     */
    private void broadcastCardFlyToTable(GameMatch match, GamePlayer player, CardInstance card,
                                          String from, String to) {
        try {
            List<String> allPlayerIds = match.getPlayers().stream()
                    .map(GamePlayer::getPlayerId)
                    .collect(Collectors.toList());

            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("type", "CARD_MOVE");
            msg.put("playerId", player.getPlayerId());
            msg.put("playerName", player.getPlayerName());
            msg.put("cardInstanceId", card.getInstanceId());
            msg.put("cardDefId", card.getDefId());
            msg.put("suit", card.getSuit());
            msg.put("suitName", card.getSuit().getDescription());
            msg.put("point", card.getPoint());
            msg.put("from", from);
            msg.put("to", to);

            String json = objectMapper.writeValueAsString(msg);
            sessionManager.broadcastToRoom(allPlayerIds, json, null);
            log.debug("[使用牌事件] 广播 CARD_MOVE → {} 的 [{}] ({} → {})",
                    player.getPlayerId(), card.getDefId(), from, to);
        } catch (Exception e) {
            log.warn("[使用牌事件] 广播 CARD_MOVE 失败", e);
        }
    }

    // ================================================================
    //  内部方法
    // ================================================================

    /** 将使用牌结果写回触发事件 */
    private void writeResult(GameEvent event, HookData data) {
        event.putData("cancelled", data.cancelled);
    }

    // ================================================================
    //  HookData — 临时数据容器
    // ================================================================

    /**
     * 临时数据容器 — 发布钩子后从事件中回读可能被修改的数据
     */
    private static class HookData {
        GamePlayer useplayer;
        GamePlayer targetplayer;
        CardInstance card;
        boolean cancelled;

        HookData(GamePlayer useplayer, GamePlayer targetplayer, CardInstance card) {
            this.useplayer = useplayer;
            this.targetplayer = targetplayer;
            this.card = card;
        }

        /**
         * 发布钩子事件并同步数据回主事件
         * <p>用于 {@code BEFORE} / {@code ACTIVE} 钩子，监听器可修改
         * {@code useplayer}、{@code targetplayer}、{@code card} 或取消。</p>
         */
        void publishAndSync(String hookType, GameEvent originalEvent,
                            GameMatch match, EventBus eventBus) {
            GameEvent hookEvent = GameEvent.builder()
                    .type(hookType)
                    .sourceId(originalEvent.getSourceId())
                    .build();
            hookEvent.putData("useplayer", useplayer)
                    .putData("targetplayer", targetplayer)
                    .putData("card", card);
            eventBus.publish(hookEvent, match);

            // 检查钩子事件是否被监听器取消
            if (hookEvent.isCancelled()) {
                this.cancelled = true;
                return; // 被取消，不再回读数据
            }

            // 回读监听器可能修改后的值（三个字段都可修改）
            GamePlayer newUseplayer = hookEvent.getData("useplayer");
            if (newUseplayer != null) this.useplayer = newUseplayer;

            GamePlayer newTargetplayer = hookEvent.getData("targetplayer");
            if (newTargetplayer != null) this.targetplayer = newTargetplayer;

            CardInstance newCard = hookEvent.getData("card");
            if (newCard != null) this.card = newCard;
        }

        /**
         * 发布 {@code CARD.USE.EFFECT} 执行牌效果钩子
         * <p>由具体卡牌监听此钩子执行各自的牌面效果（如【杀】的效果）。</p>
         */
        void publishEffect(GameEvent originalEvent, GameMatch match, EventBus eventBus) {
            GameEvent hookEvent = GameEvent.builder()
                    .type(GameEventType.CARD_USE_EFFECT_HOOK)
                    .sourceId(originalEvent.getSourceId())
                    .build();
            hookEvent.putData("useplayer", useplayer)
                    .putData("targetplayer", targetplayer)
                    .putData("card", card);
            eventBus.publish(hookEvent, match);
        }

        /**
         * 发布 {@code CARD.USE.AFTER} 钩子事件（仅通知，不回读数据）
         */
        void publishAfterOnly(GameEvent originalEvent, GameMatch match, EventBus eventBus) {
            GameEvent hookEvent = GameEvent.builder()
                    .type(GameEventType.CARD_USE_AFTER)
                    .sourceId(originalEvent.getSourceId())
                    .build();
            hookEvent.putData("useplayer", useplayer)
                    .putData("targetplayer", targetplayer)
                    .putData("card", card)
                    .putData("cancelled", false);
            eventBus.publish(hookEvent, match);
        }

        /**
         * 发布 {@code CARD.MOVE} 移牌事件 — 将牌移入弃牌堆
         * <p>委托 MoveCardEvent 处理完整的移牌生命周期（BEFORE → 移牌 → AFTER），
         * 使用 {@code player} / {@code cards} / {@code destination} 字段以兼容 MoveCardEvent。</p>
         */
        void publishMoveToDiscard(GameEvent originalEvent, GameMatch match, EventBus eventBus) {
            GameEvent hookEvent = GameEvent.builder()
                    .type(GameEventType.CARD_MOVE)
                    .sourceId(originalEvent.getSourceId())
                    .build()
                    .putData("player", useplayer)
                    .putData("cards", List.of(card))
                    .putData("destination", DEST_DISCARD);
            eventBus.publish(hookEvent, match);
        }
    }
}