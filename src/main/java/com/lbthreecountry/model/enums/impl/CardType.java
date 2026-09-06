package com.lbthreecountry.model.enums.impl;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.lbthreecountry.model.enums.interfaces.BaseEnum;
import com.lbthreecountry.model.enums.utils.EnumUtils;

/**
 * 游戏牌类型枚举
 */
public enum CardType implements BaseEnum {

    BASIC(1, "基本牌"),
    STRATEGY(2, "锦囊牌"),
    EQUIPMENT(3, "装备牌");

    private final Integer code;
    private final String description;

    CardType(Integer code, String description) {
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
    public static CardType of(Integer code) {
        return EnumUtils.fromCode(CardType.class, code);
    }
}