package com.lbthreecountry.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户简要信息 VO — 用于列表展示（如大厅玩家列表、好友列表）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class UserVO {
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
}
