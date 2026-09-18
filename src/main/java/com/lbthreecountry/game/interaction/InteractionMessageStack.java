package com.lbthreecountry.game.interaction;

import com.lbthreecountry.websocket.WebSocketSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 交互消息栈 — 管理需要前端响应的交互消息（LIFO），支持阻塞等待
 *
 * <p>在游戏战斗阶段，后端可能需要频繁向特定玩家发送需要前端响应的交互消息
 * （例如 {@code ACTION_DECISION}）。此组件提供两种使用模式：</p>
 *
 * <h3>模式一：非阻塞（推送后立即返回）</h3>
 * <pre>{@code
 * interactionStack.push(roomId, playerId, message, sessionManager);
 * // 不等待，继续往下执行，后续由消息处理器处理响应
 * }</pre>
 *
 * <h3>模式二：阻塞（发送后原地等待前端响应）</h3>
 * <pre>{@code
 * Map<String, Object> response = interactionStack.pushAndAwait(
 *     roomId, playerId, message, sessionManager, 15);
 * // 代码停在这里！前端响应后才继续往下走
 * String action = (String) response.get("action");
 * }</pre>
 *
 * <h3>栈管理（LIFO 覆盖机制）</h3>
 * <ul>
 *   <li><b>入栈 (push)</b> — 新消息压入栈顶，立即成为"活跃消息"发送到前端</li>
 *   <li><b>覆盖</b> — 若栈中已有等待响应的消息，新消息覆盖成为栈顶</li>
 *   <li><b>出栈 (resolve)</b> — 前端响应后，栈顶出栈，等价于"完成了这个 future"，
 *       阻塞在 {@code pushAndAwait} 上的代码会恢复执行</li>
 *   <li><b>自动恢复</b> — resolve 后栈中下一条消息自动成为新的活跃消息发送到前端</li>
 * </ul>
 *
 * <p>数据结构：{@code roomId → playerId → Deque<StackEntry>}</p>
 */
@Component
public class InteractionMessageStack {

    private static final Logger log = LoggerFactory.getLogger(InteractionMessageStack.class);

    // ================================================================
    //  栈条目 — 包装消息 + 它的 CompletableFuture
    // ================================================================

    /**
     * 栈条目，包装消息体和它的 {@link CompletableFuture}
     *
     * <p>每个入栈的消息都绑定一个 future。调用方可选择：</p>
     * <ul>
     *   <li>不关心 future（非阻塞模式）</li>
     *   <li>调用 {@code future.get()} 阻塞等待（阻塞模式）</li>
     * </ul>
     * <p>当前端响应到达时，{@link #resolve} 会 {@code future.complete(response)}，
     * 唤醒所有阻塞在该 future 上的线程。</p>
     */
    private static class StackEntry {
        final Map<String, Object> message;
        final CompletableFuture<Map<String, Object>> future;

        StackEntry(Map<String, Object> message) {
            this.message = message;
            this.future = new CompletableFuture<>();
        }
    }

    /** roomId → playerId → Deque<StackEntry> */
    private final Map<String, Map<String, Deque<StackEntry>>> stacks = new HashMap<>();

    // ================================================================
    //  核心操作 — 入栈
    // ================================================================

    /**
     * 压入一条交互消息（非阻塞）
     *
     * <p>消息压入栈顶并发送到前端，调用方不等待响应直接返回。</p>
     *
     * @param roomId         房间 ID
     * @param playerId       目标玩家 ID
     * @param message        交互消息体
     * @param sessionManager 会话管理器
     */
    public void push(String roomId, String playerId, Map<String, Object> message,
                     WebSocketSessionManager sessionManager) {
        Objects.requireNonNull(roomId, "roomId must not be null");
        Objects.requireNonNull(playerId, "playerId must not be null");
        Objects.requireNonNull(message, "message must not be null");

        doPush(roomId, playerId, message, sessionManager);
    }

