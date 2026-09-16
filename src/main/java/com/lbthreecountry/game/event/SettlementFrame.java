package com.lbthreecountry.game.event;

import com.lbthreecountry.game.GameMatch;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 结算栈帧 — 包装一次事件结算的全部信息
 *
 * <p>每次 {@link EventBus#publish(GameEvent, GameMatch)} 调用会创建一个栈帧，
 * 压入 {@link GameMatch} 的结算栈。栈帧保存了事件、对局、监听器快照和执行进度，
 * 支持 LIFO 嵌套结算：当监听器执行中触发新事件时，新栈帧压入栈顶，
 * 优先处理新栈帧，完成后再回到当前栈帧继续。</p>
 *
 * <p><b>生命周期：</b>  PUSHED → DISPATCHING → COMPLETED</p>
 */
@Data
@Builder
@AllArgsConstructor
public class SettlementFrame {

    /** 事件对象 */
    private GameEvent event;

    /** 当前对局 */
    private GameMatch match;

    /** 父栈帧（触发本事件的栈帧），栈底帧为 null */
    private SettlementFrame parent;

    /** 当前执行到的监听器下标（断点续传用） */
    @Builder.Default
    private int listenerCursor = 0;

    /** 发布时快照的监听器列表（已按优先级排序） */
    private List<EventListener> listeners;

    /** 是否已完成所有监听器执行 */
    @Builder.Default
    private boolean completed = false;
}