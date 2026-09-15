package com.lbthreecountry.service.impl;

import com.lbthreecountry.entity.GameRoom;
import com.lbthreecountry.model.enums.impl.RoomStatus;
import com.lbthreecountry.model.player.PlayerInfo;
import com.lbthreecountry.service.RoomService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 房间管理服务实现（纯内存）
 *
 * <p>使用 ConcurrentHashMap 存储所有房间，线程安全。
 * 没有数据库，服务重启后房间数据丢失（个人联机场景可接受）。</p>
 */
@Service
public class RoomServiceImpl implements RoomService {

    /** roomId → GameRoom */
    private final Map<String, GameRoom> roomMap = new ConcurrentHashMap<>();

    /** playerId → roomId（快速查找玩家所在房间） */
    private final Map<String, String> playerRoomMap = new ConcurrentHashMap<>();

    @Override
    public GameRoom createRoom(String roomName, PlayerInfo owner, int maxPlayers) {
        // 如果玩家已经在一个房间里，先离开
        String existingRoomId = playerRoomMap.get(owner.getPlayerId());
        if (existingRoomId != null) {
            leaveRoom(existingRoomId, owner.getPlayerId());
        }

        GameRoom room = GameRoom.create(roomName, owner, maxPlayers);
        roomMap.put(room.getRoomId(), room);
        playerRoomMap.put(owner.getPlayerId(), room.getRoomId());

        System.out.println("[房间] 创建房间: " + roomName
                + "，房主: " + owner.getName()
                + "，当前总房间数: " + roomMap.size());

        return room;
    }

    @Override
    public boolean joinRoom(String roomId, PlayerInfo player) {
        GameRoom room = roomMap.get(roomId);
        if (room == null) {
            return false;
        }
        if (room.getStatus() != RoomStatus.WAITING) {
            return false;  // 游戏已开始，不能加入
        }

        // 如果玩家已在其他房间，先离开
        String existingRoomId = playerRoomMap.get(player.getPlayerId());
        if (existingRoomId != null && !existingRoomId.equals(roomId)) {
            leaveRoom(existingRoomId, player.getPlayerId());
        }

        boolean added = room.addPlayer(player);
        if (added) {
            playerRoomMap.put(player.getPlayerId(), roomId);
            System.out.println("[房间] 玩家 " + player.getName() + " 加入房间 " + room.getRoomName());
        }
        return added;
    }

    @Override
    public boolean leaveRoom(String roomId, String playerId) {
        GameRoom room = roomMap.get(roomId);
        if (room == null) {
            return false;
        }

        boolean removed = room.removePlayer(playerId);
        if (removed) {
            playerRoomMap.remove(playerId);
            System.out.println("[房间] 玩家离开房间 " + room.getRoomName());

            // 房间空了就自动删除
            if (room.getPlayerCount() <= 0) {
                roomMap.remove(roomId);
                System.out.println("[房间] 房间 " + room.getRoomName() + " 已空，自动删除");
            }
        }
        return removed;
    }

    @Override
    public GameRoom getRoom(String roomId) {
        return roomMap.get(roomId);
    }

    @Override
    public List<GameRoom> getAllRooms() {
        return List.copyOf(roomMap.values());
    }

    @Override
    public List<GameRoom> getJoinableRooms() {
        return roomMap.values().stream()
                .filter(r -> r.getStatus() == RoomStatus.WAITING)
                .filter(r -> r.getPlayerCount() + r.getClosedSeats().size() < r.getMaxPlayers())
                .toList();
    }

    @Override
    public boolean removeRoom(String roomId) {
        GameRoom room = roomMap.remove(roomId);
        if (room != null) {
            // 清理该房间所有玩家的映射
            room.getPlayers().forEach(p -> playerRoomMap.remove(p.getPlayerId()));
            return true;
        }
        return false;
    }

    @Override
    public GameRoom findRoomByPlayerId(String playerId) {
        String roomId = playerRoomMap.get(playerId);
        if (roomId == null) {
            return null;
        }
        return roomMap.get(roomId);
    }

    @Override
    public boolean closeSeat(String roomId, String playerId, int seatNumber) {
        GameRoom room = roomMap.get(roomId);
        if (room == null) {
            return false;
        }
        // 只有房主可以关闭座位
        if (!room.getOwnerPlayerId().equals(playerId)) {
            return false;
        }
        return room.closeSeatNumber(seatNumber);
    }

    @Override
    public boolean openSeat(String roomId, String playerId, int seatNumber) {
        GameRoom room = roomMap.get(roomId);
        if (room == null) {
            return false;
        }
        // 只有房主可以打开座位
        if (!room.getOwnerPlayerId().equals(playerId)) {
            return false;
        }
        return room.openSeatNumber(seatNumber);
    }
}