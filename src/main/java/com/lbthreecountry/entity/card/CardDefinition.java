package com.lbthreecountry.entity.card;

import com.lbthreecountry.model.enums.impl.Attribute;
import com.lbthreecountry.model.enums.impl.CardSubType;
import com.lbthreecountry.model.enums.impl.CardSuit;
import com.lbthreecountry.model.enums.impl.CardType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;


/**
 * 卡牌定义模型 — 代表一张卡牌的属性和效果
 * 每张卡牌都有一个唯一的定义，定义了卡牌的属性、效果、子类型等
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CardDefinition {
    private Long id;                // 模板ID
    private String cardName;        // 卡牌名称
    private CardType cardType;      // 类型
    private CardSubType subType;    // 子类型
    private Attribute attribute;      // 属性
    private CardSuit suit;          // 花色
    private Integer point;          // 点数
    private String effectBeanName;  // 效果处理器在 Spring 容器中的 Bean 名称
}
