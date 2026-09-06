package com.lbthreecountry.model.enums.impl;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.lbthreecountry.model.enums.interfaces.BaseEnum;
import com.lbthreecountry.model.enums.utils.EnumUtils;

// 势力类型枚举
public enum KingdomType implements BaseEnum {
    EUD(1,"艾欧尼亚"),
    NOK(2,"诺克萨斯"),
    FRE(3,"弗雷尔卓德"),
    RUN(4,"符文大陆"),
    PET(5,"皮尔特沃夫"),
    DRA(6,"暗影岛"),
    DEL(7,"班德尔城"),
    GEM(8,"巨神锋"),
    DMA(9,"德玛西亚"),
    JOR(10,"比尔吉沃特"),
    ETU(11,"以绪塔尔"),
    AN(12,"祖安");

    private final Integer code;
    private final String description;

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