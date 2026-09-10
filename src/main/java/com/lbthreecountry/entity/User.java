package com.lbthreecountry.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户基本信息实体
 *
 * <p>存储用户的个人资料和游戏统计数据，不包含登录凭证等敏感信息。
 * 登录认证相关的字段（用户名、密码、最后登录时间等）存放在 {@link UserAccount} 中，
 * 实现用户资料与登录凭证的分离，遵循安全最佳实践。</p>
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "user")
public class User {
    /**
     * 用户ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 昵称（显示名称，可修改，最长20个字符）
     */
    @Column(length = 20)
    private String nickname;

    /**
     * 头像URL
     */
    @Column(length = 500)
    private String avatar;

    /**
     * 邮箱
     */
    @Column(length = 100)
    private String email;

    /**
     * 账号状态
     * <ul>
     *   <li>0 — 禁用</li>
     *   <li>1 — 正常</li>
     *   <li>2 — 已删除（软删除）</li>
     *   <li>3 — 管理员</li>
     * </ul>
     */
    @Column(nullable = false)
    @Builder.Default
    private Integer status = 1;

    /**
     * 创建时间
     */
    @Column(updatable = false)
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

    /**
     * 总游戏次数
     */
    @Builder.Default
    private Integer totalGames = 0;

    /**
     * 胜利次数
     */
    @Builder.Default
    private Integer wins = 0;
}