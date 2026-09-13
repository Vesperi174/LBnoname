package com.lbthreecountry.repository;

import com.lbthreecountry.entity.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 用户账户数据访问接口
 * 操作 user_account 表，处理登录凭证相关查询
 */
@Repository
public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {
    /**
     * 根据用户名查询账户
     * @param username 用户名
     * @return 账户信息
     */
    Optional<UserAccount> findByUsername(String username);

    /**
     * 根据用户ID查询账户
     * @param userId 用户ID（对应 User 表的主键）
     * @return 账户信息
     */
    Optional<UserAccount> findByUserId(Long userId);

    /**
     * 检查用户名是否已存在
     * @param username 用户名
     * @return 是否存在
     */
    Boolean existsByUsername(String username);
}
