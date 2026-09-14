package com.lbthreecountry.model.enums.impl;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.lbthreecountry.model.enums.interfaces.BaseEnum;
import com.lbthreecountry.model.enums.utils.EnumUtils;

/**
 * 势力类型枚举
 */
public enum KingdomType implements BaseEnum {
    SHU(1,"蜀"),
    WEI(2,"魏"),
    WU(3,"吴"),
    QUN(4,"群"),
    EUD(101,"艾欧尼亚"),
    NOK(102,"诺克萨斯"),
    FRE(103,"弗雷尔卓德"),
    RUN(104,"符文大陆"),
    PET(105,"皮尔特沃夫"),
    DRA(106,"暗影岛"),
    DEL(107,"班德尔城"),
    GEM(108,"巨神锋"),
    DMA(109,"德玛西亚"),
    JOR(110,"比尔吉沃特"),
    ETU(111,"以绪塔尔"),
    AN(112,"祖安");

    private final Integer code;
    private final String description;

    /**
     * 构造函数
     * @param code 状态码
     * @param description 状态描述
     */
    KingdomType(Integer code, String description) {
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
    public static KingdomType of(Integer code) {
        return EnumUtils.fromCode(KingdomType.class, code);
    }
}