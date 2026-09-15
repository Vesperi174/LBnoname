package com.lbthreecountry.model.enums.impl;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.lbthreecountry.model.enums.interfaces.BaseEnum;
import com.lbthreecountry.model.enums.utils.EnumUtils;

/**
 * 势力类型枚举
 */
public enum KingdomType implements BaseEnum {
    // ──────────────────────────────────────
    // 三国势力
    // ──────────────────────────────────────
    SHU(1, "蜀",     "#c0392b"),   // 赤红  — 蜀汉
    WEI(2, "魏",     "#2980b9"),   // 靛蓝  — 曹魏
    WU(3, "吴",     "#27ae60"),   // 碧绿  — 东吴
    QUN(4, "群",   "#95a5a6"),   // 铁灰  — 群雄并起

    // ──────────────────────────────────────
    // 英雄联盟势力
    // ──────────────────────────────────────
    EUD(101, "艾",  "#1abc9c"),  // 翠青  — 自然与平衡
    NOK(102, "诺",  "#e74c3c"),  // 腥红  — 征服与战争
    FRE(103, "弗", "#3498db"),  // 冰蓝  — 极寒冰霜
    RUN(104, "符",  "#f1c40f"),  // 璨金  — 符文魔法
    PET(105, "皮", "#e67e22"),  // 暖橙  — 科技与进步
    DRA(106, "暗",   "#8e44ad"),  // 暗紫  — 亡灵迷雾
    DEL(107, "班", "#e84393"),  // 樱粉  — 约德尔魔法
    GEM(108, "巨",   "#6c5ce7"),  // 星靛  — 天界星灵
    DMA(109, "德", "#0984e3"),  // 亮蓝  — 正义与秩序
    JOR(110, "比", "#d35400"), // 赤褐  — 海盗与港湾
    ETU(111, "以", "#00b894"),  // 墨绿  — 丛林元素魔法
    AN(112, "祖",    "#00cec9");   // 毒青  — 化学科技污染

    private final Integer code;
    private final String description;
    private final String color;

    /**
     * 构造函数
     * @param code        代码
     * @param description 中文名
     * @param color       十六进制颜色值（含 #）
     */
    KingdomType(Integer code, String description, String color) {
        this.code = code;
        this.description = description;
        this.color = color;
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

    /**
     * 获取势力对应十六进制颜色值
     */
    public String getColor() {
        return color;
    }

    @JsonCreator
    public static KingdomType of(Integer code) {
        return EnumUtils.fromCode(KingdomType.class, code);
    }
}