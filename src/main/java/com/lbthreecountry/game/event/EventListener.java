package com.lbthreecountry.game.event;

import com.lbthreecountry.game.GameMatch;

/**
 * 事件监听器 — 函数式接口
 *
 * <p>通过 {@link EventBus#register(String, int, EventListener) 注册} 到事件总线，
 * 当对应类型的事件发布时被调用。</p>
 *
 * <pre>{@code
 * eventBus.register("TURN.START", 0, (event, match) -> {
 *     String playerId = event.getSourceId();
 *     System.out.println("轮到玩家: " + playerId);
 * });
 * }</pre>
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