package com.lbthreecountry.model.card.def;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 卡牌定义 — 从 JSON 文件映射的完整卡牌数据
 * <p>
 * 对应 {@code resources/cards/*.json} 中每张卡牌的 JSON 对象。
 * 包含卡牌属性、副本列表、使用规则、效果组件引用等全部信息。
 * </p>
 *
 * <p>
 * <b>效果组件机制：</b>卡牌效果不在此类中直接定义，而是通过
 * {@code components} 字段引用外部 Java 组件 ID。组件在
 * {@link com.lbthreecountry.game.card.EffectManager} 中注册，
 * 通过 {@link com.lbthreecountry.game.card.EffectEngine} 执行。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CardDef {

    /** 唯一标识符（英文，如 "sha"、"tao"、"juedou"） */
    private String id;

    /** 显示名称（如"杀"、"桃"） */
    private String name;

    /** 卡牌描述文字 */
    private String description;

    /** 卡牌大类：BASIC / STRATEGY / EQUIPMENT（由 CardRegistry 注册） */
    private String type;

    /** 卡牌子类型（由 CardRegistry 注册） */
    private String subType;

    /** 属性：FIRE / THUNDER / ICE（由 CardRegistry 注册） */
    private String attribute;

    /** 牌堆中的副本列表（花色+点数，由 JSON 提供） */
    private List<CardCopy> copies;

    /** 使用规则 */
    private CardRules rules;

    /** 效果组件 ID 映射 — 按事件类型分类的组件 ID 列表 */
    private Map<String, List<String>> components;

    // ================================================================
    //  便捷方法：获取各事件的组件 ID 列表
    // ================================================================

    /** 主动使用此牌时的效果组件 ID 列表 */
    public List<String> getOnUseComponents() {
        return components != null ? components.get("onUse") : null;
    }

    /** 作为响应打出时的效果组件 ID 列表 */
    public List<String> getOnRespondComponents() {
        return components != null ? components.get("onRespond") : null;
    }

    /** 装备到装备区时的效果组件 ID 列表 */
    public List<String> getOnEquipComponents() {
        return components != null ? components.get("onEquip") : null;
    }

    /** 从装备区卸下时的效果组件 ID 列表 */
    public List<String> getOnUnequipComponents() {
        return components != null ? components.get("onUnequip") : null;
    }

    /** 判定牌翻开时的效果组件 ID 列表 */
    public List<String> getOnJudgeComponents() {
        return components != null ? components.get("onJudge") : null;
    }
}