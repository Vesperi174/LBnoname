package com.lbthreecountry.model.effect.impl;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 治疗模型 — 代表一次治疗事件
 * 每次造成治疗时创建一个 Heal 对象，沿技能链传递，最终结算
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Heal {
    /**
     * 治疗来源（武将/卡牌/技能 的ID）
     */
    private Long sourceId;
    /**
     * 受疗目标（玩家ID）
     */
    private Long targetId;
    /**
     * 受疗目标（玩家ID）
     * 治疗值（基础治疗值，可被技能修改）
     */
    private int amount;
    /**
     * 是否技能治疗
     */
    private boolean isSkillHeal;
    /**
     * 是否已被防止
     */
    private boolean isPrevented;
    /**
     * 实际最终治疗值（经过技能增减后的值）
     */
    private int modifiedAmount;
}
