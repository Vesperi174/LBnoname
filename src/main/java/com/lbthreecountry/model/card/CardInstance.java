package com.lbthreecountry.model.card;

import com.lbthreecountry.model.enums.impl.CardStatus;
import com.lbthreecountry.model.enums.impl.CardSubType;
import com.lbthreecountry.model.enums.impl.CardSuit;
import com.lbthreecountry.model.enums.impl.CardType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 卡牌实例模型 — 代表一张卡牌的具体实例
 * <p>
 * 每张卡牌在游戏过程中都有一个实例，实例包含卡牌的唯一ID、引用定义、当前状态等。
 * 卡牌的行为逻辑通过 {@code defId} 从 {@link com.lbthreecountry.game.card.CardLibrary} 中
 * 获取 {@link com.lbthreecountry.model.card.def.CardDef} 来决定。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CardInstance {
    /**
     * 实例唯一ID（全局唯一，跨对局）
     */
    private Long instanceId;
    /**
     * 引用卡牌定义的ID（如 "sha"、"tao"、"juedou"）
     */
    private String defId;
    /**
     * 花色（此实例的具体花色）
     */
    private CardSuit suit;
    /**
     * 点数（此实例的具体点数，1(A) ~ 13(K)）
     */
    private Integer point;
    /**
     * 当前状态（手牌/装备区/弃牌堆/判定区等）
     */
    private CardStatus status;
    /**
     * 是否被技能修改过（如丈八蛇矛转化的杀）
     */
    private boolean isModified;
    /**
     * 当前持有者（玩家ID）
     */
    private String ownerId;

    /**
     * 获取卡牌名称（通过 defId 查找定义）
     * 注意：此方法需要在有 CardLibrary 的上下文中使用，
     * 纯数据场景下返回 defId 作为标识。
     *
     * @return 卡牌显示名称
     */
    public String getCardName() {
        return "[" + defId + "]";
    }

    /**
     * 获取卡牌子类型（通过 defId 查找定义）
     *
     * @return 卡牌子类型
     */
    public CardSubType getSubType() {
        // 简单映射常用 defId 到子类型
        // 完整的查找需通过 CardLibrary
        return mapDefIdToSubType(defId);
    }

    /**
     * 获取卡牌类型（通过 defId 查找定义）
     *
     * @return 卡牌类型
     */
    public CardType getCardType() {
        return mapDefIdToType(defId);
    }

    /**
     * 将 defId 映射到 CardSubType
     */
    private CardSubType mapDefIdToSubType(String defId) {
        if (defId == null) return null;
        return switch (defId) {
            // 基本牌
            case "sha" -> CardSubType.SHA;
            case "shan" -> CardSubType.SHAN;
            case "tao" -> CardSubType.TAO;
            case "jiu" -> CardSubType.JIU;
            // 锦囊牌
            case "juedou" -> CardSubType.JUEDOU;
            case "nanman" -> CardSubType.NANMAN;
            case "wanjian" -> CardSubType.WANJIAN;
            case "taoyuan" -> CardSubType.TAOYUAN;
            case "wuzhong" -> CardSubType.WUZHONG;
            case "shunshou" -> CardSubType.SHUNSHOU;
            case "guohe" -> CardSubType.GUOHE;
            case "wuxie" -> CardSubType.WUXIE;
            // 装备牌
            case "qinglong" -> CardSubType.QINGLONG;
            case "zhangba" -> CardSubType.ZHANGBA;
            case "fangtian" -> CardSubType.FANGTIAN;
            case "renwang" -> CardSubType.RENWANG;
            case "bagua" -> CardSubType.BAGUA;
            default -> null;
        };
    }

    /**
     * 将 defId 映射到 CardType
     */
    private CardType mapDefIdToType(String defId) {
        if (defId == null) return null;
        return switch (defId) {
            case "sha", "shan", "tao", "jiu" -> CardType.BASIC;
            case "juedou", "nanman", "wanjian", "taoyuan", "wuzhong",
                 "shunshou", "guohe", "wuxie",
                 "lebu", "shandian" -> CardType.STRATEGY;
            case "qinglong", "zhangba", "fangtian", "renwang", "bagua",
                 "zhuge", "guanshi", "qinggang", "cixiong",
                 "chitu", "dawan", "dilu", "zixin" -> CardType.EQUIPMENT;
            default -> null;
        };
    }
}