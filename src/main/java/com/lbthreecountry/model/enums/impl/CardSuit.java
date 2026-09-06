package com.lbthreecountry.model.enums.impl;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.lbthreecountry.model.enums.interfaces.BaseEnum;
import com.lbthreecountry.model.enums.utils.EnumUtils;

/**
 * 游戏牌花色枚举
 */
public enum CardSuit implements BaseEnum {
    HEARTS(1, "红桃"),
    DIAMONDS(2, "方块"),
    CLUBS(3, "梅花"),
    SPADES(4, "黑桃");

    private final Integer code;
    private final String description;

    CardSuit(Integer code, String description) {
        this.code = code;
        this.description = description;
    }

    @JsonValue
    @Override
    public Integer getCode() {
        return code;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @JsonCreator
    public static CardSuit of(Integer code) {
        return EnumUtils.fromCode(CardSuit.class, code);
    }
}