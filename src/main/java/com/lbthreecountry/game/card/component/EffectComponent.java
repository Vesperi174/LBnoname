package com.lbthreecountry.game.card.component;

import com.lbthreecountry.game.card.EffectContext;

/**
 * 效果组件接口 — 所有卡牌效果组件的基类
 * <p>
 * 每个卡牌效果是一个独立的 Java 组件，实现此接口。
 * 组件通过 {@link #getId()} 唯一标识，在 JSON 卡牌定义中通过 ID 引用。
 * </p>
 *
 * <p>
 * <b>设计原则：</b>组件之间互相独立，不依赖具体卡牌，只操作
 * {@link EffectContext} 提供的游戏状态。新增效果只需新建实现类
 * 并注册到 {@link com.lbthreecountry.game.card.EffectManager}。
 * </p>
 *
 * <h3>示例</h3>
 * <pre>{@code
 * // 杀的效果组件
 * @Component
 * public class ShaEffect implements EffectComponent {
 *     public String getId() { return "sha_effect"; }
 *     public void onUse(EffectContext ctx) {
 *         // 造成伤害的逻辑
 *     }
 * }
 * }</pre>
 */
public interface EffectComponent {

    /**
     * 组件唯一标识（对应 JSON 中 components 数组的值）
     * <p>例如："sha_effect"、"shan_effect"、"tao_effect"</p>
     */
    String getId();

    /**
     * 卡牌被主动使用时的效果
     * <p>例如：杀对目标造成伤害、桃回复体力、锦囊牌执行策略</p>
     *
     * @param ctx 执行上下文，包含对局状态、使用者、目标等信息
     */
    void onUse(EffectContext ctx);

    /**
     * 卡牌作为响应打出时的效果
     * <p>例如：闪抵消杀、无懈可击抵消锦囊</p>
     * <p>默认空实现，需要响应行为的卡牌重写此方法</p>
     */
    default void onRespond(EffectContext ctx) {
        // 默认空实现
    }

    /**
     * 装备到装备区时的效果
     * <p>例如：武器增加攻击距离、防具提供防御效果</p>
     */
    default void onEquip(EffectContext ctx) {
        // 默认空实现
    }

    /**
     * 从装备区卸下时的效果
     * <p>例如：移除武器增加的攻击距离</p>
     */
    default void onUnequip(EffectContext ctx) {
        // 默认空实现
    }

    /**
     * 判定时的效果（仅判定牌使用）
     */
    default void onJudge(EffectContext ctx) {
        // 默认空实现
    }
}