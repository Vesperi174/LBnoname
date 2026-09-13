package com.lbthreecountry.service;

import com.lbthreecountry.model.vo.UserProfileVO;
import com.lbthreecountry.model.vo.UserVO;

/**
 * 用户服务接口
 */
public interface UserService {
    /**
     * 获取用户简要信息
     * @param userId 用户ID
     * @return 用户简要信息
     */
    UserVO getUserInfo(Long userId);
    /**
     * 获取用户详细信息
     * @param userId 用户ID
     * @return 用户详细信息
     */
    UserProfileVO getUserProfile(Long userId);
    /**
     * 修改用户昵称
     * @param userId 用户ID
     * @param nickname 新昵称
     * @return 更新后的用户简要信息
     */
    UserVO updateNickname(Long userId, String nickname);
    /**
     * 修改用户头像
     * @param userId 用户ID
     * @param avatar 新头像
     * @return 更新后的用户头像
     */
    UserVO updateAvatar(Long userId, String avatar);
    /**
     * 修改用户密码
     * @param userId 用户ID
     * @param oldPwd 旧密码
     * @param newPwd 新密码
     */
    void updatePassword(Long userId, String oldPwd, String newPwd); // 修改密码
}
