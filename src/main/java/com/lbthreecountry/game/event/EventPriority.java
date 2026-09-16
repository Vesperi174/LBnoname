package com.lbthreecountry.game.event;

/**
 * 事件监听器优先级常量
 *
 * <p>所有 {@link EventBus#register(String, int, EventListener) 注册监听器} 时，
 * 应使用此类定义的优先级常量，而不是硬编码的魔法数字。</p>
 *
 * <h3>优先级层级（数值越小优先级越高）</h3>
 * <pre>
 *   FRAMEWORK  = -100 框架基础设施（技能管理器、全局钩子注册等）
 *   SKILL      = 0    技能触发（武将技能/国战技能等）
 *   EQUIP_CARD = 100  装备与卡牌效果（武器、防具、锦囊等）
 *   ENGINE     = 200  游戏引擎（回合调度、阶段切换、框架逻辑等）
 * </pre>
 *
 * <p>示例：</p>
 * <pre>{@code
 * // 正确 — 使用常量
 * eventBus.register("DAMAGE.BEFORE", EventPriority.SKILL, this::onDamageBefore);
 * eventBus.register("ROUND.START", EventPriority.ENGINE, this::onRoundStart);
 *
 * // 错误 — 使用魔法数字
 * eventBus.register("DAMAGE.BEFORE", 0, this::onDamageBefore);
 * }</pre>
 *
 * <h3>同一层级的优先级微调</h3>
 * <p>如需在同一层级内指定执行顺序，可在基础优先级上 +N：</p>
 * <pre>{@code
 * // 两个技能监听同一事件，技能 B 在技能 A 之后执行
 * eventBus.register("DAMAGE.BEFORE", EventPriority.SKILL,       skillA);  // 0
 * eventBus.register("DAMAGE.BEFORE", EventPriority.SKILL + 10,  skillB);  // 10
 * }</pre>
 *
 * <h3>技能管理器使用框架</h3>
 * <p>{@link com.lbthreecountry.game.skill.SkillManager SkillManager} 在
 * {@code BATTLE_START} 事件中使用 {@link #FRAMEWORK} 优先级，
 * 确保在所有技能监听器之前完成技能注册。</p>
 * <p>具体技能监听器则使用 {@link #SKILL} + 玩家座位号 的优先级，
 * 保证按座位顺序执行：座位 0 的技能先于座位 1 的技能。</p>
 */
public final class EventPriority {

    private EventPriority() {
        // 工具类，禁止实例化
    }

    // ================================================================
    //  四级优先级
    // ================================================================

    /**
     * 框架基础设施 — 最高优先级
     *
     * <p>适用于：</p>
     * <ul>
     *   <li>技能管理器在 {@code BATTLE_START} 时注册所有武将技能</li>
     *   <li>需要早于任何技能/卡牌/引擎执行的基础设定</li>
     * </ul>
     */
    public static final int FRAMEWORK = -100;

    /**
     * 技能触发 — 最高游戏逻辑优先级
     *
     * <p>具体技能监听器使用 {@code SKILL + seatIndex} 作为优先级，
     * 保证先座次的技能在同级钩子中优先执行。</p>
     *
     * <p>适用于：</p>
     * <ul>
     *   <li>武将主动/被动技能</li>
     *   <li>国战技能</li>
     *   <li>觉醒技、限定技等</li>
     * </ul>
     */
    public static final int SKILL = 0;

    /**
     * 装备与卡牌效果 — 中等优先级
     *
     * <p>适用于：</p>
     * <ul>
     *   <li>武器效果（青龙偃月刀、贯石斧等杀特效）</li>
     *   <li>防具效果（八卦阵、仁王盾等）</li>
     *   <li>坐骑效果（+1马、-1马影响攻击距离）</li>
     *   <li>锦囊牌效果（过河拆桥、无中生有等）</li>
     * </ul>
     */
    public static final int EQUIP_CARD = 100;

    /**
     * 游戏引擎 — 最低优先级
     *
     * <p>适用于：</p>
     * <ul>
     *   <li>回合调度（nextTurn）</li>
     *   <li>阶段切换（PhaseManager）</li>
     *   <li>轮次状态机（RoundStateMachine）</li>
     *   <li>基础游戏框架逻辑</li>
     * </ul>
     */
    public static final int ENGINE = 200;
}