package com.lbthreecountry.model.player;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * 玩家信息 — 纯内存 POJO，无 JPA、无持久化
 *
 * <p>当玩家通过 WebSocket 连接服务器时，服务端根据客户端提交的信息
 * 创建此对象。不查数据库，不校验身份，直接使用。</p>
 *
 * <p>playerId 使用 UUID 全局唯一，避免同名冲突。
 * 前端展示使用 name，后端区分使用 playerId。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlayerInfo {

    /**
     * 玩家 ID（UUID，全局唯一）
     */
    private String playerId;

    /**
     * 玩家名称（用户自己输入的显示名，可重复）
     */
    private String name;

    /**
     * 头像（可选，预留字段）
     */
    private String avatar;

    /**
     * 总游戏场次（仅本地统计）
     */
    @Builder.Default
    private int totalGames = 0;

    /**
     * 胜利场次（仅本地统计）
     */
    @Builder.Default
    private int wins = 0;

    /**
     * 快速创建一个新玩家
     *
     * @param name 玩家名称
     * @return 新玩家实例
     */
    public static PlayerInfo create(String name) {
        return PlayerInfo.builder()
                .playerId(UUID.randomUUID().toString())
                .name(name)
                .avatar(null)
                .totalGames(0)
                .wins(0)
                .build();
    }
}