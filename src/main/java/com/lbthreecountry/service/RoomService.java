package com.lbthreecountry.service;

import com.lbthreecountry.entity.GameRoom;
import com.lbthreecountry.model.player.PlayerInfo;

import java.util.List;

/**
 * 房间管理服务
 *
 * <p>所有操作基于内存，无数据库。
 * 房主的服务器进程管理着全部的房间和玩家。</p>
 */
public interface RoomService {

    /**
     * 创建房间
     *
     * @param roomName   房间名称
     * @param owner      房主玩家信息
     * @param maxPlayers 最大玩家数
     * @return 创建的房间
     */
    GameRoom createRoom(String roomName, PlayerInfo owner, int maxPlayers);

    /**
     * 加入房间
     *
     * @param roomId   房间 ID
     * @param player   加入的玩家
     * @return 加入成功返回 true
     */
    boolean joinRoom(String roomId, PlayerInfo player);

    /**
     * 离开房间
     *
     * @param roomId   房间 ID
     * @param playerId 离开的玩家 ID
     * @return 是否成功
     */
    boolean leaveRoom(String roomId, String playerId);

    /**
     * 获取房间信息
     */
    GameRoom getRoom(String roomId);

    /**
     * 获取所有房间列表
     */
    List<GameRoom> getAllRooms();

    /**
     * 获取可加入的房间列表（等待中的房间）
     */
    List<GameRoom> getJoinableRooms();

    /**
     * 删除房间（房间为空时自动清理）
     */
    boolean removeRoom(String roomId);

    /**
     * 根据 playerId 查找玩家所在的房间
     */
    GameRoom findRoomByPlayerId(String playerId);
}