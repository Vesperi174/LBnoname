package com.lbthreecountry.entity.card;

import com.lbthreecountry.model.enums.impl.CardStatus;
import com.lbthreecountry.model.enums.impl.CardSubType;
import com.lbthreecountry.model.enums.impl.CardType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 卡牌实例模型 — 代表一张卡牌的具体实例
 * 每张卡牌在游戏过程中都有一个实例，实例包含了卡牌的当前状态、属性、效果等
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CardInstance {
    private Long instanceId;           // 实例唯一ID（牌堆中第几张）
    private CardDefinition definition; // 引用卡牌模板
    private CardStatus status;         // 当前状态（手牌/装备区/弃牌堆/判定区等）
    private boolean isModified;        // 是否被技能修改过（如丈八蛇矛转化的杀）
    private Long ownerId;              // 当前持有者

    // 快捷方法
    public String getCardName() {
        String name ="[" + definition.getCardName() + "]";
        if (definition.getAttribute() != null) {
            name = definition.getAttribute().getDescription() + name;
        }
        return name;
    }
    public CardSubType getSubType() { return definition.getSubType(); }
    public CardType getCardType() { return definition.getCardType(); }
}