    /**
     * 压入一条交互消息并阻塞等待响应（阻塞模式）
     *
     * <p>消息发送到前端后，<b>当前线程会阻塞</b>，直到前端响应到达或超时。</p>
     *
     * <p><b>⚠️ 线程安全注意：</b></p>
     * <ul>
     *   <li>此方法会阻塞调用线程，因此<b>不能在 Netty/WebSocket 的 I/O 线程</b>上调用
     *       （会导致死锁，因为响应需要 I/O 线程处理）</li>
     *   <li>必须在<b>专门的游戏线程</b>（如 {@code botScheduler}）上调用</li>
     * </ul>
     *
     * @param roomId         房间 ID
     * @param playerId       目标玩家 ID
     * @param message        交互消息体
     * @param sessionManager 会话管理器
     * @param timeoutSeconds 超时秒数（超时后返回一个带有 {@code type=TIMEOUT} 的默认响应）
     * @return 前端响应的消息体（由 {@code GameWebSocketHandler} 中的处理器传入）
     */
    public Map<String, Object> pushAndAwait(String roomId, String playerId,
                                             Map<String, Object> message,
                                             WebSocketSessionManager sessionManager,
                                             long timeoutSeconds) {
        Objects.requireNonNull(roomId, "roomId must not be null");
        Objects.requireNonNull(playerId, "playerId must not be null");
        Objects.requireNonNull(message, "message must not be null");

        StackEntry entry = doPush(roomId, playerId, message, sessionManager);

        try {
            log.debug("[消息栈] 阻塞等待 {} → {} 响应 (timeout={}s)",
                    roomId, playerId, timeoutSeconds);
            return entry.future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            log.warn("[消息栈] 等待超时 {} → {} ({}s)", roomId, playerId, timeoutSeconds);
            removeEntry(roomId, playerId, entry);
            return Map.of("type", "TIMEOUT", "action", "timeout");
        } catch (java.util.concurrent.CancellationException e) {
            log.info("[消息栈] 等待被取消 {} → {} (房间/对局已销毁)", roomId, playerId);
            removeEntry(roomId, playerId, entry);
            return Map.of("type", "CANCELLED", "action", "interrupted");
        } catch (InterruptedException e) {
            log.warn("[消息栈] 等待被中断 {} → {}", roomId, playerId);
            removeEntry(roomId, playerId, entry);
            Thread.currentThread().interrupt();
            return Map.of("type", "ERROR", "action", "interrupted");
        } catch (Exception e) {
            log.error("[消息栈] 等待异常 {} → {}", roomId, playerId, e);
            removeEntry(roomId, playerId, entry);
            return Map.of("type", "ERROR", "action", "interrupted");
        }
    }

    // ================================================================
    //  核心操作 — 出栈
    // ================================================================

    /**
     * 解析当前栈顶消息（出栈）
     *
     * <p>前端响应到达后调用此方法：</p>
     * <ol>
     *   <li>弹出栈顶条目</li>
     *   <li>用 {@code response} 完成该条目绑定的 future（唤醒阻塞的线程）</li>
     *   <li>如果栈中还有待处理消息，新的栈顶自动发送到前端</li>
     * </ol>
     *
     * @param roomId         房间 ID
     * @param playerId       目标玩家 ID
     * @param response       前端返回的响应数据
     * @param sessionManager 会话管理器（用于发送下一条消息）
     * @return 被弹出的消息体，栈为空返回 {@code null}
     */
    public Map<String, Object> resolve(String roomId, String playerId,
                                        Map<String, Object> response,
                                        WebSocketSessionManager sessionManager) {
        Deque<StackEntry> stack = getStack(roomId, playerId);
        if (stack == null || stack.isEmpty()) {
            return null;
        }

        StackEntry popped = stack.pop();
        String msgType = (String) popped.message.get("type");

        // 完成 future，唤醒阻塞的线程
        popped.future.complete(response);

        log.debug("[消息栈] 弹出 {} → {} (type={}, 剩余栈深={})",
                roomId, playerId, msgType, stack.size());

        // 如果栈中还有消息，发送新的栈顶到前端
        if (!stack.isEmpty()) {
            StackEntry next = stack.peek();
            sendToFrontend(roomId, playerId, next.message, sessionManager);
            log.debug("[消息栈] 发送下一条消息 {} → {} (type={})",
                    roomId, playerId, next.message.get("type"));
        }

        // 清理空栈
        if (stack.isEmpty()) {
            removeEmptyStack(roomId, playerId);
        }

        return popped.message;
    }

    /**
     * 获取指定玩家当前的栈深度
     *
     * @param roomId   房间 ID
     * @param playerId 玩家 ID
     * @return 栈深度（0 表示没有待处理的交互）
     */
    public int getDepth(String roomId, String playerId) {
        Deque<StackEntry> stack = getStack(roomId, playerId);
        return stack == null ? 0 : stack.size();
    }

