package com.lbthreecountry.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 房间玩家关联 — 纯内存 POJO，无 JPA
 *
 * <p>记录房间内每个玩家的游戏状态。
 * 不再关联 User 实体，直接使用 playerId（UUID）引用 {@link com.lbthreecountry.model.player.PlayerInfo}。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoomPlayer {

    /**
     * 玩家 ID（对应 PlayerInfo.playerId）
     */
    private String playerId;

    /**
     * 玩家名称（冗余存储，方便显示，避免频繁查找）
     */
    private String playerName;

    /**
     * 座位号
     */
    private int seatNumber;

    /**
     * 选择的武将 ID
     */
    private String heroId;

    /**
     * 是否准备
     */
    @Builder.Default
    private boolean isReady = false;

    /**
     * 是否存活
     */
    @Builder.Default
    private boolean isAlive = true;
}