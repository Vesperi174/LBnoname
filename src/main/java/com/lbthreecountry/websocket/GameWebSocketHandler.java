package com.lbthreecountry.websocket;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lbthreecountry.entity.GameRoom;
import com.lbthreecountry.model.enums.impl.RoomStatus;
import com.lbthreecountry.model.player.PlayerInfo;
import com.lbthreecountry.model.player.PlayerSession;
import com.lbthreecountry.service.RoomService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.List;
import java.util.Map;

/**
 * 游戏 WebSocket 处理器
 *
 * <p>统一处理 WebSocket 连接的生命周期和消息路由。
 * 玩家名称在握手阶段由 {@link PlayerHandshakeInterceptor} 从 URL 参数中提取。</p>
 *
 * <h3>消息路由</h3>
 * 所有消息为 JSON 格式，根据 {@code type} 字段分发：
 * <ul>
 *   <li>{@code HEARTBEAT} — 心跳</li>
 *   <li>{@code CREATE_ROOM} — 创建房间</li>
 *   <li>{@code JOIN_ROOM} — 加入房间</li>
 *   <li>{@code LEAVE_ROOM} — 离开房间</li>
 *   <li>{@code ROOM_LIST} — 查询房间列表</li>
 *   <li>{@code PLAYER_READY} — 准备/取消准备</li>
 *   <li>{@code START_GAME} — 开始游戏（仅房主）</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class GameWebSocketHandler extends TextWebSocketHandler {

    private final WebSocketSessionManager sessionManager;
    private final RoomService roomService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ──────────────────────────────────────────────
    // 连接生命周期
    // ──────────────────────────────────────────────

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Map<String, Object> attributes = session.getAttributes();
        String playerName = (String) attributes.get("playerName");
        String avatar = (String) attributes.get("avatar");

        PlayerInfo playerInfo = PlayerInfo.create(playerName);
        if (avatar != null) {
            playerInfo.setAvatar(avatar);
        }

        PlayerSession playerSession = sessionManager.registerSession(session, playerInfo);

        // 通知该玩家连接成功
        sendJson(session, Map.of(
                "type", "CONNECTED",
                "playerId", playerInfo.getPlayerId(),
                "playerName", playerInfo.getName()
        ));

        // 推送当前房间列表
        pushRoomListToPlayer(session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        PlayerSession playerSession = sessionManager.getBySessionId(session.getId());
        if (playerSession == null) return;

        String playerId = playerSession.getPlayer().getPlayerId();

        // 如果玩家在房间中，先离开房间并通知同房间的人
        GameRoom room = roomService.findRoomByPlayerId(playerId);
        if (room != null) {
            String roomId = room.getRoomId();
            roomService.leaveRoom(roomId, playerId);

            // 通知房间内其他玩家
            GameRoom updatedRoom = roomService.getRoom(roomId);
            if (updatedRoom != null) {
                broadcastToRoom(updatedRoom, Map.of(
                        "type", "PLAYER_LEFT",
                        "playerId", playerId,
                        "playerName", playerSession.getPlayer().getName()
                ), playerId);
                // 推送更新后的房间信息
                broadcastToRoom(updatedRoom, Map.of(
                        "type", "ROOM_UPDATE",
                        "room", updatedRoom.toRoomInfoMap()
                ), null);
            }
        }

        sessionManager.removeSession(session.getId());

        // 广播房间列表更新
        broadcastRoomList();
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        System.err.println("[异常] WebSocket 传输异常: " + exception.getMessage());
        // 让 afterConnectionClosed 处理清理逻辑
    }

    // ──────────────────────────────────────────────
    // 消息处理
    // ──────────────────────────────────────────────

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        PlayerSession playerSession = sessionManager.getBySessionId(session.getId());
        if (playerSession == null) {
            sendJson(session, Map.of("type", "ERROR", "message", "未找到玩家会话"));
            return;
        }

        Map<String, Object> msg;
        try {
            msg = objectMapper.readValue(message.getPayload(),
                    new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            sendJson(session, Map.of("type", "ERROR", "message", "消息格式错误"));
            return;
        }

        String type = (String) msg.get("type");
        if (type == null) {
            sendJson(session, Map.of("type", "ERROR", "message", "缺少 type 字段"));
            return;
        }

        switch (type) {
            case "HEARTBEAT"     -> handleHeartbeat(session);
            case "CREATE_ROOM"   -> handleCreateRoom(session, playerSession, msg);
            case "JOIN_ROOM"     -> handleJoinRoom(session, playerSession, msg);
            case "LEAVE_ROOM"    -> handleLeaveRoom(session, playerSession, msg);
            case "ROOM_LIST"     -> pushRoomListToPlayer(session.getId());
            case "PLAYER_READY"  -> handlePlayerReady(session, playerSession, msg);
            case "START_GAME"    -> handleStartGame(session, playerSession, msg);
            default -> sendJson(session, Map.of(
                    "type", "ERROR",
                    "message", "未知消息类型: " + type
            ));
        }
    }

    // ──────────────────────────────────────────────
    // 消息处理实现
    // ──────────────────────────────────────────────

    private void handleHeartbeat(WebSocketSession session) {
        sendJson(session, Map.of("type", "HEARTBEAT_ACK"));
    }

    @SuppressWarnings("unchecked")
    private void handleCreateRoom(WebSocketSession session, PlayerSession playerSession, Map<String, Object> msg) {
        String roomName = (String) msg.getOrDefault("roomName",
                playerSession.getPlayer().getName() + "的房间");
        int maxPlayers = msg.containsKey("maxPlayers")
                ? ((Number) msg.get("maxPlayers")).intValue()
                : 8;

        GameRoom room = roomService.createRoom(roomName, playerSession.getPlayer(), maxPlayers);

        // 通知创建者
        sendJson(session, Map.of(
                "type", "ROOM_CREATED",
                "room", room.toRoomInfoMap()
        ));

        // 广播房间列表更新给所有人
        broadcastRoomList();
    }

    @SuppressWarnings("unchecked")
    private void handleJoinRoom(WebSocketSession session, PlayerSession playerSession, Map<String, Object> msg) {
        String roomId = (String) msg.get("roomId");
        if (roomId == null) {
            sendJson(session, Map.of("type", "ERROR", "message", "缺少 roomId"));
            return;
        }

        GameRoom room = roomService.getRoom(roomId);
        if (room == null) {
            sendJson(session, Map.of("type", "ERROR", "message", "房间不存在"));
            return;
        }

        boolean joined = roomService.joinRoom(roomId, playerSession.getPlayer());
        if (!joined) {
            sendJson(session, Map.of("type", "ERROR", "message", "加入房间失败（房间已满或已开始）"));
            return;
        }

        // 刷新房间信息
        GameRoom updatedRoom = roomService.getRoom(roomId);

        // 通知加入者
        sendJson(session, Map.of(
                "type", "ROOM_JOINED",
                "room", updatedRoom.toRoomInfoMap()
        ));

        // 通知房间内其他人
        broadcastToRoom(updatedRoom, Map.of(
                "type", "PLAYER_JOINED",
                "playerId", playerSession.getPlayer().getPlayerId(),
                "playerName", playerSession.getPlayer().getName()
        ), playerSession.getPlayer().getPlayerId());

        // 给房间内所有人推送更新后的房间信息
        broadcastToRoom(updatedRoom, Map.of(
                "type", "ROOM_UPDATE",
                "room", updatedRoom.toRoomInfoMap()
        ), null);

        // 广播大厅房间列表更新
        broadcastRoomList();
    }

    private void handleLeaveRoom(WebSocketSession session, PlayerSession playerSession, Map<String, Object> msg) {
        String playerId = playerSession.getPlayer().getPlayerId();
        GameRoom room = roomService.findRoomByPlayerId(playerId);

        if (room == null) {
            sendJson(session, Map.of("type", "ERROR", "message", "你不在任何房间中"));
            return;
        }

        String roomId = room.getRoomId();
        String playerName = playerSession.getPlayer().getName();
        roomService.leaveRoom(roomId, playerId);

        // 通知离开者
        sendJson(session, Map.of("type", "ROOM_LEFT", "roomId", roomId));

        // 通知房间内其他人
        GameRoom updatedRoom = roomService.getRoom(roomId);
        if (updatedRoom != null) {
            broadcastToRoom(updatedRoom, Map.of(
                    "type", "PLAYER_LEFT",
                    "playerId", playerId,
                    "playerName", playerName
            ), playerId);
            broadcastToRoom(updatedRoom, Map.of(
                    "type", "ROOM_UPDATE",
                    "room", updatedRoom.toRoomInfoMap()
            ), null);
        }

        // 广播大厅房间列表更新
        broadcastRoomList();
    }

    @SuppressWarnings("unchecked")
    private void handlePlayerReady(WebSocketSession session, PlayerSession playerSession, Map<String, Object> msg) {
        String playerId = playerSession.getPlayer().getPlayerId();
        GameRoom room = roomService.findRoomByPlayerId(playerId);

        if (room == null) {
            sendJson(session, Map.of("type", "ERROR", "message", "你不在任何房间中"));
            return;
        }

        boolean ready = msg.containsKey("ready")
                ? Boolean.TRUE.equals(msg.get("ready"))
                : true;

        // 更新玩家准备状态
        room.getPlayers().stream()
                .filter(p -> p.getPlayerId().equals(playerId))
                .findFirst()
                .ifPresent(p -> p.setReady(ready));

        // 广播更新给房间所有人
        broadcastToRoom(room, Map.of(
                "type", "ROOM_UPDATE",
                "room", room.toRoomInfoMap()
        ), null);
    }

    @SuppressWarnings("unchecked")
    private void handleStartGame(WebSocketSession session, PlayerSession playerSession, Map<String, Object> msg) {
        String playerId = playerSession.getPlayer().getPlayerId();
        GameRoom room = roomService.findRoomByPlayerId(playerId);

        if (room == null) {
            sendJson(session, Map.of("type", "ERROR", "message", "你不在任何房间中"));
            return;
        }

        // 只有房主可以开始游戏
        if (!room.getOwnerPlayerId().equals(playerId)) {
            sendJson(session, Map.of("type", "ERROR", "message", "只有房主可以开始游戏"));
            return;
        }

        // 检查是否所有人都准备了
        boolean allReady = room.getPlayers().stream().allMatch(p -> p.isReady());
        if (!allReady) {
            sendJson(session, Map.of("type", "ERROR", "message", "还有玩家未准备"));
            return;
        }

        // 最小人数检查（至少 2 人）
        if (room.getPlayerCount() < 2) {
            sendJson(session, Map.of("type", "ERROR", "message", "至少需要 2 名玩家"));
            return;
        }

        // 更新房间状态
        room.setStatus(RoomStatus.IN_PROGRESS);

        // 广播游戏开始（后续由游戏引擎接管）
        broadcastToRoom(room, Map.of(
                "type", "GAME_START",
                "room", room.toRoomInfoMap()
        ), null);
    }

    // ──────────────────────────────────────────────
    // 辅助方法
    // ──────────────────────────────────────────────

    /**
     * 广播房间列表给所有在线玩家
     */
    private void broadcastRoomList() {
        List<Map<String, Object>> roomList = roomService.getJoinableRooms().stream()
                .map(GameRoom::toRoomInfoMap)
                .toList();

        sessionManager.broadcast(toJson(Map.of("type", "ROOM_LIST", "rooms", roomList)));
    }

    /**
     * 向指定玩家推送房间列表
     */
    private void pushRoomListToPlayer(String sessionId) {
        List<Map<String, Object>> roomList = roomService.getJoinableRooms().stream()
                .map(GameRoom::toRoomInfoMap)
                .toList();

        sessionManager.sendMessageBySessionId(sessionId,
                toJson(Map.of("type", "ROOM_LIST", "rooms", roomList)));
    }

    /**
     * 向房间内所有玩家广播消息
     */
    private void broadcastToRoom(GameRoom room, Map<String, Object> message, String excludePlayerId) {
        List<String> playerIds;
        if (excludePlayerId != null) {
            playerIds = room.getPlayerIdList().stream()
                    .filter(id -> !id.equals(excludePlayerId))
                    .toList();
        } else {
            playerIds = room.getPlayerIdList();
        }
        sessionManager.broadcastToRoom(playerIds, toJson(message), null);
    }

    /**
     * 发送 JSON 消息
     */
    private void sendJson(WebSocketSession session, Map<String, Object> data) {
        sessionManager.sendMessageBySessionId(session.getId(), toJson(data));
    }

    /**
     * 将对象转为 JSON 字符串
     */
    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            System.err.println("[错误] JSON 序列化失败: " + e.getMessage());
            return "{\"type\":\"ERROR\",\"message\":\"内部错误\"}";
        }
    }
}