    /**
     * 获取当前栈顶消息（不移除）
     *
     * @param roomId   房间 ID
     * @param playerId 玩家 ID
     * @return 栈顶消息体，栈为空返回 {@code null}
     */
    public Map<String, Object> peek(String roomId, String playerId) {
        Deque<StackEntry> stack = getStack(roomId, playerId);
        return (stack == null || stack.isEmpty()) ? null : stack.peek().message;
    }

    // ================================================================
    //  清理操作
    // ================================================================

    /**
     * 清除指定玩家的所有待处理消息（如玩家断线时）
     *
     * @param roomId   房间 ID
     * @param playerId 玩家 ID
     */
    public void clear(String roomId, String playerId) {
        Map<String, Deque<StackEntry>> roomStacks = stacks.get(roomId);
        if (roomStacks != null) {
            Deque<StackEntry> removed = roomStacks.remove(playerId);
            if (removed != null && !removed.isEmpty()) {
                for (StackEntry entry : removed) {
                    entry.future.cancel(true);
                }
                log.debug("[消息栈] 清除 {} → {} (共 {} 条待处理消息)",
                        roomId, playerId, removed.size());
            }
            if (roomStacks.isEmpty()) {
                stacks.remove(roomId);
            }
        }
    }

    /**
     * 清除指定房间所有玩家的待处理消息（如游戏结束时）
     *
     * @param roomId 房间 ID
     */
    public void clearRoom(String roomId) {
        Map<String, Deque<StackEntry>> removed = stacks.remove(roomId);
        if (removed != null && !removed.isEmpty()) {
            int total = 0;
            for (Deque<StackEntry> deque : removed.values()) {
                for (StackEntry entry : deque) {
                    entry.future.cancel(true);
                }
                total += deque.size();
            }
            log.debug("[消息栈] 清除房间 {} (共 {} 条待处理消息)", roomId, total);
        }
    }

    // ================================================================
    //  内部方法
    // ================================================================

    private StackEntry doPush(String roomId, String playerId, Map<String, Object> message,
                               WebSocketSessionManager sessionManager) {
        Deque<StackEntry> stack = getOrCreateStack(roomId, playerId);
        boolean wasEmpty = stack.isEmpty();

        StackEntry entry = new StackEntry(message);
        stack.push(entry);

        sendToFrontend(roomId, playerId, message, sessionManager);

        String msgType = (String) message.get("type");
        if (wasEmpty) {
            log.debug("[消息栈] 推送 {} → {} (type={}, 栈深=1)",
                    roomId, playerId, msgType);
        } else {
            log.debug("[消息栈] 推送 {} → {} (type={}, 栈深={}) — 覆盖旧消息",
                    roomId, playerId, msgType, stack.size());
        }

        return entry;
    }

    private void removeEntry(String roomId, String playerId, StackEntry target) {
        Deque<StackEntry> stack = getStack(roomId, playerId);
        if (stack != null) {
            stack.removeIf(e -> e == target);
            if (stack.isEmpty()) {
                removeEmptyStack(roomId, playerId);
            }
            // 不移除后不主动发下一条——超时场景下由下一轮游戏循环推动
        }
    }

    private void sendToFrontend(String roomId, String playerId,
                                 Map<String, Object> message,
                                 WebSocketSessionManager sessionManager) {
        try {
            String json = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(message);
            sessionManager.sendMessage(playerId, json);
        } catch (Exception e) {
            log.error("[消息栈] 发送消息失败 [roomId={}, playerId={}]", roomId, playerId, e);
        }
    }

    private Deque<StackEntry> getOrCreateStack(String roomId, String playerId) {
        return stacks.computeIfAbsent(roomId, k -> new HashMap<>())
                .computeIfAbsent(playerId, k -> new ArrayDeque<>());
    }

    private Deque<StackEntry> getStack(String roomId, String playerId) {
        Map<String, Deque<StackEntry>> roomStacks = stacks.get(roomId);
        return roomStacks == null ? null : roomStacks.get(playerId);
    }

    private void removeEmptyStack(String roomId, String playerId) {
        Map<String, Deque<StackEntry>> roomStacks = stacks.get(roomId);
        if (roomStacks != null) {
            Deque<StackEntry> stack = roomStacks.get(playerId);
            if (stack != null && stack.isEmpty()) {
                roomStacks.remove(playerId);
            }
            if (roomStacks.isEmpty()) {
                stacks.remove(roomId);
            }
        }
    }
}