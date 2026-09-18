package com.lbthreecountry.game.event.common;

import com.lbthreecountry.game.event.EventBus;
import com.lbthreecountry.game.event.EventPriority;
import com.lbthreecountry.game.event.GameEvent;
import com.lbthreecountry.game.event.GameEventType;
import com.lbthreecountry.game.event.SettlementFrame;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Deque;

/**
 * 取消事件 — 监听 {@code CANCEL_ACTION} 触发钩子，执行取消生命周期
 *
 * <h3>取消生命周期</h3>
 * <pre>
 * CANCEL_ACTION (触发钩子)
 *   ├── CANCEL_BEFORE  (取消前，监听器可阻止取消)
 *   ├── CANCEL_ACTIVE  (取消进行中，取消结算栈中的父事件)
 *   └── CANCEL_AFTER   (取消完成后通知)
 * </pre>
 *
 * <h3>核心原理</h3>
 * <p>
 * {@code CancelEvent} 通过结算栈（Settlement Stack）来定位要取消的目标事件。
 * 当 {@code CANCEL_ACTION} 从某个事件的监听器中被发布时，结算栈的状态为：
 * </p>
 * <pre>
 * 栈底 [父事件帧]  ← 要取消的目标
 *        ↓ (嵌套发布)
 * 栈顶 [CANCEL_ACTION 帧]  ← 正在被 CancelEvent 处理
 * </pre>
 * <p>
 * CancelEvent 在 {@code CANCEL_ACTIVE} 阶段通过 {@code match.getSettlementStack()}
 * 获取结算栈，找到父帧并调用 {@code parentEvent.cancel()}。
 * 父帧的 {@code processFrameSync} 循环检测到 {@code cancelled = true} 后自动跳出，
 * 终止后续监听器执行，从而实现"取消当前事件"的效果。
 * </p>
 *
 * <h3>事件数据格式</h3>
 * <pre>
 * ┌──────────────┬──────────┬──────────────────────────────────────────┐
 * │ 字段          │ 类型      │ 说明                                     │
 * ├──────────────┼──────────┼──────────────────────────────────────────┤
 * │ cancelId     │ String   │ 被取消的对象 ID（必填）                    │
 * │ cancelType   │ String   │ 取消类型（如 SKILL / CARD / ACTION）       │
 * │ reason       │ String   │ 取消原因描述                               │
 * └──────────────┴──────────┴──────────────────────────────────────────┘
 * </pre>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 在某个事件的监听器中发布取消事件
 * eventBus.register("SOME.EVENT", EventPriority.SKILL, (event, match) -> {
 *     // 某些条件满足时，取消当前事件
 *     GameEvent cancel = GameEvent.builder()
 *         .type(GameEventType.CANCEL_ACTION)
 *         .sourceId(sourceId)
 *         .build()
 *         .putData("cancelId", targetId)
 *         .putData("cancelType", CancelEvent.CANCEL_TYPE_ACTION)
 *         .putData("reason", "条件不满足，取消执行");
 *     eventBus.publish(cancel, match);
 *     // publish 返回后，父事件 (SOME.EVENT) 已被取消，
 *     // 其 processFrameSync 循环已跳出，不再执行后续监听器
 * });
 *
 * // 监听取消前钩子来阻止取消
 * eventBus.register(GameEventType.CANCEL_BEFORE, EventPriority.SKILL, (ev, m) -> {
 *     String reason = ev.getData("reason");
 *     if ("某些不可取消的场景".equals(reason)) {
 *         ev.cancel(); // 阻止取消
 *     }
 * });
 * }</pre>
 */
@Component
public class CancelEvent {

    private static final Logger log = LoggerFactory.getLogger(CancelEvent.class);

    // ================================================================
    //  取消类型常量
    // ================================================================

    /** 技能取消 */
    public static final String CANCEL_TYPE_SKILL = "SKILL";
    /** 卡牌取消 */
    public static final String CANCEL_TYPE_CARD = "CARD";
    /** 动作取消 */
    public static final String CANCEL_TYPE_ACTION = "ACTION";

    private final EventBus eventBus;

