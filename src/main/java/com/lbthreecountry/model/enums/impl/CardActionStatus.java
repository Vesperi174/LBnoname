package com.lbthreecountry.model.enums.impl;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.lbthreecountry.model.enums.interfaces.BaseEnum;
import com.lbthreecountry.model.enums.utils.EnumUtils;

/**
 * 卡牌动作状态 — 告诉前端此牌可否点击和使用
 *
 * <p>每张卡牌在 {@code MY_HAND} 或 {@code CHECK_CARDS} 响应中携带此状态，前端据此渲染：</p>
 * <ul>
 *   <li>{@link #PLAYABLE PLAYABLE} — 高亮显示，可点击使用（绿色/金色边框）</li>
 *   <li>{@link #NOT_PLAYABLE NOT_PLAYABLE} — 灰显，可点击但不可用（鼠标悬停显示原因）</li>
 *   <li>{@link #NOT_CLICKABLE NOT_CLICKABLE} — 完全不可点击（透明/锁定图标）</li>
 * </ul>
 */
public enum CardActionStatus implements BaseEnum {

    /**
     * ✅ 可点击，可用
     * <p>当前玩家、当前阶段、未超过使用次数限制、有合法目标</p>
     */
    PLAYABLE(1, "可点击，可用"),

    /**
     * ⚠️ 可点击，但当前不可用
     * <p>例如：本回合已出杀、无合法目标、超出距离限制</p>
     */
    NOT_PLAYABLE(2, "可点击，不可用"),

    /**
     * 🔒 不可点击
     * <p>例如：不是当前回合、不是出牌阶段、不是自己的手牌、游戏未开始</p>
     */
    NOT_CLICKABLE(3, "不可点击");

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