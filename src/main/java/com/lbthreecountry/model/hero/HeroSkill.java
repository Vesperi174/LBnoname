package com.lbthreecountry.model.hero;

import com.lbthreecountry.model.enums.impl.GamePhase;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 武将技能定义
 *
 * <p>描述一个武将技能的名称、描述、类型等信息。
 * 技能的具体逻辑由对应的 {@link BaseHero} 子类通过事件机制实现。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HeroSkill {

    private String skillId;
    private String skillName;
    private String description;


    @Builder.Default
    private boolean canUse = true;
    /**
     * 是否为机制技
     */
    @Builder.Default
    private boolean isMechanism = false;
    /**
     * 是否为限定技
     */
    @Builder.Default
    private boolean isLimited = false;
    /**
     * 是否为锁定技
     */
    @Builder.Default
    private boolean isLocked = false;

    @Builder.Default
    private Integer useCount = null;
    @Builder.Default
    private Integer useCountInPrepare = null;
    @Builder.Default
    private Integer useCountInJudge = null;
    @Builder.Default
    private Integer useCountInDraw = null;
    @Builder.Default
    private Integer useCountInPlay = null;
    @Builder.Default
    private Integer useCountInDiscard = null;
    @Builder.Default
    private Integer useCountInEnd = null;
}