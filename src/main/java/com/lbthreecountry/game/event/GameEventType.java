package com.lbthreecountry.game.event;

import com.lbthreecountry.model.enums.impl.GamePhase;

/**
 * 游戏内置事件常量定义
 *
 * <p>这里列出框架内置的标准事件类型。
 * 只需使用字符串常量即可引用，不强制使用此类，自定义事件可直接写字符串。</p>
 *
 * <h3>命名规范</h3>
 * <pre>
 * 模块.具体事件  —  例如：
 *   GAME.START    游戏开始
 *   PHASE.CHANGE  阶段切换
 *   CARD.PLAYED   出牌
 * </pre>
 *
 * <h3>阶段事件钩子系统</h3>
 * <p>每个阶段有 4 个钩子：BEFORE（开始前）、ACTIVE（进行中）、END（结束时）、AFTER（结束后）。</p>
 * <p>使用 {@link #phaseBefore(GamePhase)}、{@link #phaseActive(GamePhase)}、
 * {@link #phaseEnd(GamePhase)}、{@link #phaseAfter(GamePhase)} 快捷生成事件类型字符串。</p>
 *
 * <h3>钩子触发顺序（以 PREPARE → JUDGE 为例）</h3>
 * <pre>
 * TURN.BEFORE（玩家回合开始前）
 *   PREPARE.BEFORE → PREPARE.ACTIVE → ... → PREPARE.END → PREPARE.AFTER
 *   JUDGE.BEFORE   → JUDGE.ACTIVE   → ... → JUDGE.END   → JUDGE.AFTER
 *   ...（DRAW / PLAY / DISCARD / END）
 * TURN.AFTER（玩家回合结束后）
 * </pre>
 */
public final class GameEventType {

    private GameEventType() {
        // 工具类，禁止实例化
    }

    // ================================================================
    //  游戏生命周期
    // ================================================================

    /** 游戏初始化完成，对局开始 */
    public static final String GAME_START = "GAME.START";

    /** 游戏结束 */
    public static final String GAME_OVER = "GAME.OVER";

    /** 游戏日志（调试用） — 前端收到后会在日志面板显示 */
    public static final String GAME_LOG = "GAME.LOG";

    /** 武将选择 — 主公从候选武将中选一个 */
    public static final String HERO_SELECT = "HERO.SELECT";

    // ================================================================
    //  回合钩子
    // ================================================================

    /** 玩家回合开始前 — 在第一个阶段（PREPARE）钩子之前触发 */
    public static final String TURN_BEFORE = "TURN.BEFORE";

    /** 玩家回合进行中 — 在 TURN_BEFORE 之后、阶段钩子之前触发 */
    public static final String TURN_ACTIVE = "TURN.ACTIVE";

    /** 玩家回合结束时 — 在最后一个阶段（END）钩子之后、TURN_AFTER 之前触发 */
    public static final String TURN_END = "TURN.END";

    /** 玩家回合结束后 — 在 TURN_END 之后、切下一玩家之前触发 */
    public static final String TURN_AFTER = "TURN.AFTER";

    /** 新回合开始（兼容旧版） */
    @Deprecated
    public static final String TURN_START = "TURN.START";

    /** 新轮次开始 */
    public static final String ROUND_CHANGE = "ROUND.CHANGE";

    // ================================================================
    //  阶段钩子生成方法
    // ================================================================

    private static final String PHASE_BEFORE_PREFIX = "PHASE.BEFORE.";
    private static final String PHASE_ACTIVE_PREFIX = "PHASE.ACTIVE.";
    private static final String PHASE_END_PREFIX    = "PHASE.END.";
    private static final String PHASE_AFTER_PREFIX  = "PHASE.AFTER.";

    /** 某阶段开始前（如 "PHASE.BEFORE.PREPARE"） */
    public static String phaseBefore(GamePhase phase) {
        return PHASE_BEFORE_PREFIX + phase.name();
    }

    /** 某阶段进行中（如 "PHASE.ACTIVE.PREPARE"） */
    public static String phaseActive(GamePhase phase) {
        return PHASE_ACTIVE_PREFIX + phase.name();
    }

    /** 某阶段结束时（如 "PHASE.END.PREPARE"） */
    public static String phaseEnd(GamePhase phase) {
        return PHASE_END_PREFIX + phase.name();
    }

    /** 某阶段结束后（如 "PHASE.AFTER.PREPARE"） */
    public static String phaseAfter(GamePhase phase) {
        return PHASE_AFTER_PREFIX + phase.name();
    }

    /** 阶段切换（兼容旧版） */
    @Deprecated
    public static final String PHASE_CHANGE = "PHASE.CHANGE";

    // ================================================================
    //  卡牌事件
    // ================================================================

    /** 玩家出牌 */
    public static final String CARD_PLAYED = "CARD.PLAYED";

    // ================================================================
    //  摸牌事件钩子
    // ================================================================

    /** 摸牌前 */
    public static final String CARD_DRAW_BEFORE = "CARD.DRAW.BEFORE";

    /** 摸牌时 */
    public static final String CARD_DRAW_ACTIVE = "CARD.DRAW.ACTIVE";

    /** 摸牌后 */
    public static final String CARD_DRAW_AFTER = "CARD.DRAW.AFTER";

    /** 摸牌（旧版） */
    @Deprecated
    public static final String CARD_DRAWN = "CARD.DRAWN";

    /** 弃牌 */
    public static final String CARD_DISCARDED = "CARD.DISCARDED";

    // ================================================================
    //  伤害事件
    // ================================================================

    /** 造成伤害前 */
    public static final String BEFORE_DAMAGE = "DAMAGE.BEFORE";

    /** 造成伤害后 */
    public static final String AFTER_DAMAGE = "DAMAGE.AFTER";

    // ================================================================
    //  玩家状态
    // ================================================================

    /** 玩家濒死 */
    public static final String PLAYER_DYING = "PLAYER.DYING";

    /** 玩家死亡 */
    public static final String PLAYER_DEAD = "PLAYER.DEAD";
}