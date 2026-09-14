package com.lbthreecountry.entity;

import com.lbthreecountry.model.enums.impl.RoomStatus;
import com.lbthreecountry.model.player.PlayerInfo;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * 游戏房间实体 — 纯内存 POJO，无 JPA
 *
 * <p>管理房间内的玩家集合和房间状态。
 * 不再依赖 User 实体或数据库，直接引用 {@link PlayerInfo}。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GameRoom {

    private static final Random RANDOM = new Random();

    /**
     * 房间 ID（UUID）
     */
    private String roomId;

    /**
     * 房间名称
     */
    private String roomName;

    /**
     * 房主的 playerId
     */
    private String ownerPlayerId;

    /**
     * 房间状态
     */
    @Builder.Default
    private RoomStatus status = RoomStatus.WAITING;

    /**
     * 房间内的玩家列表
     */
    @Builder.Default
    private List<RoomPlayer> players = new ArrayList<>();

    /**
     * 最大玩家数
     */
    @Builder.Default
    private int maxPlayers = 8;

    /**
     * 当前玩家数
     */
    @Builder.Default
    private int playerCount = 0;

    /**
     * 创建时间戳
     */
    @Builder.Default
    private long createdAt = System.currentTimeMillis();

    /**
     * 房间设置
     */
    @Builder.Default
    private Map<String, Object> roomSettings = new HashMap<>();

    /**
     * 快速创建一个新房间
     *
     * @param roomName     房间名称
     * @param owner        房主玩家信息
     * @param maxPlayers   最大玩家数
     * @return 新房间实例
     */
    public static GameRoom create(String roomName, PlayerInfo owner, int maxPlayers) {
        RoomPlayer ownerPlayer = RoomPlayer.builder()
                .playerId(owner.getPlayerId())
                .playerName(owner.getName())
                .seatNumber(0)
                .isReady(true)   // 房主默认准备
                .isAlive(true)
                .build();

        List<RoomPlayer> playerList = new ArrayList<>();
        playerList.add(ownerPlayer);

        Map<String, Object> defaultSettings = new HashMap<>();
        defaultSettings.put("doubleIntruder", false);
        defaultSettings.put("turnTime", 15);
        defaultSettings.put("placeholder1", "");

        return GameRoom.builder()
                .roomId(UUID.randomUUID().toString())
                .roomName(roomName)
                .ownerPlayerId(owner.getPlayerId())
                .status(RoomStatus.WAITING)
                .players(playerList)
                .maxPlayers(maxPlayers)
                .playerCount(1)
                .roomSettings(defaultSettings)
                .build();
    }

    /**
     * 玩家加入房间
     *
     * @param playerInfo 加入的玩家
     * @return 是否加入成功
     */
    public boolean addPlayer(PlayerInfo playerInfo) {
        if (playerCount >= maxPlayers) {
            return false;
        }
        // 检查是否已在房间中
        if (players.stream().anyMatch(p -> p.getPlayerId().equals(playerInfo.getPlayerId()))) {
            return false;
        }

        RoomPlayer roomPlayer = RoomPlayer.builder()
                .playerId(playerInfo.getPlayerId())
                .playerName(playerInfo.getName())
                .seatNumber(playerCount)
                .isReady(false)
                .isAlive(true)
                .build();

        players.add(roomPlayer);
        playerCount++;
        return true;
    }

    /**
     * 玩家离开房间
     *
     * @param playerId 离开的玩家 ID
     * @return 是否离开成功
     */
    public boolean removePlayer(String playerId) {
        boolean removed = players.removeIf(p -> p.getPlayerId().equals(playerId));
        if (removed) {
            playerCount--;
            // 重新排座位
            for (int i = 0; i < players.size(); i++) {
                players.get(i).setSeatNumber(i);
            }
            // 如果房主离开，随机转让房主给一个剩余玩家
            if (ownerPlayerId.equals(playerId) && playerCount > 0) {
                int newOwnerIndex = RANDOM.nextInt(players.size());
                ownerPlayerId = players.get(newOwnerIndex).getPlayerId();
                players.get(newOwnerIndex).setReady(true);
            }
        }
        return removed;
    }

    /**
     * 获取房间内的玩家信息列表（用于广播）
     */
    public List<PlayerInfo> getPlayerInfoList() {
        return players.stream()
                .map(rp -> PlayerInfo.builder()
                        .playerId(rp.getPlayerId())
                        .name(rp.getPlayerName())
                        .build())
                .toList();
    }

    /**
     * 将房间信息转为 Map（JSON 友好），用于 WebSocket 消息
     */
    public Map<String, Object> toRoomInfoMap() {
        Map<String, Object> info = new HashMap<>();
        info.put("roomId", roomId);
        info.put("roomName", roomName);
        info.put("ownerPlayerId", ownerPlayerId);
        info.put("status", status.name());
        info.put("maxPlayers", maxPlayers);
        info.put("playerCount", playerCount);
        info.put("roomSettings", roomSettings);

        List<Map<String, Object>> playerList = players.stream().map(rp -> {
            Map<String, Object> p = new HashMap<>();
            p.put("playerId", rp.getPlayerId());
            p.put("playerName", rp.getPlayerName());
            p.put("seatNumber", rp.getSeatNumber());
            p.put("isReady", rp.isReady());
            p.put("isAlive", rp.isAlive());
            return p;
        }).toList();
        info.put("players", playerList);

        return info;
    }

    /**
     * 获取房间内所有玩家的 playerId 列表
     */
    public List<String> getPlayerIdList() {
        return players.stream()
                .map(RoomPlayer::getPlayerId)
                .toList();
    }
}