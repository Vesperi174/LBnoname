package com.lbthreecountry.model.enums.impl;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.lbthreecountry.model.enums.interfaces.BaseEnum;
import com.lbthreecountry.model.enums.utils.EnumUtils;

/**
 * 游戏阶段枚举
 */
public enum GamePhase implements BaseEnum {
    PREPARE(1, "准备阶段"),
    JUDGE(2, "判定阶段"),
    DRAW(3, "摸牌阶段"),
    PLAY(4, "出牌阶段"),
    DISCARD(5, "弃牌阶段"),
    END(6, "结束阶段");

    private final Integer code;
    private final String description;

    GamePhase(Integer code, String description) {
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
    public static GamePhase fromCode(Integer code) {
        return EnumUtils.fromCode(GamePhase.class, code);
    }
}
