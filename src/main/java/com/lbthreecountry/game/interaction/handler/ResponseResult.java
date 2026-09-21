package com.lbthreecountry.game.interaction.handler;

import java.util.Map;
import java.util.Objects;

/**
 * 交互响应结果 — 携带状态、业务值和原始响应的泛型容器
 *
 * <p>由 {@link ResponseHandler} 的各交互方法返回，包含：</p>
 * <ul>
 *   <li>{@link #status} — 响应状态（确认/取消/超时/中断）</li>
 *   <li>{@link #value} — 业务值（如选中的卡牌 instanceId、目标玩家 ID 等），
 *       仅在 {@code status == CONFIRMED} 时有意义</li>
 *   <li>{@link #rawResponse} — 前端的原始响应 Map（调试/日志用）</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * ResponseResult<Long> result = responseHandler.selectCardWithConfirm(
 *         match, player, config.stream().collect(Collectors.toList()));
 *
 * if (result.isConfirmed()) {
 *     Long cardId = result.getValue();
 *     // 执行打出逻辑
 * } else if (result.isTimeout()) {
 *     // 超时处理
 * }
 * }</pre>
 *
 * @param <T> 业务值类型（Long=卡牌ID, String=玩家ID, Void=纯确认等）
 */
public final class ResponseResult<T> {

    private final ResponseStatus status;
    private final T value;
    private final Map<String, Object> rawResponse;

    /**
     * @param status      响应状态（不可为 null）
     * @param value       业务值（确认状态时有意义，否则一般为 null）
     * @param rawResponse 前端原始响应（可为 null）
     */
    public ResponseResult(ResponseStatus status, T value, Map<String, Object> rawResponse) {
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.value = value;
        this.rawResponse = rawResponse;
    }

    // ── 工厂方法 ──

    /** 创建确认结果 */
    public static <T> ResponseResult<T> confirmed(T value, Map<String, Object> raw) {
        return new ResponseResult<>(ResponseStatus.CONFIRMED, value, raw);
    }

    /** 创建取消结果 */
    public static <T> ResponseResult<T> cancelled(Map<String, Object> raw) {
        return new ResponseResult<>(ResponseStatus.CANCEL, null, raw);
    }

    /** 创建超时结果 */
    public static <T> ResponseResult<T> timeout(Map<String, Object> raw) {
        return new ResponseResult<>(ResponseStatus.TIMEOUT, null, raw);
    }

    /** 创建中断结果 */
    public static <T> ResponseResult<T> interrupted(Map<String, Object> raw) {
        return new ResponseResult<>(ResponseStatus.INTERRUPTED, null, raw);
    }

    /** 根据原始响应自动推断状态 */
    @SuppressWarnings("unchecked")
    public static ResponseResult<Map<String, Object>> fromRaw(Map<String, Object> raw) {
        if (raw == null) {
            return new ResponseResult<>(ResponseStatus.INTERRUPTED, null, null);
        }
        String action = (String) raw.get("action");
        String type = (String) raw.get("type");
        if ("cancel".equals(action)) {
            return cancelled(raw);
        }
        if ("timeout".equals(action) || "TIMEOUT".equals(action)
                || "CANCELLED".equals(type)) {
            return timeout(raw);
        }
        if ("interrupted".equals(action)) {
            return interrupted(raw);
        }
        // CONFIRM 或未知 → 视为确认
        return confirmed(raw, raw);
    }

    // ── 便捷查询 ──

    public boolean isConfirmed() { return status == ResponseStatus.CONFIRMED; }
    public boolean isCancelled() { return status == ResponseStatus.CANCEL; }
    public boolean isTimeout()   { return status == ResponseStatus.TIMEOUT; }
    public boolean isAbort()     { return status.isAbort(); }

    // ── Getter ──

    public ResponseStatus getStatus() { return status; }
    public T getValue()               { return value; }
    public Map<String, Object> getRawResponse() { return rawResponse; }

    @Override
    public String toString() {
        return "ResponseResult{" +
                "status=" + status +
                ", value=" + value +
                '}';
    }
}