package com.lbthreecountry.model.enums.impl;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.lbthreecountry.model.enums.interfaces.BaseEnum;
import com.lbthreecountry.model.enums.utils.EnumUtils;

/**
 * 卡牌动作状态 — 告诉前端此牌可否点击和使用
 *
 * <p>每张手牌携带此状态，前端据此渲染：</p>
 * <ul>
 *   <li>{@link #PLAYABLE PLAYABLE}(1) — 亮，正常渲染，此牌可以出/可以选</li>
 *   <li>{@link #NOT_SELECTABLE NOT_SELECTABLE}(2) — 暗（30% 黑色半透明遮罩），此牌不可选</li>
 * </ul>
 */
public enum CardActionStatus implements BaseEnum {

    /**
     * 可以出/可以选 — 亮（正常渲染）
     */
    PLAYABLE(1, "可出/可选"),

    /**
     * 不可以 — 暗（30% 黑色半透明遮罩）
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