package com.lbthreecountry.websocket;

import com.lbthreecountry.model.player.PlayerInfo;
import com.lbthreecountry.model.player.PlayerSession;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 会话管理器
 *
 * <p>管理所有在线玩家的 WebSocket 会话：</p>
 * <ul>
 *   <li>sessionId → PlayerSession 映射</li>
 *   <li>playerId → sessionId 映射（方便按玩家查找）</li>
 * </ul>
 *
 * <p>线程安全（使用 ConcurrentHashMap），支持并发连接。</p>
 */
@Component
public class WebSocketSessionManager {

    /** sessionId → PlayerSession */
    private final Map<String, PlayerSession> sessionMap = new ConcurrentHashMap<>();

    /** playerId → sessionId */
    private final Map<String, String> playerSessionMap = new ConcurrentHashMap<>();

    /**
     * 注册新会话
     *
     * @param webSocketSession WebSocket 会话
     * @param playerInfo       玩家信息
     * @return 创建的 PlayerSession
     */
    public PlayerSession registerSession(WebSocketSession webSocketSession, PlayerInfo playerInfo) {
        PlayerSession playerSession = PlayerSession.builder()
                .sessionId(webSocketSession.getId())
                .player(playerInfo)
                .session(webSocketSession)
                .build();

        sessionMap.put(webSocketSession.getId(), playerSession);
        playerSessionMap.put(playerInfo.getPlayerId(), webSocketSession.getId());

        System.out.println("[会话] 玩家 " + playerInfo.getName()
                + "(" + playerInfo.getPlayerId() + ") 已连接，当前在线: " + getOnlineCount());

        return playerSession;
    }

    /**
     * 移除会话（玩家断开连接时）
     *
     * @param sessionId WebSocket 会话 ID
     * @return 被移除的 PlayerSession，如果不存在返回 null
     */
    public PlayerSession removeSession(String sessionId) {
        PlayerSession playerSession = sessionMap.remove(sessionId);
        if (playerSession != null) {
            playerSessionMap.remove(playerSession.getPlayer().getPlayerId());
            System.out.println("[会话] 玩家 " + playerSession.getPlayer().getName()
                    + " 已断开，当前在线: " + getOnlineCount());
        }
        return playerSession;
    }

    /**
     * 根据 sessionId 获取玩家会话
     */
    public PlayerSession getBySessionId(String sessionId) {
        return sessionMap.get(sessionId);
    }

    /**
     * 根据 playerId 获取玩家会话
     */
    public PlayerSession getByPlayerId(String playerId) {
        String sessionId = playerSessionMap.get(playerId);
        if (sessionId == null) {
            return null;
        }
        return sessionMap.get(sessionId);
    }

    /**
     * 获取所有在线会话
     */
    public Collection<PlayerSession> getAllSessions() {
        return sessionMap.values();
    }

    /**
     * 获取当前在线人数
     */
    public int getOnlineCount() {
        return sessionMap.size();
    }

    /**
     * 判断玩家是否在线
     */
    public boolean isOnline(String playerId) {
        return playerSessionMap.containsKey(playerId);
    }

    /**
     * 向指定 sessionId 的玩家发送消息（最底层，不抛异常）
     *
     * @param sessionId 目标会话 ID
     * @param message   消息内容
     */
    public void sendMessageBySessionId(String sessionId, String message) {
        PlayerSession playerSession = sessionMap.get(sessionId);
        if (playerSession != null && playerSession.isValid()) {
            sendRawMessage(playerSession.getSession(), message);
        }
    }

    /**
     * 向指定 playerId 的玩家发送消息
     *
     * @param playerId 目标玩家 ID
     * @param message  消息内容
     */
    public void sendMessage(String playerId, String message) {
        PlayerSession playerSession = getByPlayerId(playerId);
        if (playerSession != null && playerSession.isValid()) {
            sendRawMessage(playerSession.getSession(), message);
        }
    }

    /**
     * 向房间内所有玩家广播（排除指定玩家）
     *
     * @param roomPlayers 房间内的玩家 ID 列表
     * @param message     消息内容
     * @param excludePlayerId 排除的玩家 ID（可为 null）
     */
    public void broadcastToRoom(List<String> playerIds, String message, String excludePlayerId) {
        for (String playerId : playerIds) {
            if (excludePlayerId != null && excludePlayerId.equals(playerId)) {
                continue;
            }
            sendMessage(playerId, message);
        }
    }

    /**
     * 广播消息给所有在线玩家
     *
     * @param message 消息内容
     */
    public void broadcast(String message) {
        for (PlayerSession playerSession : sessionMap.values()) {
            if (playerSession.isValid()) {
                sendRawMessage(playerSession.getSession(), message);
            }
        }
    }

    /**
     * 原始发送（含异常处理）
     */
    private void sendRawMessage(WebSocketSession session, String message) {
        try {
            synchronized (session) {
                session.sendMessage(new org.springframework.web.socket.TextMessage(message));
            }
        } catch (IOException e) {
            System.err.println("[错误] 发送消息失败: " + e.getMessage());
        }
    }

    /**
     * 应用关闭时，关闭所有连接
     */
    @PreDestroy
    public void closeAllSessions() {
        System.out.println("[会话] 应用关闭，断开所有玩家连接...");
        for (PlayerSession playerSession : sessionMap.values()) {
            try {
                if (playerSession.isValid()) {
                    playerSession.getSession().close();
                }
            } catch (IOException e) {
                // 忽略关闭时的异常
            }
        }
        sessionMap.clear();
        playerSessionMap.clear();
    }
}