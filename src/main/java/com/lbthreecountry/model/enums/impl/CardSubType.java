package com.lbthreecountry.model.enums.impl;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.lbthreecountry.model.enums.interfaces.BaseEnum;
import com.lbthreecountry.model.enums.utils.EnumUtils;

/**
 * 卡牌子类型枚举
 */
public enum CardSubType implements BaseEnum {
    // === 基本牌 ===
    SHA(101, "杀"),
    SHAN(102, "闪"),
    TAO(103, "桃"),
    JIU(104, "酒"),

    // === 锦囊牌 ===
    JUEDOU(201, "决斗"),
    NANMAN(202, "南蛮入侵"),
    WANJIAN(203, "万箭齐发"),
    TAOYUAN(204, "桃园结义"),
    WUZHONG(205, "无中生有"),
    SHUNSHOU(206, "顺手牵羊"),
    GUOHE(207, "过河拆桥"),
    WUXIE(208, "无懈可击"),

    // === 装备牌 ===
    QINGLONG(301, "青龙偃月刀"),
    ZHANGBA(302, "丈八蛇矛"),
    FANGTIAN(303, "方天画戟"),
    RENWANG(304, "仁王盾"),
    BAGUA(305, "八卦阵"),
    // ... 更多装备
    ;

    private final Integer code;
    private final String description;

    /**
     * 构造函数
     * @param code 状态码
     * @param description 状态描述
     */
    CardSubType(Integer code, String description) {
        this.code = code;
        this.description = description;
    }

    @JsonValue
    @Override
    public Integer getCode() { return code; }

    @Override
    public String getDescription() { return description; }

    @JsonCreator
    public static CardSubType of(Integer code) {
        return EnumUtils.fromCode(CardSubType.class, code);
    }
}
