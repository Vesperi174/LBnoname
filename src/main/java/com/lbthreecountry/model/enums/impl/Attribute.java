package com.lbthreecountry.model.enums.impl;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.lbthreecountry.model.enums.interfaces.BaseEnum;
import com.lbthreecountry.model.enums.utils.EnumUtils;

/**
 * 伤害属性枚举
 */
public enum Attribute implements BaseEnum {
    ICE(1, "冰"),
    FIRE(2, "火"),
    THUNDER(3, "雷");

    private final Integer code;
    private final String description;

    Attribute(Integer code, String description) {
        this.code = code;
        this.description = description;
    }

    @JsonValue
    @Override
    public Integer getCode() { return code; }

    @Override
    public String getDescription() { return description; }

    @JsonCreator
    public static Attribute of(Integer code) {
        return EnumUtils.fromCode(Attribute.class, code);
    }
}
