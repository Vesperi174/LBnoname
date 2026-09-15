package com.lbthreecountry.model.enums.impl;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.lbthreecountry.model.enums.interfaces.BaseEnum;
import com.lbthreecountry.model.enums.utils.EnumUtils;

public enum Gender implements BaseEnum {
    MALE(1, "男"),
    FEMALE(2, "女"),
    UNKNOWN(0, "未知");
    private final Integer code;
    private final String description;

    Gender(Integer code, String description) {
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
    public static Gender fromCode(Integer code) {
        return EnumUtils.fromCode(Gender.class, code);
    }

}
