package com.lbthreecountry.websocket;

import com.lbthreecountry.model.player.PlayerInfo;
import com.lbthreecountry.model.player.PlayerSession;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Collection;
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
     * 向单个玩家发送消息
     *
     * @param playerId 目标玩家 ID
     * @param message  消息内容
     */
    public void sendMessage(String playerId, String message) throws IOException {
        PlayerSession playerSession = getByPlayerId(playerId);
        if (playerSession != null && playerSession.isValid()) {
            synchronized (playerSession.getSession()) {
                playerSession.getSession().sendMessage(
                        new org.springframework.web.socket.TextMessage(message));
            }
        }
    }

    /**
     * 广播消息给所有在线玩家
     *
     * @param message 消息内容
     */
    public void broadcast(String message) throws IOException {
        for (PlayerSession playerSession : sessionMap.values()) {
            if (playerSession.isValid()) {
                synchronized (playerSession.getSession()) {
                    playerSession.getSession().sendMessage(
                            new org.springframework.web.socket.TextMessage(message));
                }
            }
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