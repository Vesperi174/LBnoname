package com.lbthreecountry.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户账户实体 — 登录凭证
 *
 * <p>存储用户的登录认证信息，与 {@link User} 实体分离，避免敏感信息与用户详情耦合。
 * 遵循最小权限原则，即使 User 表被泄露，密码等敏感信息仍在此表中受到保护。</p>
 *
 * <h3>安全设计</h3>
 * <ul>
 *   <li>密码使用 BCrypt 加密存储，不可逆</li>
 *   <li>loginAttempts 记录连续登录失败次数，超过阈值自动锁定账户</li>
 *   <li>username 唯一索引，防止重复注册</li>
 * </ul>
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "user_account", uniqueConstraints = {
        @UniqueConstraint(name = "uk_username", columnNames = "username")
})
public class UserAccount {

    /**
     * 账户ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 用户名（唯一登录名，3~50个字符，只能包含字母、数字和下划线）
     */
    @Column(nullable = false, unique = true, length = 50)
    private String username;

    /**
     * 密码（BCrypt 加密后的密文，非明文）
     */
    @Column(nullable = false, length = 255)
    private String password;

    /**
     * 关联的用户ID（对应 {@link User#id}）
     */
    @Column(nullable = false)
    private Long userId;

    /**
     * 账户状态
     * <ul>
     *   <li>0 — 禁用</li>
     *   <li>1 — 正常</li>
     *   <li>2 — 锁定（登录失败次数过多）</li>
     * </ul>
     */
    @Column(nullable = false)
    @Builder.Default
    private Integer accountStatus = 1;

    /**
     * 连续登录失败次数（用于防暴力破解，超过5次自动锁定）
     */
    @Builder.Default
    private Integer loginAttempts = 0;

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
     * 最后登录时间
     */
    private LocalDateTime lastLoginTime;
}