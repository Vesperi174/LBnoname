package com.lbthreecountry.entity;

import com.lbthreecountry.model.enums.impl.Attribute;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 伤害模型 — 代表一次伤害事件
 * 每次造成伤害时创建一个 Damage 对象，沿技能链传递，最终结算
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Damage {
    private Long sourceId;            // 伤害来源（武将/卡牌/技能 的ID）
    private Long targetId;            // 受伤目标（玩家ID）
    private int amount;               // 伤害值（基础伤害值，可被技能修改）
    private Attribute attribute;      // 伤害属性（null=普通, FIRE=火, THUNDER=雷, ICE=冰）
    private boolean isSkillDamage;    // 是否技能伤害（区别于"杀"造成的伤害）
    private boolean isPrevented;      // 是否已被防止
    private int modifiedAmount;       // 实际最终伤害值（经过技能增减后的值）
}