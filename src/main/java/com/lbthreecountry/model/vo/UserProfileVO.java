package com.lbthreecountry.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户详细信息 VO — 用于个人中心页面展示
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class UserProfileVO {
    /**
     * 用户 ID
     */
    private Long id;
    /**
     * 用户名
     */
    private String username;
    /**
     * 昵称
     */
    private String nickname;
    /**
     * 头像
     */
    private String avatar;
    /**
     * 邮箱
     */
    private String email;
    /**
     * 状态
     */
    private Integer status;
    /**
     * 总游戏场次
     */
    private Integer totalGames;
    /**
     * 胜利场次
     */
    private Integer wins;
    /**
     * 胜率（如 "62.5%"）
     */
    private String winRate;
    /**
     * 注册时间
     */
    private String createTime;
    /**
     * 最后登录时间
     */
    private String lastLoginTime;
}
