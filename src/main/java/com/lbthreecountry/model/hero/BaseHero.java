package com.lbthreecountry.model.hero;

import com.lbthreecountry.model.enums.impl.Gender;
import com.lbthreecountry.model.enums.impl.KingdomType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.List;

/**
 * 武将基类 — 所有武将需继承此类
 *
 * <p>定义了武将的核心属性（ID、名称、势力、体力、技能等）。
 * 每个具体的武将（如刘备、关羽、曹操）都应继承此类，
 * 在构造函数中填充自己的固有属性，并实现技能逻辑。</p>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * public class LiuBeiHero extends BaseHero {
 *     public LiuBeiHero() {
 *         super("liubei", "刘备", KingdomType.SHU, 4, "男",
 *               "仁德：出牌阶段，可以将任意张手牌交给其他角色，以此法给出第二张手牌时，回复1点体力。",
 *               List.of(
 *                 HeroSkill.builder()
 *                     .skillId("liubei_rende")
 *                     .skillName("仁德")
 *                     .description("出牌阶段，可以将任意张手牌交给其他角色，以此法给出第二张手牌时，回复1点体力。")
 *                     .skillType(SkillType.INITIATIVE)
 *                     .build()
 *               ));
 *     }
 * }
 * }</pre>
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public abstract class BaseHero {

    /** 武将 ID */
    private String heroId;

    /** 武将名称 */
    private String heroName;

    /** 武将称号 */
    private String heroTitle;

    /** 势力 */
    private KingdomType kingdom;

    /** 体力上限 */
    private int maxHp;

    /** 性别 */
    private Gender gender;

    /** 武将描述 */
    private String description;

    /** 技能列表 */
    private List<HeroSkill> skills;

    // ============ 便捷方法 ============

    /**
     * 获取武将的显示标题
     * <p>格式示例：【蜀】刘备（4体力）</p>
     */
    public String getDisplayName() {
        return "【" + kingdom.getDescription() + "】" + heroName + "（" + maxHp + "体力）";
    }


}