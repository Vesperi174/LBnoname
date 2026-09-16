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
 * <h3>事件钩子机制</h3>
 * <p>
 * 任何事件在其生命周期中都可以发布自定义钩子（子事件），其他事件可以监听这些钩子。
 * 例如一个"杀"事件在处理过程中可以发布 {@code DAMAGE.BEFORE} 和 {@code DAMAGE.AFTER} 钩子：
 * </p>
 * <pre>{@code
 * // 注册监听器监听"伤害前"钩子
 * eventBus.register("DAMAGE.BEFORE", EventPriority.SKILL, (event, match) -> {
 *     int amount = event.getData("amount");
 *     event.putData("amount", amount + 1); // 伤害+1
 * });
 *
 * // 在事件处理中发布钩子
 * event.publishHook("BEFORE", eventBus, match); // → 发布 "当前事件类型.BEFORE"
 * // ... 执行伤害逻辑 ...
 * event.publishHook("AFTER", eventBus, match);  // → 发布 "当前事件类型.AFTER"
 * }</pre>
 *
 * <p>钩子事件通过 {@link GameEvent#createHook(String)} 创建，自动继承父事件的
 * type、sourceId、targetId、data。发布后走结算栈 LIFO 调度，嵌套事件优先处理。</p>
 *
 * <h3>结算栈机制（LIFO）</h3>
 * <p>
 * {@link #publish(GameEvent, GameMatch)} 将事件包装为
 * {@link SettlementFrame} 压入对局的结算栈。栈的 {@code settle()} 循环每次只执行
 * 栈顶帧的一个监听器，然后重新检查栈顶。
 * </p>
 *
 * <p>如果某个监听器中发布了新事件，新栈帧会压到栈顶，<b>优先处理新结算</b>，
 * 完成后自动回到原栈帧继续。这实现了 LIFO（后进先出）的嵌套结算语义。</p>
 *
 * <h3>钩子命名规范</h3>
 * <pre>
 * 事件类型             钩子后缀         发布的事件类型
 * ──────────          ───────        ─────────────────
 * DAMAGE              BEFORE         DAMAGE.BEFORE
 * SKILL.EXECUTE       AFTER          SKILL.EXECUTE.AFTER
 * CARD.PLAYED         CALCULATE      CARD.PLAYED.CALCULATE
 * </pre>
 *
 * <h3>设计原则</h3>
 * <ul>
 *   <li><b>监听器不变</b> — 技能开发者写 {@code eventBus.register()} 的方式完全不变</li>
 *   <li><b>优先级排序</b> — 数值越小越先执行（0 = 最高优先级）</li>
 *   <li><b>支持取消</b> — 监听器可调用 {@link GameEvent#cancel()}，后续监听器会跳过</li>
 *   <li><b>线程安全</b> — 使用 ConcurrentHashMap + CopyOnWriteArrayList</li>
 * </ul>
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
    //  发布 — 入栈 + 结算
    // ================================================================

    /**
     * 发布事件 — 压入对局的结算栈，由 {@code settle()} 循环调度
     *
     * <p>调用此方法后：</p>
     * <ol>
     *   <li>快照当前监听器列表，创建 {@link SettlementFrame}</li>
     *   <li>压入 {@code match} 的结算栈</li>
     *   <li>如果栈此前为空，启动 {@code settle()} 循环；否则等外层循环处理</li>
     * </ol>
     *
     * <p>{@code settle()} 循环每次只执行栈顶帧的一个监听器，然后重新检查栈顶，
     * 自然实现 LIFO 嵌套结算。</p>
     *
     * @param event 事件对象
     * @param match 当前对局实例（必须有结算栈）
     */
    public void publish(GameEvent event, GameMatch match) {
        Objects.requireNonNull(event, "event must not be null");
        Objects.requireNonNull(match, "match must not be null");
        if (event.getType() == null) {
            log.warn("[事件] 发布的事件缺少 type 字段，已忽略");
            return;
        }

        // 1) 快照监听器（按优先级排序的副本）
        List<PrioritizedListener> all = listenerMap.get(event.getType());
        if (all == null || all.isEmpty()) {
            return; // 无人监听，无事发生
        }
        List<EventListener> snapshot = all.stream()
                .map(pl -> pl.listener)
                .toList();

        // 2) 获取当前栈顶作为父帧
        Deque<SettlementFrame> stack = match.getSettlementStack();
        SettlementFrame parent = stack.peek();

        // 3) 创建栈帧并压入
        SettlementFrame frame = SettlementFrame.builder()
                .event(event)
                .match(match)
                .parent(parent)
                .listenerCursor(0)
                .listeners(snapshot)
                .completed(false)
                .build();

        stack.push(frame);

        if (log.isDebugEnabled()) {
            log.debug("[事件] {} (来源: {}, 栈深: {})",
                    event.getType(), event.getSourceId(), stack.size());
        }

        // 4) 如果栈此前为空，启动 settle 循环
        if (stack.size() == 1) {
            settle(match);
        }
        // 如果栈此前非空，说明正在外层 settle() 中，新帧会由外层循环的下次迭代处理
    }

    /**
     * 发布事件（无对局实例的简化版本）
     *
     * <p>不使用结算栈，直接遍历监听器执行（兼容旧式调用）。</p>
     *
     * @param event 事件对象
     */
    public void publish(GameEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        if (event.getType() == null) {
            log.warn("[事件] 发布的事件缺少 type 字段，已忽略");
            return;
        }

        List<PrioritizedListener> listeners = listenerMap.get(event.getType());
        if (listeners == null || listeners.isEmpty()) {
            return;
        }

        for (PrioritizedListener pl : listeners) {
            try {
                pl.listener.onEvent(event, null);
            } catch (Exception e) {
                log.error("[事件] 监听器执行异常 [type={}]", event.getType(), e);
            }
        }
    }

    /**
     * 发布事件钩子（便捷方法）
     *
     * <p>基于父事件创建一个钩子子事件并发布。等价于：</p>
     * <pre>{@code
     * eventBus.publish(parentEvent.createHook(hookSuffix), match);
     * }</pre>
     *
     * <p>钩子子事件类型为 {@code "父事件类型.钩子后缀"}，自动继承父事件的
     * sourceId、targetId、data。通过结算栈 LIFO 调度。</p>
     *
     * <p>示例：</p>
     * <pre>{@code
 * // 在处理"杀"事件的监听器中发布伤害前/后钩子
 * eventBus.register("CARD.PLAYED.SHA", EventPriority.EQUIP_CARD, (event, match) -> {
 *     // 发布 "CARD.PLAYED.SHA.BEFORE" 钩子
 *     event.publishHook("BEFORE", eventBus, match);
 *
 *     // 执行伤害逻辑
 *     int damage = 1;
 *     event.putData("damage", damage);
 *
 *     // 发布 "CARD.PLAYED.SHA.AFTER" 钩子
 *     event.publishHook("AFTER", eventBus, match);
 * });
 *
 * // 其他技能监听伤害前钩子来增加伤害
 * eventBus.register("CARD.PLAYED.SHA.BEFORE", EventPriority.SKILL, (event, match) -> {
 *     int damage = event.getData("damage");
 *     event.putData("damage", damage + 1); // 伤害+1
 * });
 * }</pre>
     *
     * @param parentEvent 父事件（作为钩子的基础）
     * @param hookSuffix  钩子后缀（如 "BEFORE"、"AFTER"、"CALCULATE"）
     * @param match       当前对局
     */
    public void publishHook(GameEvent parentEvent, String hookSuffix, GameMatch match) {
        Objects.requireNonNull(parentEvent, "parentEvent must not be null");
        Objects.requireNonNull(hookSuffix, "hookSuffix must not be null");
        Objects.requireNonNull(match, "match must not be null");
        publish(parentEvent.createHook(hookSuffix), match);
    }

    // ================================================================
    //  结算栈调度
    // ================================================================

    /**
     * 结算循环 — 不断从栈顶取帧，每次执行一个监听器
     *
     * <p>核心逻辑：每次执行完一个监听器后重新 peek 栈顶。
     * 如果监听器中发布了新事件（新帧入栈），下轮循环会自动处理新栈顶，
     * 从而实现 LIFO 嵌套结算。</p>
     */
    private void settle(GameMatch match) {
        Deque<SettlementFrame> stack = match.getSettlementStack();

        while (!stack.isEmpty()) {
            SettlementFrame top = stack.peek();

            // 执行当前帧的下一个监听器
            boolean frameDone = dispatchNext(top);

            if (frameDone) {
                SettlementFrame popped = stack.pop();
                log.debug("[结算] 帧完成: {} (栈深: {})",
                        popped.getEvent().getType(), stack.size());
            }
            // frameDone == false → 帧还有未执行的监听器，继续循环
            // 如果监听器中 publish 了新事件，下轮循环 peek 到的是新帧 → LIFO
        }
    }

    /**
     * 执行栈帧的下一个监听器
     *
     * @param frame 当前栈帧
     * @return true 表示帧内所有监听器已执行完毕
     */
    private boolean dispatchNext(SettlementFrame frame) {
        List<EventListener> listeners = frame.getListeners();
        int cursor = frame.getListenerCursor();

        if (listeners == null || cursor >= listeners.size()) {
            frame.setCompleted(true);
            return true;
        }

        // 光标前进
        frame.setListenerCursor(cursor + 1);
        EventListener listener = listeners.get(cursor);
        GameEvent event = frame.getEvent();

        // 如果事件已被取消，跳过后续所有监听器
        if (event.isCancelled()) {
            frame.setCompleted(true);
            frame.setListenerCursor(listeners.size());
            return true;
        }

        // 执行这个监听器
        try {
            listener.onEvent(event, frame.getMatch());
        } catch (Exception e) {
            log.error("[事件] 监听器执行异常 [type={}, source={}]",
                    event.getType(), event.getSourceId(), e);
        }

        return false; // 可能还有更多监听器，由 caller 重新检查
    }

    // ================================================================
    //  内部类
    // ================================================================

    /** 带优先级的监听器包装 */
    private record PrioritizedListener(String eventType, int priority, EventListener listener) {
    }
}