package com.lbthreecountry.config;

import com.lbthreecountry.websocket.GameWebSocketHandler;
import com.lbthreecountry.websocket.PlayerHandshakeInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 配置
 *
 * <p>注册游戏 WebSocket 端点，配置握手拦截器。
 * 不再需要 Spring Security / JWT / CORS 等重量级安全配置。</p>
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class GameWebSocketConfig implements WebSocketConfigurer {

    private final GameWebSocketHandler gameWebSocketHandler;
    private final PlayerHandshakeInterceptor playerHandshakeInterceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(gameWebSocketHandler, "/ws/game")
                .addInterceptors(playerHandshakeInterceptor)
                .setAllowedOrigins("*");  // 允许所有来源（局域网联机场景）
    }
}