package com.lbthreecountry.exception;

/**
 * 玩家未找到异常
 *
 * <p>当根据 playerId 或 sessionId 查找玩家时，
 * 如果找不到对应的玩家会话，抛出此异常。</p>
 */
public class PlayerNotFoundException extends RuntimeException {

    public PlayerNotFoundException(String message) {
        super(message);
    }

    public PlayerNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}