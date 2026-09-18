package com.lbthreecountry.model.card.def;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 卡牌使用规则 — 定义卡牌在何时、以何种方式使用
 * <p>
 * 所有字段都有默认值，JSON 中可省略使用默认值的字段。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CardRules {

    /** 可用阶段：PLAY（出牌阶段）/ RESPOND（响应阶段）/ ANY（任意） */
    @Builder.Default
    private String playablePhase = "PLAY";

    /** 每回合最多使用次数（默认 999=无限制） */
    @Builder.Default
    private int maxPerTurn = 999;

    /** 需要选择的目标数（0=无目标，如桃园结义） */
    @Builder.Default
    private int targetCount = 0;

    /** 目标类型：ENEMY / SELF / ALLY / ANY */
    @Builder.Default
    private String targetType = "ANY";

    /** 距离限制（-1=走攻击范围逻辑，0=无距离限制） */
    @Builder.Default
    private int rangeLimit = -1;

    /** 此卡可以响应哪些卡牌的子类型（如"闪"响应"杀"） */
    @Builder.Default
    private List<String> canRespondTo = List.of();

    /** 装备槽位（仅装备牌使用）：WEAPON / ARMOR / MOUNT_PLUS / MOUNT_MINUS / TREASURE */
    private String equipSlot;

    /** 攻击范围（仅武器牌使用） */
    @Builder.Default
    private int attackRange = 1;

    // ================================================================
    //  新增字段（JSON 配置驱动）
    // ================================================================

    /** 可使用次数，null=不限次数（覆盖 maxPerTurn 逻辑） */
    @Builder.Default
    private Integer maxUseCount = null;

    /** 出牌阶段是否不限次数（默认 true） */
    @Builder.Default
    private Boolean unlimitedInPlayPhase = true;

    /** 是否可主动使用（默认 true） */
    @Builder.Default
    private Boolean canActiveUse = true;

    /** 目标筛选类型，null=无目标（如桃园结义无目标） */
    private String filterType;
}