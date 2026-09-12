package com.lbthreecountry.repository;

import com.lbthreecountry.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 用户数据访问接口
 * JpaRepository 提供 save()、findById()、findAll()、delete() 等基础方法
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    /**
     * 根据用户名查询用户
     * @param username 用户名
     * @return 用户对象
     */
    Optional<User> findByUsername(String username);
    /**
     * 根据邮箱查询用户
     * @param email 邮箱
     * @return 用户对象
     */
    Optional<User> findByEmail(String email);
    /**
     * 检查用户名是否存在
     * @param username 用户名
     * @return 是否存在
     */
    Boolean existsByUsername(String username);
    /**
     * 根据邮箱查询用户
     * @param email 邮箱
     * @return 是否存在
     */
    Boolean existsByEmail(String email);
}
