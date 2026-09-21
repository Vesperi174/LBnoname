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
 *   CARD.PLAY   打出牌
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

    // ================================================================
    //  轮次钩子（由 RoundStateMachine 发布）
    // ================================================================

    /**
     * 轮次推进 — 进入 ROUND_START 之前由 RoundStateMachine 发布
     *
     * <p>此钩子仅由 {@code RoundStateMachine} 自己监听（ENGINE 优先级），
     * 用于将当前轮次 +1。拆成独立钩子的目的是让所有状态变化都走事件驱动，
     * 便于后续扩展（如监听轮次递增做日志、统计等）。</p>
     */
    public static final String ROUND_ROLL = "ROUND.ROLL";

    /**
     * 轮次开始 — 第 x 轮开始时由 RoundStateMachine 发布
     * <p>监听此事件可在每轮开始时执行逻辑（如重置全局标记、触发每轮一次的技能）。</p>
     */
    public static final String ROUND_START = "ROUND.START";

    /**
     * 轮次结束 — 第 x 轮结束时由 RoundStateMachine 发布
     * <p>监听此事件可在每轮结束时执行逻辑（如结算每轮一次的技能效果）。</p>
     */
    public static final String ROUND_END = "ROUND.END";

    /**
     * 轮次·回合序列开始 — 由 RoundStateMachine 在 ROUND.START 钩子走完后进入
     * {@link com.lbthreecountry.game.state.RoundStateMachine.RoundPhase#PLAYER_TURN} 状态时发布。
     * <p>监听此事件可在本轮所有玩家回合开始前执行逻辑（如初始化回合计数器、重置回合标记）。
     * 此事件发布后，外部驱动应开始调度第 1 个玩家的回合。</p>
     */
    public static final String ROUND_TURN_START = "ROUND.TURN_START";

    // ================================================================
    //  距离
    // ================================================================

    /**
     * 距离计算 — 由 {@code DistanceManager} 在计算两名玩家距离时发布
     *
     * <p>监听者可修改事件数据中的 {@code distance} 字段来影响最终距离值。
     * 事件数据格式：</p>
     * <pre>{@code
     * {
     *   "fromId": "<源玩家ID>",
     *   "toId":   "<目标玩家ID>",
     *   "seatDistance": 2,       // 座次原始距离（不可修改）
     *   "distance": 2            // 最终距离（监听器可修改）
     * }
     * }</pre>
     */
    public static final String DISTANCE_CALC = "DISTANCE.CALC";

    // ================================================================
    //  攻击距离
    // ================================================================

    /**
     * 获取攻击距离 — 由 {@code AttackRangeEvent} 在计算玩家攻击距离时发布
     *
     * <p>监听者可修改事件数据中的 {@code attackRange} 字段来影响最终攻击距离值。</p>
     */
    public static final String GET_ATTACK_RANGE = "ATTACK_RANGE.GET";

    /** 攻击距离修正 — 由 {@code AttackRangeEvent} 在 {@code GET_ATTACK_RANGE} 回调中发布
     *
     * <p>装备、技能等监听器可监听此钩子，修改事件数据中的 {@code attackRange} 来修正攻击距离。</p>
     */
    public static final String ATTACK_RANGE_MODIFY = "ATTACK_RANGE.MODIFY";

    // ================================================================
    //  手牌上限
    //
    //  发布 HAND_LIMIT.CALC 触发事件（需附带 playerId 数据），
    //  由 HandLimitEvent 组件监听到后计算手牌上限，并抛出修正钩子。
    //    HAND_LIMIT.CALC       — 触发计算手牌上限
    //      └─ HAND_LIMIT.MODIFY  — 修正钩子，监听器可修改 limit 值
    //
    //  数据字段：
    //    playerId    — 玩家 ID（必填）
    //    baseLimit   — 基础手牌上限（由 HandLimitEvent 填入，默认 = 当前体力值）
    //    limit       — 最终手牌上限（监听器可在 MODIFY 钩子中修改此值）
    // ================================================================

    /** 获取手牌上限 — 发布此事件触发手牌上限计算（由 HandLimitEvent @Component 监听） */
    public static final String HAND_LIMIT_CALC = "HAND_LIMIT.CALC";

    /** 手牌上限修正钩子 — 监听器可修改 limit 字段 */
    public static final String HAND_LIMIT_MODIFY = "HAND_LIMIT.MODIFY";

    // ================================================================
    //  获取目标
    // ================================================================

    /**
     * 获取目标 — 由 {@code GetTargetEvent} 在处理获取目标逻辑时发布
     */
    public static final String GET_TARGET = "TARGET.GET";

    /**
     * 即将成为目标 — 由 {@code GetTargetEvent} 在筛选出目标后，逐个目标发布
     *
     * <p>事件数据包含：initiatorId（发起者）、playerId（成为目标的玩家）、
     * sourceType、sourceName。</p>
     */
    public static final String BECOME_TARGET = "TARGET.BECOME";

    // ================================================================
    //  伤害
    // ================================================================

    /**
     * 造成伤害 — 由 {@code DamageEvent} 在处理造成伤害逻辑时发布
     */
    public static final String DAMAGE_CAUSE = "DAMAGE.CAUSE";

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

    /** 玩家打出牌触发事件 — 由 PlayCardEvent 监听，触发打出牌生命周期 */
    public static final String CARD_PLAY = "CARD.PLAY";

    /** 打出前钩子 — 附带本次打出信息，可修改或取消 */
    public static final String CARD_PLAY_BEFORE = "CARD.PLAY.BEFORE";

    /** 打出时钩子 — 附带本次打出信息，可修改或取消 */
    public static final String CARD_PLAY_ACTIVE = "CARD.PLAY.ACTIVE";

    /** 打出后钩子 — 附带本次打出信息，仅通知 */
    public static final String CARD_PLAY_AFTER = "CARD.PLAY.AFTER";

    // ================================================================
    //  摸牌事件 — 完整生命周期
    //
    //  发布 CARD.DRAW 触发事件（需附带 driver/playerId/count 数据），
    //  由 DrawCardEvent 组件监听到后自动按以下顺序发布子钩子：
    //    CARD.DRAW.BEFORE（摸牌开始前，可修改）
    //    CARD.DRAW.ACTIVE（摸牌开始时，可修改）
    //    CARD.DRAW.AFTER（摸牌结束后，可修改）
    // ================================================================

    /** 摸牌触发事件 — 发布此事件即可触发摸牌生命周期（由 DrawCardEvent @Component 监听） */
    public static final String CARD_DRAW = "CARD.DRAW";

    /** 摸牌开始前钩子 — 附带本次摸牌信息，可被其他事件监听、调用、修改 */
    public static final String CARD_DRAW_BEFORE = "CARD.DRAW.BEFORE";

    /** 摸牌开始时钩子 — 附带本次摸牌信息，可被其他事件监听、调用、修改 */
    public static final String CARD_DRAW_ACTIVE = "CARD.DRAW.ACTIVE";

    /** 摸牌结束后钩子 — 附带本次摸牌信息，可被其他事件监听、调用、修改 */
    public static final String CARD_DRAW_AFTER = "CARD.DRAW.AFTER";

    /** 摸牌（旧版） */
    @Deprecated
    public static final String CARD_DRAWN = "CARD.DRAWN";

    // ================================================================
    //  初始手牌分发事件
    //
    //  在武将选择完成（HERO_ASSIGNMENT 广播）之后触发，
    //  此事件钩子包含"谁，摸多少牌"的信息，可被监听、修改。
    //  钩子处理完毕后直接执行摸牌行为（绕过 DrawCardEvent 生命周期，
    //  不触发 CARD.DRAW.BEFORE/ACTIVE/AFTER，此为例外）。
    // ================================================================

    /** 分发初始手牌 — 在武将选择完成后、第一回合开始前触发 */
    public static final String CARD_INITIAL_DRAW = "CARD.INITIAL_DRAW";

    /** 摸牌后检测 — CardManager.draw() 摸完牌后发布，附带 player 信息 */
    public static final String CARD_DRAW_CHECK = "CARD.DRAW.CHECK";

    /** 战斗开始 — 初始手牌分发完毕、全玩家状态就绪后触发 */
    public static final String BATTLE_START = "BATTLE.START";

    // ================================================================
    //  弃牌事件 — 完整生命周期
    //
    //  发布 CARD.DISCARD 触发事件（需附带 playerId/count 数据），
    //  由 DiscardEvent 组件监听到后自动按以下顺序发布子钩子：
    //    CARD.DISCARD.BEFORE（弃牌开始前，可修改 count / 可取消）
    //    CARD.DISCARD.ACTIVE（弃牌进行中，可修改 count / 可取消）
    //    CARD.DISCARD.AFTER（弃牌结束后，仅通知）
    // ================================================================

    /** 弃牌触发事件 — 发布此事件即可触发弃牌生命周期（由 DiscardEvent @Component 监听） */
    public static final String CARD_DISCARD = "CARD.DISCARD";

    /** 弃牌开始前钩子 — 附带本次弃牌信息，可被其他事件监听、调用、修改 */
    public static final String CARD_DISCARD_BEFORE = "CARD.DISCARD.BEFORE";

    /** 弃牌进行中钩子 — 附带本次弃牌信息，可被其他事件监听、调用、修改 */
    public static final String CARD_DISCARD_ACTIVE = "CARD.DISCARD.ACTIVE";

    /** 弃牌结束后钩子 — 附带本次弃牌信息，仅通知 */
    public static final String CARD_DISCARD_AFTER = "CARD.DISCARD.AFTER";

    /** 弃牌（旧版） */
    @Deprecated
    public static final String CARD_DISCARDED = "CARD.DISCARDED";

    // ================================================================
    //  移牌事件 — 完整生命周期
    //
    //  发布 CARD.MOVE 触发事件（需附带 cards/destination 数据），
    //  由 MoveCardEvent 组件监听到后自动按以下顺序发布子钩子：
    //    CARD.MOVE.BEFORE（移牌前，可修改数据 / 可取消）
    //    CARD.MOVE.AFTER（移牌后，可修改数据）
    // ================================================================

    /** 移牌触发事件 — 发布此事件将牌移入指定区域（由 MoveCardEvent @Component 监听） */
    public static final String CARD_MOVE = "CARD.MOVE";

    /** 移牌前钩子 — 附带本次移牌信息，可修改或取消 */
    public static final String CARD_MOVE_BEFORE = "CARD.MOVE.BEFORE";

    /** 移牌后钩子 — 附带本次移牌信息，可修改 */
    public static final String CARD_MOVE_AFTER = "CARD.MOVE.AFTER";

    // ================================================================
    //  使用牌事件 — 完整生命周期
    //
    //  发布 CARD.USE 触发事件（需附带 useplayer/targetplayer/card 数据），
    //  由 CardUseEffectEvent 组件监听到后自动按以下顺序发布子钩子：
    //    CARD.USE.BEFORE   — 使用牌前（可修改 / 可取消）
    //    CARD.USE.ACTIVE   — 使用牌时（可修改 / 可取消）
    //    CARD.USE.EFFECT   — 执行牌效果
    //    CARD.USE.AFTER    — 使用牌后（仅通知）
    //    CARD.MOVE         — 移入弃牌堆（由 MoveCardEvent 处理）
    //
    //    数据字段：
    //      useplayer    — 使用牌的玩家
    //      targetplayer — 目标玩家
    //      card         — 使用的卡牌
    // ================================================================

    /** 使用牌触发事件 — 发布此事件触发使用牌生命周期（由 CardUseEffectEvent @Component 监听） */
    public static final String CARD_USE = "CARD.USE";

    /** 使用牌前钩子 — 附带本次使用牌信息，可修改或取消 */
    public static final String CARD_USE_BEFORE = "CARD.USE.BEFORE";

    /** 使用牌时钩子 — 附带本次使用牌信息，可修改或取消 */
    public static final String CARD_USE_ACTIVE = "CARD.USE.ACTIVE";

    /** 执行牌效果钩子 — 附带本次使用牌信息，执行卡牌效果 */
    public static final String CARD_USE_EFFECT_HOOK = "CARD.USE.EFFECT";

    /** 使用牌后钩子 — 附带本次使用牌信息，仅通知 */
    public static final String CARD_USE_AFTER = "CARD.USE.AFTER";

    // ================================================================
    //  执行牌效果事件
    //
    //  发布 CARD.USE_EFFECT 触发指定卡牌的效果。
    //    player  — 效果执行者（谁执行）
    //    card   — 效果来源牌（执行什么牌）
    //    target — 效果目标（对谁执行，可为 null / 单个 / 集合）
    // ================================================================

    /** 执行牌效果 — 发布此事件触发指定卡牌的效果 */
    public static final String CARD_USE_EFFECT = "CARD.USE_EFFECT";

    // ================================================================
    //  不限次数检查事件
    //
    //  发布 CARD.UNLIMITED_CHECK 触发检查（需附带 playerId/cardDefId 数据），
    //  由 CardUnlimitedCheckEvent 组件监听到后处理，监听器可通过修改 unlimited 字段
    //  来影响最终结果。
    //
    //    playerId  — 检查的玩家 ID
    //    cardDefId — 卡牌定义 ID（检查哪张牌）
    //    unlimited — boolean，是否不限次数（默认 false，监听器可修改为 true）
    // ================================================================

    /** 获取卡牌是否不限次数 — 发布此事件触发不限次数检查（由 CardUnlimitedCheckEvent @Component 监听） */
    public static final String CARD_UNLIMITED_CHECK = "CARD.UNLIMITED_CHECK";

    /** 不限次数修正钩子 — 监听器可修改 unlimited 字段 */
    public static final String CARD_UNLIMITED_MODIFY = "CARD.UNLIMITED.MODIFY";

    // ================================================================
    //  已使用次数事件
    //
    //  发布 CARD.USED_COUNT 触发检查（需附带 player/card 数据），
    //  由 CardUsedCountEvent 组件监听到后自动按以下流程执行：
    //    CARD.USED_COUNT        — 触发检查回调
    //      └─ CARD.USED_COUNT.MODIFY  — 修正钩子，监听器可修改 usedCount
    //    数据字段：
    //      player    — GamePlayer，要检查的玩家
    //      card      — CardInstance，要检查的卡牌实例
    //      usedCount — int，已使用次数（默认从 CardInstance 获取，监听器可修改）
    // ================================================================

    /** 获取卡牌已使用次数 — 发布此事件触发查询（由 CardUsedCountEvent @Component 监听） */
    public static final String CARD_USED_COUNT = "CARD.USED_COUNT";

    /** 已使用次数修正钩子 — 监听器可修改 usedCount 字段 */
    public static final String CARD_USED_COUNT_MODIFY = "CARD.USED_COUNT.MODIFY";

    // ================================================================
    //  可使用次数事件
    //
    //  发布 CARD.AVAILABLE_COUNT 触发检查（需附带 player/card 数据），
    //  由 CardAvailableCountEvent 组件监听到后自动按以下流程执行：
    //    CARD.AVAILABLE_COUNT        — 触发检查回调
    //      └─ CARD.AVAILABLE_COUNT.MODIFY  — 修正钩子，监听器可修改 availableCount
    //    数据字段：
    //      player         — GamePlayer，要检查的玩家
    //      card           — CardInstance，要检查的卡牌实例
    //      availableCount — int，可使用次数（默认从 CardRules 推算，监听器可修改）
    // ================================================================

    /** 获取卡牌可使用次数 — 发布此事件触发查询（由 CardAvailableCountEvent @Component 监听） */
    public static final String CARD_AVAILABLE_COUNT = "CARD.AVAILABLE_COUNT";

    /** 可使用次数修正钩子 — 监听器可修改 availableCount 字段 */
    public static final String CARD_AVAILABLE_COUNT_MODIFY = "CARD.AVAILABLE_COUNT.MODIFY";

    // ================================================================
    //  伤害事件
    // ================================================================

    /** 造成伤害前 */
    public static final String BEFORE_DAMAGE = "DAMAGE.BEFORE";

    /** 造成伤害时 */
    public static final String DAMAGE_ACTIVE = "DAMAGE.ACTIVE";

    /** 造成伤害后 */
    public static final String AFTER_DAMAGE = "DAMAGE.AFTER";

    // ================================================================
    //  失去体力事件 — 完整生命周期
    //
    //  发布 LOSE.HP 触发事件（需附带 playerId/amount 数据），
    //  由 LoseHpEvent 组件监听到后自动按以下顺序发布子钩子：
    //    LOSE.HP.BEFORE（失去体力前，可修改 amount / 可取消）
    //    LOSE.HP.ACTIVE（失去体力时，可修改 amount / 可取消）
    //    LOSE.HP.AFTER（失去体力后，仅通知）
    // ================================================================

    /** 失去体力触发事件 — 发布此事件即可触发失去体力生命周期（由 LoseHpEvent @Component 监听） */
    public static final String LOSE_HP = "LOSE.HP";

    /** 失去体力前钩子 — 附带本次失去体力信息，可被其他事件监听、调用、修改 */
    public static final String BEFORE_LOSE_HP = "LOSE.HP.BEFORE";

    /** 失去体力时钩子 — 附带本次失去体力信息，可被其他事件监听、调用、修改 */
    public static final String LOSE_HP_ACTIVE = "LOSE.HP.ACTIVE";

    /** 失去体力后钩子 — 附带本次失去体力信息，仅通知 */
    public static final String AFTER_LOSE_HP = "LOSE.HP.AFTER";

    // ================================================================
    //  回复体力事件 — 完整生命周期
    //
    //  发布 RECOVER.HP 触发事件（需附带 sourceType/sourceName/targetId/amount 数据），
    //  由 RecoverHpEvent 组件监听到后自动按以下顺序发布子钩子：
    //    RECOVER.HP.BEFORE（回复体力前，可修改 amount / 可取消）
    //    RECOVER.HP.ACTIVE（回复体力时，可修改 amount / 可取消）
    //    RECOVER.HP.AFTER（回复体力后，仅通知）
    // ================================================================

    /** 回复体力触发事件 — 发布此事件即可触发回复体力生命周期（由 RecoverHpEvent @Component 监听） */
    public static final String RECOVER_HP = "RECOVER.HP";

    /** 回复体力前钩子 — 附带本次回复体力信息，可被其他事件监听、调用、修改 */
    public static final String BEFORE_RECOVER_HP = "RECOVER.HP.BEFORE";

    /** 回复体力时钩子 — 附带本次回复体力信息，可被其他事件监听、调用、修改 */
    public static final String RECOVER_HP_ACTIVE = "RECOVER.HP.ACTIVE";

    /** 回复体力后钩子 — 附带本次回复体力信息，仅通知 */
    public static final String AFTER_RECOVER_HP = "RECOVER.HP.AFTER";

    // ================================================================
    //  玩家状态
    // ================================================================

    /** 玩家濒死 */
    public static final String PLAYER_DYING = "PLAYER.DYING";

    /** 玩家死亡 */
    public static final String PLAYER_DEAD = "PLAYER.DEAD";

    // ================================================================
    //  满血检查事件
    //
    //  发布 PLAYER.FULL_HP_CHECK 触发检查（需附带 player 数据），
    //  由 PlayerFullHpEvent 组件监听到后自动按以下流程执行：
    //    PLAYER.FULL_HP_CHECK     — 触发检查回调
    //      └─ PLAYER.FULL_HP.MODIFY    — 修正钩子，监听器可修改 fullHp
    //    数据字段：
    //      player — GamePlayer，要检查的玩家
    //      fullHp — boolean，是否满血（默认由 currentHp >= maxHp 判断，监听器可修改）
    // ================================================================

    /** 判断玩家是否满血 — 发布此事件触发检查（由 PlayerFullHpEvent @Component 监听） */
    public static final String PLAYER_FULL_HP_CHECK = "PLAYER.FULL_HP_CHECK";

    /** 满血修正钩子 — 监听器可修改 fullHp 字段 */
    public static final String PLAYER_FULL_HP_MODIFY = "PLAYER.FULL_HP.MODIFY";

    // ================================================================
    //  卡牌可用性检测
    //
    //  由 CardPlayabilityChecker 在检测单张卡牌是否可用后抛出。
    //  监听此钩子的监听器可修改 tag（boolean）来影响最终的可用性判定。
    //    数据字段：
    //      player — GamePlayer，玩家
    //      card   — CardInstance，要检测的卡牌
    //      tag    — boolean，检测结果（监听器可修改）
    // ================================================================

    /** 卡牌可用性检测修正钩子 — 监听器可修改 tag 字段 */
    public static final String CARD_PLAYABILITY_MODIFY = "CARD.PLAYABILITY.MODIFY";

    // ================================================================
    //  取消事件
    // ================================================================

    /**
     * 取消动作 — 由 {@code CancelEvent} 在处理取消逻辑时发布
     *
     * <p>监听此事件可执行取消前/后的逻辑。</p>
     */
    public static final String CANCEL_ACTION = "CANCEL.ACTION";

    /** 取消动作前钩子 — 监听器可阻止取消行为 */
    public static final String CANCEL_BEFORE = "CANCEL.BEFORE";

    /** 取消进行中 — 执行取消逻辑 */
    public static final String CANCEL_ACTIVE = "CANCEL.ACTIVE";

    /** 取消动作后钩子 — 取消完成后通知 */
    public static final String CANCEL_AFTER = "CANCEL.AFTER";

    // ================================================================
    //  交互请求事件
    //
    //  发布 INTERACTION.REQUEST 触发一次前端交互（由 InteractionManager 监听），
    //    event 数据需包含交互类型、目标玩家、交互参数等信息。
    // ================================================================

    /** 交互请求 — 发布此事件启动一次前端交互（由 InteractionManager @Component 监听） */
    public static final String INTERACTION_REQUEST = "INTERACTION.REQUEST";
}