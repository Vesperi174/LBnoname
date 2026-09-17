package com.lbthreecountry.model.enums.impl;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.lbthreecountry.model.enums.interfaces.BaseEnum;
import com.lbthreecountry.model.enums.utils.EnumUtils;

/**
 * 卡牌动作状态 — 告诉前端此牌可否选择
 *
 * <p>每张卡牌在 {@code MY_HAND} 或 {@code CHECK_CARDS} 响应中携带此状态，前端据此渲染：</p>
 * <ul>
 *   <li>{@link #PLAYABLE PLAYABLE} — 亮（正常渲染），可点击选择</li>
 *   <li>{@link #NOT_SELECTABLE NOT_SELECTABLE} — 暗（30% 黑色半透明遮罩），不可点击</li>
 * </ul>
 */
public enum CardActionStatus implements BaseEnum {

    /**
     * ✅ 这张牌可以出/可以选
     * <p>前端效果：亮（正常渲染），可点击</p>
     */
    PLAYABLE(1, "可选"),

    /**
     * ❌ 这张牌不可选
     * <p>前端效果：暗（30% 黑色半透明遮罩），不可点击</p>
     */
    NOT_SELECTABLE(2, "不可选");

    private final Integer code;
    private final String description;

    CardActionStatus(Integer code, String description) {
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
    public static CardActionStatus of(Integer code) {
        return EnumUtils.fromCode(CardActionStatus.class, code);
    }
}