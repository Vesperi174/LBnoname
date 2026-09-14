package com.lbthreecountry.game.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

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
 *     .type("CARD_PLAYED")
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

    /** 取消事件（阻止后续处理） */
    public void cancel() {
        this.cancelled = true;
    }
}