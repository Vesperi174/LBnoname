package com.lbthreecountry.model.enums.impl;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.lbthreecountry.model.enums.interfaces.BaseEnum;
import com.lbthreecountry.model.enums.utils.EnumUtils;

/**
 * 卡牌位置状态枚举
 */
public enum CardStatus implements BaseEnum {
    DRAW_PILE(1, "牌堆"),     // 牌堆
    HAND(2, "手牌"),          // 手牌
    EQUIPMENT(3, "装备区"),     // 装备区
    JUDGEMENT(4, "判定区"),     // 判定区
    DISCARD_PILE(5, "弃牌堆"),  // 弃牌堆
    REMOVED(6, "移出游戏");        // 移出游戏

    private final Integer code;
    private final String description;

    CardStatus(Integer code, String description) {
        this.code = code;
        this.description = description;
    }

    @JsonValue
    @Override
    public Integer getCode() { return code; }

    @Override
    public String getDescription() { return description; }

    @JsonCreator
    public static CardStatus of(Integer code) {
        return EnumUtils.fromCode(CardStatus.class, code);
    }
}
