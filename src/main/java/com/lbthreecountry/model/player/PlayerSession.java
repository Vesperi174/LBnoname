package com.lbthreecountry.model.player;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import org.springframework.web.socket.WebSocketSession;

/**
 * 玩家会话 — 关联 WebSocket 连接与玩家信息
 *
 * <p>服务端用此对象管理所有在线玩家。</p>
 * <ul>
 *   <li>key = sessionId（WebSocket 会话 ID）</li>
 *   <li>value = PlayerSession</li>
 * </ul>
 */
@Data
@Builder
@AllArgsConstructor
public class PlayerSession {

    /**
     * WebSocket 会话 ID
     */
    private String sessionId;

    /**
     * 玩家信息
     */
    private PlayerInfo player;

    /**
     * WebSocket 连接
     */
    private WebSocketSession session;

    /**
     * 是否在房间中
     */
    @Builder.Default
    private boolean inRoom = false;

    /**
     * 所在房间 ID（如果 inRoom 为 true）
     */
    private String roomId;

    /**
     * 连接时间戳
     */
    @Builder.Default
    private long connectedAt = System.currentTimeMillis();

    /**
     * 判断会话是否有效
     *
     * @return 如果 WebSocket 连接是打开的，返回 true
     */
    public boolean isValid() {
        return session != null && session.isOpen();
    }
}