package com.lbthreecountry.model.card;

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
    /**
     * 模板ID
     */
    private Long id;
    /**
     * 卡牌名称
     */
    private String cardName;
    /**
     * 卡牌类型
     */
    private CardType cardType;
    /**
     * 子类型
     */
    private CardSubType subType;
    /**
     * 属性
     */
    private Attribute attribute;
    /**
     * 花色
     */
    private CardSuit suit;
    /**
     * 点数
     */
    private Integer point;
    /**
     * 效果处理器在 Spring 容器中的 Bean 名称
     */
    private String effectBeanName;
}
