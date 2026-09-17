package com.lbthreecountry.game.card;

import com.lbthreecountry.game.GameMatch;
import com.lbthreecountry.game.GamePlayer;
import com.lbthreecountry.game.event.EventBus;
import com.lbthreecountry.game.event.GameEvent;
import com.lbthreecountry.game.event.GameEventType;
import com.lbthreecountry.model.card.CardInstance;
import com.lbthreecountry.model.card.def.CardDef;
import com.lbthreecountry.model.card.def.CardCopy;
import com.lbthreecountry.model.enums.impl.CardStatus;
import com.lbthreecountry.model.enums.impl.CardSuit;
import com.lbthreecountry.model.enums.impl.CardSubType;
import com.lbthreecountry.model.enums.impl.CardType;
import com.lbthreecountry.websocket.WebSocketSessionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 牌堆管理器 — 负责牌堆初始化、洗牌、摸牌、弃牌等操作
 * <p>
 * 每个 {@link GameMatch} 对应一个 CardManager 实例。
 * CardManager 从 {@link CardLibrary} 获取卡牌定义，展开副本生成完整牌堆。
 * </p>
 */
@Service
public class CardManager {

    private static final Logger log = LoggerFactory.getLogger(CardManager.class);

    private final CardLibrary cardLibrary;
    private final EventBus eventBus;
    private final WebSocketSessionManager sessionManager;
    private final ObjectMapper objectMapper;

    /** 实例 ID 生成器（全局唯一，跨对局） */
    private final AtomicLong instanceIdCounter = new AtomicLong(0);

