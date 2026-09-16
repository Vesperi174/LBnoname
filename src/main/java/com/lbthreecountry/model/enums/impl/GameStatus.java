package com.lbthreecountry.model.enums.impl;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.lbthreecountry.model.enums.interfaces.BaseEnum;
import com.lbthreecountry.model.enums.utils.EnumUtils;

/**
 * 游戏状态枚举
 */
public enum GameStatus implements BaseEnum {
    INIT(0, "初始化"),
    HERO_SELECT(1, "武将选择"),
    PLAYING(2, "进行中"),
    FINISHED(3, "已结束");

    private final Integer code;
    private final String description;

    GameStatus(Integer code, String description) {
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
    public static GameStatus fromCode(Integer code) {
        return EnumUtils.fromCode(GameStatus.class, code);
    }
}