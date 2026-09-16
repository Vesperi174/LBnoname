package com.lbthreecountry.game.event;

import com.lbthreecountry.game.GameMatch;

/**
 * 事件监听器 — 函数式接口
 *
 * <p>通过 {@link EventBus#register(String, int, EventListener) 注册} 到事件总线，
 * 当对应类型的事件发布时被调用。</p>
 *
 * <h3>基本用法</h3>
 * <pre>{@code
 * eventBus.register("TURN.START", 0, (event, match) -> {
 *     String playerId = event.getSourceId();
 *     System.out.println("轮到玩家: " + playerId);
 * });
 * }</pre>
 *
 * <h3>发布自定义钩子</h3>
 * <p>在事件处理过程中，可以发布子事件（钩子），其他技能可以监听这些钩子。</p>
 * <pre>{@code
 * // 事件处理器：在摸牌过程中发布钩子
 * eventBus.register("CARD.DRAW", 0, (event, match) -> {
 *     // 发布摸牌前钩子 → 监听器可修改摸牌数量
 *     event.publishHook("BEFORE", eventBus, match);
 *
 *     int count = event.getDataOrDefault("count", 2);
 *     // ... 执行摸牌 ...
 *     event.putData("actualCount", count);
 *
 *     // 发布摸牌后钩子 → 监听器可响应摸牌完成
 *     event.publishHook("AFTER", eventBus, match);
 * });
 *
 * // 技能监听："英姿"监听 CARD.DRAW.BEFORE 钩子 → 摸牌数+1
 * eventBus.register("CARD.DRAW.BEFORE", 100, (event, match) -> {
 *     int count = event.getData("count");
 *     event.putData("count", count + 1);
 * });
 * }</pre>
 *
 * <h3>钩子事件命名</h3>
 * <p>发布钩子时，事件类型为 {@code "父事件类型.钩子后缀"}。
 * 例如父事件类型为 {@code "SKILL.ACTIVATE"}，发布 {@code "BEFORE"} 钩子，
 * 则钩子事件类型为 {@code "SKILL.ACTIVATE.BEFORE"}。</p>
 *
 * <p>钩子事件自动继承父事件的 sourceId、targetId、data，
 * 可在监听器中通过 {@link GameEvent#putData(String, Object)} 修改数据影响后续处理。</p>
 */
@FunctionalInterface
public interface EventListener {

    /**
     * 当事件被发布时调用
     *
     * @param event 事件对象（可通过 {@link GameEvent#cancel()} 取消后续处理）
     * @param match 当前对局实例（可在监听器中修改）
     */
    void onEvent(GameEvent event, GameMatch match);
}