    public CardManager(CardLibrary cardLibrary, EventBus eventBus, WebSocketSessionManager sessionManager) {
        this.cardLibrary = cardLibrary;
        this.eventBus = eventBus;
        this.sessionManager = sessionManager;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 初始化对局的牌堆
     * <p>
     * 将 CardLibrary 中所有卡牌的 copies 展开为 CardInstance，
     * 洗牌后放入 match 的 drawPile。
     * </p>
     *
     * @param match 要初始化牌堆的对局
     */
    public void initDeck(GameMatch match) {
        List<CardInstance> fullDeck = new ArrayList<>();

        for (CardDef def : cardLibrary.getAllDefs()) {
            if (def.getCopies() == null || def.getCopies().isEmpty()) continue;

            for (CardCopy copy : def.getCopies()) {
                CardInstance instance = createInstance(def, copy);
                fullDeck.add(instance);
            }
        }

        // 洗牌
        Collections.shuffle(fullDeck);

        match.getDrawPile().clear();
        match.getDrawPile().addAll(fullDeck);
        match.getDiscardPile().clear();

        log.info("[牌堆] 初始化完成: 共 {} 张牌 [roomId={}]", fullDeck.size(), match.getRoomId());
    }

    /**
     * 创建一张卡牌实例
     */
    private CardInstance createInstance(CardDef def, CardCopy copy) {
        return CardInstance.builder()
                .instanceId(instanceIdCounter.incrementAndGet())
                .defId(def.getId())
                .suit(parseSuit(copy.getSuit()))
                .point(copy.getPoint())
                .status(CardStatus.DRAW_PILE)
                .isModified(false)
                .ownerId(null)
                .build();
    }

    /**
     * 摸牌 — 从牌堆顶摸指定张数
     *
     * @param match 当前对局
     * @param player 摸牌玩家
     * @param count 摸牌张数
     * @return 实际摸到的牌列表
     */
    public List<CardInstance> draw(GameMatch match, GamePlayer player, int count) {
        List<CardInstance> drawn = new ArrayList<>();
        // 提前获取房间所有玩家 ID（用于广播）
        List<String> allPlayerIds = match.getPlayers().stream()
                .map(GamePlayer::getPlayerId)
                .toList();

        // ── 阶段一：逐张摸牌（仅操作数据，不推送消息） ──
        for (int i = 0; i < count; i++) {
            if (match.getDrawPile().isEmpty()) {
                // 牌堆为空：将弃牌堆洗回
                reshuffleDiscard(match);
                if (match.getDrawPile().isEmpty()) break;
            }
            CardInstance card = match.getDrawPile().remove(match.getDrawPile().size() - 1);
            card.setOwnerId(player.getPlayerId());
            card.setStatus(CardStatus.HAND);
            player.getHandCards().add(card);
            drawn.add(card);
        }

        // ── 阶段二：全部摸完后，一次性发送 DRAW_CARD 消息 ──
        if (!drawn.isEmpty()) {
            // 构造摸牌者可见的牌面数据
            List<Map<String, Object>> cardDataList = new ArrayList<>();
            for (CardInstance card : drawn) {
                CardDef def = cardLibrary.getDef(card.getDefId());
                Map<String, Object> cardData = new LinkedHashMap<>();
                cardData.put("instanceId", card.getInstanceId());
                cardData.put("defId", card.getDefId());
                cardData.put("name", def != null ? def.getName() : card.getDefId());
                cardData.put("suit", card.getSuit().name());
                cardData.put("point", card.getPoint());
                cardDataList.add(cardData);
            }

            // 本人 → 携带所有牌数据
            String ownerMsg = toJson(Map.of(
                    "type", "DRAW_CARD",
                    "playerId", player.getPlayerId(),
                    "cards", cardDataList,
                    "count", drawn.size()
            ));
            sessionManager.sendMessage(player.getPlayerId(), ownerMsg);

            // 对手 → 空数组（看不到具体牌面）
            String otherMsg = toJson(Map.of(
                    "type", "DRAW_CARD",
                    "playerId", player.getPlayerId(),
                    "cards", List.of(),
                    "count", drawn.size()
            ));
            sessionManager.broadcastToRoom(allPlayerIds, otherMsg, player.getPlayerId());
        }

        // ── 摸牌后检测事件钩子 ──
        GameEvent checkEvent = GameEvent.builder()
                .type(GameEventType.CARD_DRAW_CHECK)
                .sourceId(player.getPlayerId())
                .build();
        checkEvent.putData("playerId", player.getPlayerId());
        checkEvent.putData("playerName", player.getPlayerName());
        checkEvent.putData("drawnCount", drawn.size());
        eventBus.publish(checkEvent, match);

        return drawn;
    }

    /**
     * 弃牌 — 将卡牌放入弃牌堆
     */
    public void discard(GameMatch match, CardInstance card) {
        // 从当前所在区域移除
        removeFromCurrentZone(match, card);

        card.setOwnerId(null);
        card.setStatus(CardStatus.DISCARD_PILE);
        match.getDiscardPile().add(card);
    }

    /**
     * 批量弃牌
     */
    public void discardAll(GameMatch match, List<CardInstance> cards) {
        for (CardInstance card : cards) {
            discard(match, card);
        }
    }

    /**
     * 通用移牌 — 将卡牌移入指定区域
     *
     * <p>支持的目标区域：</p>
     * <ul>
     *   <li>{@code DISCARD_PILE} — 弃牌堆</li>
     *   <li>{@code HAND} — 目标玩家的手牌</li>
     *   <li>{@code EQUIPMENT} — 目标玩家的装备区</li>
     *   <li>{@code JUDGEMENT} — 目标玩家的判定区</li>
     *   <li>{@code DRAW_PILE} — 摸牌堆（牌堆顶）</li>
     *   <li>{@code REMOVED} — 移出游戏</li>
     * </ul>
     *
     * @param match        当前对局
     * @param card         要移动的卡牌
     * @param destination  目标区域（CardStatus 枚举名）
     * @param targetPlayer 目标玩家（HAND / EQUIPMENT / JUDGEMENT 时需要）
     */
    public void moveToZone(GameMatch match, CardInstance card, String destination, GamePlayer targetPlayer) {
        // 从当前所在区域移除
        removeFromCurrentZone(match, card);

        switch (destination) {
            case "DISCARD_PILE" -> {
                card.setOwnerId(null);
                card.setStatus(CardStatus.DISCARD_PILE);
                match.getDiscardPile().add(card);
            }
            case "HAND" -> {
                if (targetPlayer == null) {
                    log.warn("[CardManager] moveToZone HAND 时 targetPlayer 为 null");
                    return;
                }
                card.setOwnerId(targetPlayer.getPlayerId());
                card.setStatus(CardStatus.HAND);
                targetPlayer.getHandCards().add(card);
            }
            case "EQUIPMENT" -> {
                if (targetPlayer == null) {
                    log.warn("[CardManager] moveToZone EQUIPMENT 时 targetPlayer 为 null");
                    return;
                }
                card.setOwnerId(targetPlayer.getPlayerId());
                card.setStatus(CardStatus.EQUIPMENT);
                targetPlayer.getEquipCards().add(card);
            }
            case "JUDGEMENT" -> {
                if (targetPlayer == null) {
                    log.warn("[CardManager] moveToZone JUDGEMENT 时 targetPlayer 为 null");
                    return;
                }
                card.setOwnerId(targetPlayer.getPlayerId());
                card.setStatus(CardStatus.JUDGEMENT);
                targetPlayer.getJudgeArea().add(card);
            }
            case "DRAW_PILE" -> {
                card.setOwnerId(null);
                card.setStatus(CardStatus.DRAW_PILE);
                match.getDrawPile().add(card);
            }
            case "REMOVED" -> {
                card.setOwnerId(null);
                card.setStatus(CardStatus.REMOVED);
                // REMOVED 状态的牌不放在任何牌堆列表中
            }
            default -> log.warn("[CardManager] moveToZone 未知目标区域: {}", destination);
        }
    }

    /**
     * 批量通用移牌
     */
    public void moveAllToZone(GameMatch match, List<CardInstance> cards, String destination, GamePlayer targetPlayer) {
        for (CardInstance card : cards) {
            moveToZone(match, card, destination, targetPlayer);
        }
    }

    /**
     * 洗牌
     */
    public void shuffle(GameMatch match) {
        Collections.shuffle(match.getDrawPile());
        log.info("[牌堆] 洗牌完成: 共 {} 张 [roomId={}]", match.getDrawPile().size(), match.getRoomId());
    }

    /**
     * 弃牌堆洗回摸牌堆
     */
    public void reshuffleDiscard(GameMatch match) {
        if (match.getDiscardPile().isEmpty()) return;

        int count = match.getDiscardPile().size();
        match.getDrawPile().addAll(match.getDiscardPile());
        match.getDiscardPile().clear();
        Collections.shuffle(match.getDrawPile());

        log.info("[牌堆] 弃牌堆洗回: {} 张 [roomId={}]", count, match.getRoomId());
    }

    /**
     * 从当前所在区域移除卡牌
     */
    private void removeFromCurrentZone(GameMatch match, CardInstance card) {
        String ownerId = card.getOwnerId();
        if (ownerId != null) {
            GamePlayer player = match.findPlayer(ownerId);
            if (player != null) {
                player.getHandCards().remove(card);
                player.getEquipCards().remove(card);
                player.getJudgeArea().remove(card);
            }
        }
        match.getDrawPile().remove(card);
        match.getDiscardPile().remove(card);
    }

    /**
     * 根据 defId 查询卡牌定义
     */
    public CardDef getDef(String defId) {
        return cardLibrary.getDef(defId);
    }

    /**
     * 解析花色字符串为枚举
     */
    private CardSuit parseSuit(String suit) {
        if (suit == null) return null;
        return switch (suit.toUpperCase()) {
            case "HEARTS" -> CardSuit.HEARTS;
            case "DIAMONDS" -> CardSuit.DIAMONDS;
            case "CLUBS" -> CardSuit.CLUBS;
            case "SPADES" -> CardSuit.SPADES;
            default -> null;
        };
    }

    // ================================================================
    //  JSON 工具
    // ================================================================

    /**
     * 将对象转为 JSON 字符串
     */
    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.error("[CardManager] JSON 序列化失败", e);
            return "{\"type\":\"ERROR\",\"message\":\"序列化失败\"}";
        }
    }
}