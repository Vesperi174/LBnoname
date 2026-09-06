package com.lbthreecountry.model.enums.impl;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.lbthreecountry.model.enums.interfaces.BaseEnum;
import com.lbthreecountry.model.enums.utils.EnumUtils;

// 房间状态枚举
public enum RoomStatus implements BaseEnum {
    WAITING(1,"等待中"),
    IN_PROGRESS(2,"进行中");

    private final Integer code;
    private final String description;

    RoomStatus(Integer code, String description) {
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
    public static RoomStatus fromCode(Integer code) {
        return EnumUtils.fromCode(RoomStatus.class, code);
    }
}
