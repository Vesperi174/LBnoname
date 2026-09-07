package com.lbthreecountry.model.enums.impl;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.lbthreecountry.model.enums.interfaces.BaseEnum;
import com.lbthreecountry.model.enums.utils.EnumUtils;

/**
 * 玩家状态枚举
 * 由于判断对局内玩家的状态
 */
public enum PlayerStatus implements BaseEnum {
    /**
     * 正常
     */
    ALIVE(1, "存活"),
    /**
     * 离线
     */
    DANGER(2, "濒死"),
    /**
     * 离线
     */
    DEAD(3, "死亡"),
    /**
     * 离开
     */
    LEAVE(4, "离开");

    private final Integer code;
    private final String description;

    /**
     * 构造函数
     * @param code 状态码
     * @param description 状态描述
     */
    PlayerStatus(Integer code, String description) {
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
    public static PlayerStatus fromCode(Integer code) {
        return EnumUtils.fromCode(PlayerStatus.class, code);
    }
}