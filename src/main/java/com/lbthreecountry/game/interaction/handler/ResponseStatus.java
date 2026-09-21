package com.lbthreecountry.game.interaction.handler;

/**
 * 交互响应状态 — 标识玩家对交互消息的响应结果
 *
 * <p>用于 {@link ResponseResult} 中标识本次交互的最终状态：</p>
 * <ul>
 *   <li>{@link #CONFIRMED} — 玩家确认/选定了内容，{@link ResponseResult#getValue()} 中包含有效数据</li>
 *   <li>{@link #CANCEL} — 玩家主动取消</li>
 *   <li>{@link #TIMEOUT} — 玩家超时未响应</li>
 *   <li>{@link #INTERRUPTED} — 连接中断/房间销毁等异常中断</li>
 * </ul>
 */
public enum ResponseStatus {

    /** 玩家确认/选定了内容 */
    CONFIRMED,
    /** 玩家主动取消 */
    CANCEL,
    /** 超时未响应 */
    TIMEOUT,
    /** 连接中断/房间销毁 */
    INTERRUPTED;

    /**
     * 是否为"放弃"类状态（取消/超时/中断）
     *
     * @return {@code true} 表示玩家未确认任何内容
     */
    public boolean isAbort() {
        return this == CANCEL || this == TIMEOUT || this == INTERRUPTED;
    }

    /**
     * 是否为"成功"状态（仅 CONFIRMED）
     *
     * @return {@code true} 表示玩家确认了内容
     */
    public boolean isSuccess() {
        return this == CONFIRMED;
    }
}