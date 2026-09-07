package com.lbthreecountry.model.enums.impl;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.lbthreecountry.model.enums.interfaces.BaseEnum;
import com.lbthreecountry.model.enums.utils.EnumUtils;

/**
 * 游戏角色类型枚举
 */
public enum RoleType implements BaseEnum {
    LORD(1, "主公"),
    MINION(2, "忠臣"),
    REBEL(3, "反贼"),
    INTRUDER(4, "内奸");

    private final Integer code;
    private final String description;

    /**
     * 构造函数
     * @param code 状态码
     * @param description 状态描述
     */
    RoleType(Integer code, String description) {
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
    public static RoleType of(Integer code) {
        return EnumUtils.fromCode(RoleType.class, code);
    }
}