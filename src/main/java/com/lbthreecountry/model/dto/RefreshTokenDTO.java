package com.lbthreecountry.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Token 刷新请求 DTO
 */
@Data
public class RefreshTokenDTO {
    /**
     * 刷新令牌
     */
    @NotBlank(message = "刷新令牌不能为空")
    private String refreshToken;
}
