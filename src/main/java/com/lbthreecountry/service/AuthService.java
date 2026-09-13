package com.lbthreecountry.service;

import com.lbthreecountry.model.dto.LoginRequestDTO;
import com.lbthreecountry.model.dto.RefreshTokenDTO;
import com.lbthreecountry.model.dto.RegisterRequestDTO;
import com.lbthreecountry.model.vo.UserVO;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 认证服务接口
 * 定义登录、注册、Token刷新三个核心方法
 */
public interface AuthService {
    /**
     * 用户登录
     * @param request 包含用户名和密码
     * @return Map 包含 accessToken, refreshToken, userInfo
     */
    Map<String, Object> login(LoginRequestDTO request);

    /**
     * 用户注册
     * @param request 包含用户名、密码、昵称、邮箱
     * @return 新创建用户的简要信息
     */
    UserVO register(RegisterRequestDTO request);

    /**
     * 刷新 Token
     * @param request 包含 refreshToken
     * @return Map 包含新的 accessToken 和 refreshToken
     */
    Map<String, Object> refresh(RefreshTokenDTO request);

    /**
     * 用户注销
     * @param userId 用户ID
     */
    void logout(Long userId);
}
