package com.lbthreecountry.game.event;

import com.lbthreecountry.game.GameMatch;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 事件总线 — 游戏引擎的中枢神经系统
 *
 * <p>所有游戏行为通过发布事件来触发监听器。
 * 事件类型使用 {@link String} 标识，任何模块都可以定义自己的事件常量，
 * 不需要修改核心代码来扩展新事件。</p>
 *
 * <h3>设计原则</h3>
 * <ul>
 *   <li><b>同步执行</b> — 发布事件后，所有监听器在同一线程中依次执行</li>
 *   <li><b>优先级排序</b> — 数值越小越先执行（0 = 最高优先级）</li>
 *   <li><b>支持取消</b> — 监听器可调用 {@link GameEvent#cancel()} 阻止后续处理</li>
 *   <li><b>线程安全</b> — 使用 ConcurrentHashMap + CopyOnWriteArrayList</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 注册监听器（事件类型直接传字符串，不用枚举）
 * eventBus.register("GAME.TURN_START", 0, (event, match) -> {
 *     System.out.println("轮到: " + event.getSourceId());
 * });
 *
 * // 发布事件
 * eventBus.publish(GameEvent.builder()
 *     .type("GAME.TURN_START")
 *     .sourceId("player_001")
 *     .build(), match);
 * }</pre>
 */
@Component
public class EventBus {

    private static final Logger log = LoggerFactory.getLogger(EventBus.class);

    /** 监听器注册表：事件类型 → 优先级排序的监听器列表 */
    private final Map<String, List<PrioritizedListener>> listenerMap = new ConcurrentHashMap<>();

    // ================================================================
    //  注册 / 注销
    // ================================================================

    /**
     * 注册事件监听器
     *
     * @param eventType 要监听的事件类型（String，不可为 null）
     * @param priority  优先级（0 = 最高，数值越大优先级越低）
     * @param listener  监听器回调
     */
    public void register(String eventType, int priority, EventListener listener) {
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(listener, "listener must not be null");

        PrioritizedListener pl = new PrioritizedListener(eventType, priority, listener);
        listenerMap.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(pl);

        // 按优先级排序
        listenerMap.get(eventType).sort(Comparator.comparingInt(PrioritizedListener::priority));
    }

    /**
     * 移除指定的监听器（从所有事件类型中移除）
     *
     * @param listener 要移除的监听器
     */
    public void unregister(EventListener listener) {
        Objects.requireNonNull(listener, "listener must not be null");
        listenerMap.values().forEach(list -> list.removeIf(pl -> pl.listener == listener));
    }

    /**
     * 移除指定事件类型的所有监听器
     *
     * @param eventType 事件类型
     */
    public void unregisterAll(String eventType) {
        listenerMap.remove(eventType);
    }

    /**
     * 清空所有监听器
     */
    @PreDestroy
    public void clear() {
        listenerMap.clear();
    }

    // ================================================================
    //  发布
    // ================================================================

    /**
     * 发布事件 — 所有匹配的监听器按优先级依次执行
     *
     * <p>如果某个监听器调用了 {@link GameEvent#cancel()}，
     * 后续监听器仍会执行（事件取消由业务层自行判断）。</p>
     *
     * @param event 事件对象
     * @param match 当前对局实例
     */
    public void publish(GameEvent event, GameMatch match) {
        Objects.requireNonNull(event, "event must not be null");
        if (event.getType() == null) {
            log.warn("[事件] 发布的事件缺少 type 字段，已忽略");
            return;
        }

        List<PrioritizedListener> listeners = listenerMap.get(event.getType());
        if (listeners == null || listeners.isEmpty()) {
            return; // 没有监听者，无事发生
        }

        if (log.isDebugEnabled()) {
            log.debug("[事件] {} (来源: {})", event.getType(), event.getSourceId());
        }

        for (PrioritizedListener pl : listeners) {
            try {
                pl.listener.onEvent(event, match);
            } catch (Exception e) {
                log.error("[事件] 监听器执行异常 [type={}, source={}]",
                        event.getType(), event.getSourceId(), e);
            }
        }
    }

    /**
     * 发布事件（无对局实例的简化版本）
     *
     * @param event 事件对象
     */
    public void publish(GameEvent event) {
        publish(event, null);
    }

    // ================================================================
    //  内部类
    // ================================================================

    /** 带优先级的监听器包装 */
    private record PrioritizedListener(String eventType, int priority, EventListener listener) {
    }
}