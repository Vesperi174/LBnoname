package com.lbthreecountry.model.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一 API 响应封装
 * 所有接口统一返回格式，便于前端统一处理
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ApiResponse<T> {
    /**
     * 状态码
     */
    private Integer code;
    /**
     * 状态描述
     */
    private String message;
    /**
     * 数据
     */
    private T data;

    // ========== 静态工厂方法 ==========
    /**
     * 成功响应
     * @param data 数据
     * @return 成功响应
     */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(200, "操作成功", data);
    }
    /**
     * 成功响应
     * @param message 状态描述
     * @param data 数据
     * @return 成功响应
     */
    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(200, message, data);
    }
    /**
     * 错误响应
     * @param code 状态码
     * @param message 状态描述
     * @return 错误响应
     */
    public static <T> ApiResponse<T> error(Integer code, String message) {
        return new ApiResponse<>(code, message, null);
    }
    /**
     * 未授权响应
     * @param message 状态描述
     * @return 未授权响应
     */
    public static <T> ApiResponse<T> unauthorized(String message) {
        return new ApiResponse<>(401, message, null);
    }
    /**
     * 禁止响应
     * @param message 状态描述
     * @return 禁止响应
     */
    public static <T> ApiResponse<T> forbidden(String message) {
        return new ApiResponse<>(403, message, null);
    }
    /**
     * bad request 响应
     * @param message 状态描述
     * @return 400 响应
     */
    public static <T> ApiResponse<T> badRequest(String message) {
        return new ApiResponse<>(400, message, null);
    }
}
