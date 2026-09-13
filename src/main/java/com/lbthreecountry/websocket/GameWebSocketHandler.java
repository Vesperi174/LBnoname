package com.lbthreecountry.websocket;

import com.lbthreecountry.model.player.PlayerInfo;
import com.lbthreecountry.model.player.PlayerSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;

/**
 * 游戏 WebSocket 处理器
 *
 * <p>处理玩家连接、断开、消息收发等核心逻辑。
 * 玩家信息在握手阶段由 {@link PlayerHandshakeInterceptor} 提取并存入 attributes。</p>
 *
 * <h3>消息协议</h3>
 * 所有消息使用 JSON 格式。
 * 后续扩展各类游戏消息（出牌、技能、房间操作等）都在此处理。
 */
@Component
@RequiredArgsConstructor
public class GameWebSocketHandler extends TextWebSocketHandler {

    private final WebSocketSessionManager sessionManager;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // 从握手阶段存入的 attributes 中提取玩家信息
        Map<String, Object> attributes = session.getAttributes();
        String playerName = (String) attributes.get("playerName");
        String avatar = (String) attributes.get("avatar");

        // 创建玩家信息
        PlayerInfo playerInfo = PlayerInfo.create(playerName);
        if (avatar != null) {
            playerInfo.setAvatar(avatar);
        }

        // 注册会话
        PlayerSession playerSession = sessionManager.registerSession(session, playerInfo);

        // 发送欢迎消息
        String welcome = String.format(
                "{\"type\":\"CONNECTED\",\"playerId\":\"%s\",\"playerName\":\"%s\",\"message\":\"连接成功\"}",
                playerInfo.getPlayerId(), playerInfo.getName());

        sendMessage(session, welcome);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String payload = message.getPayload();

        // 获取当前会话
        PlayerSession playerSession = sessionManager.getBySessionId(session.getId());
        if (playerSession == null) {
            sendMessage(session, "{\"type\":\"ERROR\",\"message\":\"未找到玩家会话\"}");
            return;
        }

        // 处理心跳
        if ("{\"type\":\"HEARTBEAT\"}".equals(payload)) {
            sendMessage(session, "{\"type\":\"HEARTBEAT_ACK\"}");
            return;
        }

        // 其他消息类型暂不处理（后续由房间/游戏系统扩展）
        System.out.println("[消息] 来自 " + playerSession.getPlayer().getName()
                + ": " + payload);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        PlayerSession playerSession = sessionManager.removeSession(session.getId());
        if (playerSession != null) {
            System.out.println("[断开] 玩家 " + playerSession.getPlayer().getName()
                    + " 已断开，原因: " + status.getReason());
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        System.err.println("[异常] WebSocket 传输异常: " + exception.getMessage());
        sessionManager.removeSession(session.getId());
    }

    /**
     * 发送消息到 WebSocket 连接
     */
    private void sendMessage(WebSocketSession session, String message) {
        try {
            if (session.isOpen()) {
                synchronized (session) {
                    session.sendMessage(new TextMessage(message));
                }
            }
        } catch (IOException e) {
            System.err.println("[错误] 发送消息失败: " + e.getMessage());
        }
    }
}