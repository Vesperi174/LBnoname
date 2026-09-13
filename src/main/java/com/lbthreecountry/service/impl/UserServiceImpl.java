package com.lbthreecountry.service.impl;

import com.lbthreecountry.entity.User;
import com.lbthreecountry.entity.UserAccount;
import com.lbthreecountry.model.vo.UserProfileVO;
import com.lbthreecountry.model.vo.UserVO;
import com.lbthreecountry.repository.UserAccountRepository;
import com.lbthreecountry.repository.UserRepository;
import com.lbthreecountry.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 用户服务实现
 */
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private static final PasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();

    private static final String USER_NOT_FOUND = "用户不存在";
    private static final String ACCOUNT_NOT_FOUND = "账户信息不存在";
    private static final String INCORRECT_PASSWORD = "旧密码错误";

    private final UserRepository userRepository;
    private final UserAccountRepository userAccountRepository;

    // ==================== 查询 ====================

    @Override
    @Transactional(readOnly = true)
    public UserVO getUserInfo(Long userId) {
        User user = findUserById(userId);
        UserAccount account = findAccountByUserId(userId);
        return buildUserVO(user, account);
    }

    @Override
    @Transactional(readOnly = true)
    public UserProfileVO getUserProfile(Long userId) {
        User user = findUserById(userId);
        UserAccount account = findAccountByUserId(userId);

        return UserProfileVO.builder()
                .id(user.getId())
                .username(account.getUsername())
                .nickname(user.getNickname())
                .avatar(user.getAvatar())
                .email(user.getEmail())
                .status(user.getStatus())
                .totalGames(user.getTotalGames())
                .wins(user.getWins())
                .winRate(calculateWinRate(user))
                .createTime(formatDateTime(user.getCreateTime()))
                .lastLoginTime(formatDateTime(account.getLastLoginTime()))
                .build();
    }

    // ==================== 修改 ====================

    @Override
    @Transactional
    public UserVO updateNickname(Long userId, String nickname) {
        User user = findUserById(userId);
        user.setNickname(nickname);
        UserAccount account = findAccountByUserId(userId);
        return buildUserVO(user, account);
    }

    @Override
    @Transactional
    public UserVO updateAvatar(Long userId, String avatar) {
        User user = findUserById(userId);
        user.setAvatar(avatar);
        UserAccount account = findAccountByUserId(userId);
        return buildUserVO(user, account);
    }

    @Override
    @Transactional
    public void updatePassword(Long userId, String oldPwd, String newPwd) {
        UserAccount account = findAccountByUserId(userId);

        if (!PASSWORD_ENCODER.matches(oldPwd, account.getPassword())) {
            throw new RuntimeException(INCORRECT_PASSWORD);
        }

        account.setPassword(PASSWORD_ENCODER.encode(newPwd));
    }

    // ==================== 私有方法 ====================

    private User findUserById(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException(USER_NOT_FOUND));
    }

    private UserAccount findAccountByUserId(Long userId) {
        return userAccountRepository.findByUserId(userId)
                .orElseThrow(() -> new RuntimeException(ACCOUNT_NOT_FOUND));
    }

    private static UserVO buildUserVO(User user, UserAccount account) {
        return UserVO.builder()
                .id(user.getId())
                .username(account.getUsername())
                .nickname(user.getNickname())
                .avatar(user.getAvatar())
                .build();
    }

    private static String calculateWinRate(User user) {
        if (user.getTotalGames() == null || user.getTotalGames() <= 0 || user.getWins() == null) {
            return "0%";
        }
        double rate = (double) user.getWins() / user.getTotalGames() * 100;
        return String.format("%.1f%%", rate);
    }

    private static String formatDateTime(LocalDateTime dateTime) {
        return dateTime != null ? dateTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : null;
    }

}