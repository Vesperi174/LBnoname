package com.lbthreecountry.game.event;

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

    // ================================================================
    //  回合事件
    // ================================================================

    /** 新回合开始 */
    public static final String TURN_START = "TURN.START";

    /** 回合结束 */
    public static final String TURN_END = "TURN.END";

    /** 新轮次开始 */
    public static final String ROUND_CHANGE = "ROUND.CHANGE";

    // ================================================================
    //  阶段事件
    // ================================================================

    /** 阶段切换 */
    public static final String PHASE_CHANGE = "PHASE.CHANGE";

    // ================================================================
    //  卡牌事件
    // ================================================================

    /** 玩家出牌 */
    public static final String CARD_PLAYED = "CARD.PLAYED";

    /** 摸牌 */
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