    public CancelEvent(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    @PostConstruct
    public void init() {
        eventBus.register(GameEventType.CANCEL_ACTION, EventPriority.ENGINE, this::onCancel);
        
    }

    /**
     * {@code CANCEL_ACTION} 事件回调
     *
     * <p>从事件数据中读取取消参数，依次执行以下流程：</p>
     * <ol>
     *   <li>{@code CANCEL_BEFORE} — 取消前（监听器可阻止取消）</li>
     *   <li>{@code CANCEL_ACTIVE} — 取消进行中（在结算栈中查找父事件并取消）</li>
     *   <li>{@code CANCEL_AFTER} — 取消完成后通知</li>
     * </ol>
     */
    private void onCancel(GameEvent event, com.lbthreecountry.game.GameMatch match) {
        String cancelId = event.getData("cancelId");
        String cancelType = event.getData("cancelType");
        String reason = event.getData("reason");

        log.info("[取消事件] 收到取消请求: type={}, cancelId={}, reason={}",
                cancelType, cancelId, reason);

        // ================================================================
        //  阶段一：取消前钩子（CANCEL_BEFORE）
        //  监听器可在此时阻止取消行为
        // ================================================================
        GameEvent beforeHook = createHookEvent(GameEventType.CANCEL_BEFORE, event);
        eventBus.publish(beforeHook, match);

        if (beforeHook.isCancelled()) {
            log.info("[取消事件] BEFORE 钩子阻止了取消: cancelId={}", cancelId);
            event.putData("cancelled", false);
            return;
        }

        // ================================================================
        //  阶段二：取消进行中（CANCEL_ACTIVE）
        //  发布钩子 + 实际取消结算栈中的父事件
        // ================================================================
        GameEvent activeHook = createHookEvent(GameEventType.CANCEL_ACTIVE, event);
        eventBus.publish(activeHook, match);

        // 通过结算栈查找父帧并取消其事件
        boolean cancelled = cancelParentEvent(match);

        // ================================================================
        //  阶段三：取消完成后钩子（CANCEL_AFTER）
        // ================================================================
        GameEvent afterHook = createHookEvent(GameEventType.CANCEL_AFTER, event);
        afterHook.putData("cancelled", cancelled);
        eventBus.publish(afterHook, match);

        // 将取消结果写回主事件
        event.putData("cancelled", cancelled);

        if (cancelled) {
            log.info("[取消事件] 成功取消父事件: cancelId={}", cancelId);
        } else {
            log.warn("[取消事件] 取消失败，未找到父事件: cancelId={}", cancelId);
        }
    }

    // ================================================================
    //  内部方法
    // ================================================================

    /**
     * 创建钩子事件（继承主事件的 sourceId 和数据）
     *
     * @param hookType 钩子事件类型
     * @param source   主事件
     * @return 钩子事件
     */
    private GameEvent createHookEvent(String hookType, GameEvent source) {
        return GameEvent.builder()
                .type(hookType)
                .sourceId(source.getSourceId())
                .build()
                .putData("cancelId", source.getData("cancelId"))
                .putData("cancelType", source.getData("cancelType"))
                .putData("reason", source.getData("reason"));
    }

    /**
     * 在结算栈中查找父帧并取消其事件
     *
     * <p>结算栈状态示意：</p>
     * <pre>
     * 栈底 [父事件帧]  ← 要取消的目标
     *        ↓
     * 栈顶 [CANCEL_ACTION 帧]  ← 当前帧
     * </pre>
     *
     * <p>CANCEL_ACTION 帧创建时，其 parent 被设为当时的栈顶（即父帧）。
     * 本方法通过 {@code cancelFrame.getParent()} 获取父帧，然后调用父事件的
     * {@code cancel()} 方法。父帧的 {@code processFrameSync} 循环在下一次迭代时
     * 检测到 {@code isCancelled() == true}，自动跳出循环，终止后续监听器执行。</p>
     *
     * @param match 当前对局
     * @return 是否成功取消父事件
     */
    private boolean cancelParentEvent(com.lbthreecountry.game.GameMatch match) {
        Deque<SettlementFrame> stack = match.getSettlementStack();
        if (stack == null || stack.isEmpty()) {
            return false;
        }

        // 栈顶是 CANCEL_ACTION 帧
        SettlementFrame cancelFrame = stack.peek();
        if (cancelFrame == null) {
            return false;
        }

        // 获取父帧（即触发取消的原始事件所在的帧）
        SettlementFrame parentFrame = cancelFrame.getParent();
        if (parentFrame == null) {
            log.warn("[取消事件] CANCEL_ACTION 在栈顶发布，没有可取消的父事件");
            return false;
        }

        GameEvent parentEvent = parentFrame.getEvent();
        if (parentEvent == null) {
            return false;
        }

        // 取消父事件 — processFrameSync/dispatchNext 会在下次迭代时检测到 cancelled
        parentEvent.cancel();
        log.debug("[取消事件] 已取消父事件: type={}, sourceId={}",
                parentEvent.getType(), parentEvent.getSourceId());
        return true;
    }
}