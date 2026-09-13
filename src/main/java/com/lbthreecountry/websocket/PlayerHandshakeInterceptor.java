package com.lbthreecountry.websocket;

import com.lbthreecountry.config.LocalNameConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URI;
import java.util.Map;

/**
 * WebSocket 握手拦截器 — 提取玩家名称
 *
 * <p>在 WebSocket 握手阶段，从 URL 查询参数中提取玩家信息：</p>
 * <pre>
 *   ws://localhost:8080/ws/game?name=张三&avatar=url
 * </pre>
 *
 * <p>提取后将信息存入 attributes，供 {@link GameWebSocketHandler} 使用。
 * 因为本项目的定位是个人/局域网游玩，不需要校验身份，直接信任客户端传来的名称。</p>
 */
@Component
@RequiredArgsConstructor
public class PlayerHandshakeInterceptor implements HandshakeInterceptor {

    private final LocalNameConfig localNameConfig;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {

        // 从 URL 查询参数中提取玩家名称
        URI uri = request.getURI();
        String query = uri.getQuery();

        String playerName = null;
        String avatar = null;

        if (query != null && !query.isEmpty()) {
            String[] params = query.split("&");
            for (String param : params) {
                String[] kv = param.split("=", 2);
                if (kv.length == 2) {
                    String key = kv[0].toLowerCase();
                    String value = kv[1];
                    if ("name".equals(key)) {
                        playerName = value;
                    } else if ("avatar".equals(key)) {
                        avatar = value;
                    }
                }
            }
        }

        // 如果没有提供名称，使用默认名称
        if (playerName == null || playerName.isBlank()) {
            playerName = localNameConfig.getDefaultName();
        }

        // 将玩家信息存入会话属性，供后续使用
        attributes.put("playerName", playerName);
        attributes.put("avatar", avatar);

        return true;  // 允许握手
    }

    @Override
    public void afterHandshake(ServerHttpRequest request,
                               ServerHttpResponse response,
                               WebSocketHandler wsHandler,
                               Exception exception) {
        // 握手完成后无需额外操作
    }
}