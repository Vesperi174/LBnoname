package com.lbthreecountry.model.effect.interfaces;

/**
 * 效果发生器接口 — 根据效果数据执行具体行为
 *
 * @param <T> 效果数据类型（Damage 或 Heal）
 */
@FunctionalInterface
public interface BaseEffect<T> {

    /**
     * 执行效果
     * @param data 效果数据（携带来源、目标、数值、属性等信息）
     */
    void execute(T data);
}
