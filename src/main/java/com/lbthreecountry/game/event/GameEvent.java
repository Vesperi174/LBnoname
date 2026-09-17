package com.lbthreecountry.game.event;

import com.lbthreecountry.game.GameMatch;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import com.lbthreecountry.game.GameMatch;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
/**
 * 游戏事件 — 事件总线中传递的事件对象
 *
 * <p>每次游戏中发生某个行为时，创建一个事件对象通过 {@link EventBus} 发布。
 * 事件类型使用 {@link String} 标识，支持任意模块定义自己的事件常量。</p>
 *
 * <p>示例：</p>
 * <pre>{@code
 * // 玩家 A 使用杀
 * GameEvent event = GameEvent.builder()
 *     .type("CARD.PLAY")
 *     .sourceId("player_A")
 *     .putData("cardName", "杀")
 *     .putData("targetId", "player_B")
 *     .build();
 * eventBus.publish(event, match);
 * }</pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GameEvent {

    /** 事件类型（String，各模块自行定义常量，不依赖固定枚举） */
    private String type;

    /** 事件来源 ID（玩家 ID 或 "system"） */
    private String sourceId;

    /** 事件目标 ID（可选，受影响的玩家/卡牌等） */
    private String targetId;

    /** 附加数据 */
    @Builder.Default
    private Map<String, Object> data = new HashMap<>();

    /** 事件发生时间戳 */
    @Builder.Default
    private long timestamp = System.currentTimeMillis();

    /**
     * 是否已被取消
     * <p>监听器可将此标记设为 true 来阻止后续监听器执行或阻止默认行为。</p>
     */
    @Builder.Default
    private boolean cancelled = false;

    // ============ 便捷方法 ============

    /** 放入一条附加数据（链式调用） */
    public GameEvent putData(String key, Object value) {
        if (this.data == null) {
            this.data = new HashMap<>();
        }
        this.data.put(key, value);
        return this;
    }

    /** 获取附加数据（自动转型） */
    @SuppressWarnings("unchecked")
    public <T> T getData(String key) {
        if (data == null) return null;
        return (T) data.get(key);
    }

    /**
     * 获取附加数据，不存在时返回默认值
     *
     * @param key          键
     * @param defaultValue 默认值
     * @return 数据值或默认值
     */
    @SuppressWarnings("unchecked")
    public <T> T getDataOrDefault(String key, T defaultValue) {
        if (data == null || !data.containsKey(key)) return defaultValue;
        T value = (T) data.get(key);
        return value != null ? value : defaultValue;
    }

    /** 取消事件（阻止后续处理） */
    public void cancel() {
        this.cancelled = true;
    }

    // ============ 钩子系统 ============

    /**
     * 创建子事件（钩子）
     *
     * <p>基于当前事件创建一个派生事件，用于在事件处理过程中发布钩子。
     * 子事件的类型为 {@code "父事件类型.钩子后缀"}，并继承父事件的
     * sourceId、targetId、data。</p>
     *
     * <p>示例用法：</p>
     * <pre>{@code
     * // 在事件监听器中发布钩子
     * eventBus.publish(event.createHook("BEFORE"), match);
     * // → 发布的事件类型为 "原始类型.BEFORE"
     * }</pre>
     *
     * @param hookSuffix 钩子后缀（如 "BEFORE"、"AFTER"、"CALCULATE"），不可为 null 或空
     * @return 新的子事件对象
     */
    public GameEvent createHook(String hookSuffix) {
        Objects.requireNonNull(hookSuffix, "hookSuffix must not be null");
        if (hookSuffix.isEmpty()) {
            throw new IllegalArgumentException("hookSuffix must not be empty");
        }

        GameEvent hook = new GameEvent();
        hook.setType(this.type + "." + hookSuffix);
        hook.setSourceId(this.sourceId);
        hook.setTargetId(this.targetId);
        // 继承父事件的数据（浅拷贝副本，互不影响）
        hook.setData(this.data != null ? new HashMap<>(this.data) : new HashMap<>());
        hook.setTimestamp(System.currentTimeMillis());
        return hook;
    }

    /**
     * 创建并发布一个钩子子事件
     *
     * <p>等价于 {@code eventBus.publish(event.createHook(hookSuffix), match)}。</p>
     *
     * @param hookSuffix 钩子后缀
     * @param eventBus   事件总线
     * @param match      当前对局
     */
    public void publishHook(String hookSuffix, EventBus eventBus, GameMatch match) {
        eventBus.publish(this.createHook(hookSuffix), match);
    }
}