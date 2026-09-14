package com.lbthreecountry.game.card;

import com.lbthreecountry.game.GameMatch;
import com.lbthreecountry.game.GamePlayer;
import com.lbthreecountry.model.card.CardInstance;
import com.lbthreecountry.model.card.def.CardCopy;
import com.lbthreecountry.model.card.def.CardDef;
import com.lbthreecountry.model.enums.impl.CardStatus;
import com.lbthreecountry.model.enums.impl.CardSuit;
import com.lbthreecountry.model.enums.impl.CardSubType;
import com.lbthreecountry.model.enums.impl.CardType;
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

    /** 实例 ID 生成器（全局唯一，跨对局） */
    private final AtomicLong instanceIdCounter = new AtomicLong(0);

    public CardManager(CardLibrary cardLibrary) {
        this.cardLibrary = cardLibrary;
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
}