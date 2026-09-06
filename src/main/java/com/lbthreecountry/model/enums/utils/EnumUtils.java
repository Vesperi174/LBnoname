package com.lbthreecountry.model.enums.utils;

import com.lbthreecountry.model.enums.interfaces.BaseEnum;

import java.util.Arrays;

public class EnumUtils {
    public static <T extends Enum<T> & BaseEnum> T fromCode(Class<T> enumClass, Integer code) {
        if (code == null) return null;
        return Arrays.stream(enumClass.getEnumConstants())
                .filter(e -> e.getCode().equals(code))
                .findFirst()
                .orElseThrow(() ->
                        new IllegalArgumentException("未知的 " + enumClass.getSimpleName() + " code: " + code));
    }
}
