package com.lbthreecountry.websocket;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lbthreecountry.entity.GameRoom;
import com.lbthreecountry.entity.RoomPlayer;
import com.lbthreecountry.game.GameMatch;
import com.lbthreecountry.game.GamePlayer;
import com.lbthreecountry.game.card.CardManager;
import com.lbthreecountry.model.card.CardInstance;
import com.lbthreecountry.model.card.def.CardDef;
import com.lbthreecountry.model.enums.impl.RoomStatus;
import com.lbthreecountry.model.player.PlayerInfo;
import com.lbthreecountry.model.player.PlayerSession;
import com.lbthreecountry.service.GameService;
import com.lbthreecountry.service.RoomService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

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
 *   <li>{@code CLOSE_SEAT} — 关闭空座位（仅房主）</li>
 *   <li>{@code OPEN_SEAT} — 打开已关闭的座位（仅房主）</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class GameWebSocketHandler extends TextWebSocketHandler {

    private final WebSocketSessionManager sessionManager;
    private final RoomService roomService;
    private final GameService gameService;
    private final CardManager cardManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 机器人自动推进调度器 */
    private final ScheduledExecutorService botScheduler = Executors.newSingleThreadScheduledExecutor();

    /** 对局中是否正在执行机器人推进（roomId → true），防止重复调度 */
    private final Map<String, Boolean> botAdvancing = new ConcurrentHashMap<>();

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
        // 广播在线玩家列表
        broadcastOnlinePlayers();
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        PlayerSession playerSession = sessionManager.getBySessionId(session.getId());
        if (playerSession == null) return;

        String playerId = playerSession.getPlayer().getPlayerId();

        // 如果玩家在房间中
        GameRoom room = roomService.findRoomByPlayerId(playerId);
        if (room != null) {
            String roomId = room.getRoomId();

            // 检查游戏是否正在进行
            GameMatch match = gameService.getMatch(roomId);
            if (match != null && "PLAYING".equals(match.getStatus().name())) {
                // 游戏进行中断线 → 转为机器人
                handlePlayerToBot(room, match, playerId, playerSession.getPlayer().getName());
            } else {
                // 非游戏状态，正常离开
                roomService.leaveRoom(roomId, playerId);

                GameRoom updatedRoom = roomService.getRoom(roomId);
                if (updatedRoom != null) {
                    broadcastToRoom(updatedRoom, Map.of(
                            "type", "PLAYER_LEFT",
                            "playerId", playerId,
                            "playerName", playerSession.getPlayer().getName()
                    ), playerId);
                    broadcastToRoom(updatedRoom, Map.of(
                            "type", "ROOM_UPDATE",
                            "room", updatedRoom.toRoomInfoMap()
                    ), null);
                }
            }
        }

        sessionManager.removeSession(session.getId());
        broadcastRoomList();
        broadcastOnlinePlayers();
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
            case "START_SINGLE_PLAYER" -> handleStartSinglePlayer(session, playerSession, msg);
            case "NEXT_PHASE"    -> handleNextPhase(session, playerSession);
            case "LIST_ONLINE_PLAYERS" -> broadcastOnlinePlayers();
            case "CLOSE_SEAT"    -> handleCloseSeat(session, playerSession, msg);
            case "OPEN_SEAT"     -> handleOpenSeat(session, playerSession, msg);
            case "UPDATE_ROOM_SETTINGS" -> handleUpdateRoomSettings(session, playerSession, msg);
            case "PLAY_CARD"            -> handlePlayCard(session, playerSession, msg);
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

        // 广播房间列表和在线玩家状态更新
        broadcastRoomList();
        broadcastOnlinePlayers();
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
        broadcastOnlinePlayers();
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

        // 检查游戏是否正在进行
        GameMatch match = gameService.getMatch(roomId);
        if (match != null && "PLAYING".equals(match.getStatus().name())) {
            // 游戏进行中 → 转为机器人
            handlePlayerToBot(room, match, playerId, playerName);
            sendJson(session, Map.of("type", "ROOM_LEFT", "roomId", roomId));
            broadcastRoomList();
            broadcastOnlinePlayers();
            return;
        }

        boolean wasOwner = room.getOwnerPlayerId().equals(playerId);
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

            // 如果离开者是房主，通知新房主变更
            if (wasOwner) {
                String newOwnerId = updatedRoom.getOwnerPlayerId();
                String newOwnerName = updatedRoom.getPlayerInfoList().stream()
                        .filter(p -> p.getPlayerId().equals(newOwnerId))
                        .findFirst()
                        .map(PlayerInfo::getName)
                        .orElse("未知");
                broadcastToRoom(updatedRoom, Map.of(
                        "type", "OWNER_CHANGED",
                        "newOwnerId", newOwnerId,
                        "newOwnerName", newOwnerName
                ), null);
            }
        }

        // 广播大厅房间列表更新
        broadcastRoomList();
        broadcastOnlinePlayers();
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

        // 检查所有真人玩家是否都已准备
        boolean allRealPlayersReady = room.getPlayers().stream()
                .filter(p -> !p.isBot())
                .allMatch(RoomPlayer::isReady);
        if (!allRealPlayersReady) {
            sendJson(session, Map.of("type", "ERROR", "message", "所有玩家必须准备后才能开始游戏"));
            return;
        }

        try {
            // ── 用 Bot 填满空位（只填充未被关闭的座位） ──
            int availableSeats = room.getMaxPlayers() - room.getClosedSeats().size();
            int botsNeeded = availableSeats - room.getPlayerCount();
            if (botsNeeded > 0) {
                String roomId = room.getRoomId();
                for (int i = 0; i < botsNeeded; i++) {
                    String botId = "bot_" + roomId + "_" + (i + 1);
                    String botName = "机器人" + (i + 1);
                    PlayerInfo botInfo = PlayerInfo.builder()
                            .playerId(botId)
                            .name(botName)
                            .build();
                    roomService.joinRoom(roomId, botInfo);
                    room.getPlayers().stream()
                            .filter(p -> p.getPlayerId().equals(botId))
                            .findFirst()
                            .ifPresent(p -> {
                                p.setReady(true);
                                p.setBot(true);
                            });
                }
                System.out.println("[房间] 已添加 " + botsNeeded + " 个 Bot 填充空位");
            }

            // 从房间设置中读取身份配置
            Map<String, Object> settings = room.getRoomSettings();
            boolean doubleIntruder = false;
            if (settings != null && Boolean.TRUE.equals(settings.get("doubleIntruder"))) {
                doubleIntruder = true;
            }
            String identityConfig = doubleIntruder ? "double_intruder" : "standard";

            // 交由游戏服务创建对局
            GameMatch match = gameService.startGame(room.getRoomId(), identityConfig);

            // 为每个玩家构建其私有信息（包含手牌和身份）
            Map<String, Object> gameStartData = buildGameStartData(match, null);

            // 获取出手时间
            int turnTime = 15;
            if (settings != null && settings.get("turnTime") instanceof Number) {
                turnTime = ((Number) settings.get("turnTime")).intValue();
            }

            // 广播游戏开始基础信息给所有人
            broadcastToRoom(room, Map.of(
                    "type", "GAME_START",
                    "roomId", room.getRoomId(),
                    "players", gameStartData.get("players"),
                    "currentPlayerIndex", match.getCurrentPlayerIndex(),
                    "currentPhase", match.getCurrentPhase().name(),
                    "round", match.getCurrentRound(),
                    "totalTurns", match.getTotalTurns(),
                    "turnTime", turnTime
            ), null);

            // 私发每个玩家自己的手牌和身份
            for (GamePlayer gp : match.getPlayers()) {
                sessionManager.sendMessage(gp.getPlayerId(), toJson(Map.of(
                        "type", "YOUR_PRIVATE_INFO",
                        "playerId", gp.getPlayerId(),
                        "role", gp.getRole().name(),
                        "gameSeat", gp.getGameSeat(),
                        "handCardCount", gp.getHandCards().size()
                )));

                // 私发手牌详情（卡面数据供前端渲染）
                List<Map<String, Object>> cardList = new java.util.ArrayList<>();
                for (CardInstance card : gp.getHandCards()) {
                    CardDef def = cardManager.getDef(card.getDefId());
                    Map<String, Object> cardMap = new java.util.HashMap<>();
                    cardMap.put("instanceId", card.getInstanceId());
                    cardMap.put("defId", card.getDefId());
                    cardMap.put("name", def != null ? def.getName() : card.getDefId());
                    cardMap.put("suit", card.getSuit().name());
                    cardMap.put("point", card.getPoint());
                    cardList.add(cardMap);
                }
                sessionManager.sendMessage(gp.getPlayerId(), toJson(Map.of(
                        "type", "MY_HAND",
                        "cards", cardList
                )));
            }

            // 广播第一回合开始（含轮次和牌堆信息）
            broadcastToRoom(room, buildTurnStartMessage(match, turnTime), null);

            // 自动快速推进 PREPARE → JUDGE → DRAW → PLAY（战报可见每个阶段）
            autoAdvanceToPlay(room.getRoomId(), room);

            // 如果当前玩家是 Bot（例如全部为 Bot 的测试场景），触发自动推进
            triggerBotIfNeeded(room.getRoomId());

            // 通知大厅中的玩家房间状态已更新
            broadcastRoomList();
            broadcastOnlinePlayers();

        } catch (IllegalStateException e) {
            sendJson(session, Map.of("type", "ERROR", "message", e.getMessage()));
        }
    }

    // ──────────────────────────────────────────────
    //  出牌
    // ──────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void handlePlayCard(WebSocketSession session, PlayerSession playerSession, Map<String, Object> msg) {
        String playerId = playerSession.getPlayer().getPlayerId();
        GameRoom room = roomService.findRoomByPlayerId(playerId);
        if (room == null) {
            sendJson(session, Map.of("type", "ERROR", "message", "你不在任何房间中"));
            return;
        }

        String roomId = room.getRoomId();
        Object cardIdObj = msg.get("cardInstanceId");
        if (cardIdObj == null) {
            sendJson(session, Map.of("type", "ERROR", "message", "缺少 cardInstanceId"));
            return;
        }
        Long cardInstanceId = ((Number) cardIdObj).longValue();

        @SuppressWarnings("unchecked")
        List<String> targetIds = (List<String>) msg.get("targetIds");
        if (targetIds == null) targetIds = List.of();

        try {
            GameMatch match = gameService.playCard(roomId, playerId, cardInstanceId, targetIds);

            // 从事件总线获取出牌广播数据
            String cardName = "";
            String cardDefId = "";
            String playerName = "";
            String suit = null;
            Integer point = null;
            // 从 match 中查找打出的牌的信息，通过 EventBus 获取比较复杂
            // 改用直接从 match 和参数构建

            GamePlayer player = match.findPlayer(playerId);
            String name = player != null ? player.getPlayerName() : playerId;

            // 构建 PLAY_ACTION 广播给所有人
            Map<String, Object> actionData = new java.util.HashMap<>();
            actionData.put("type", "PLAY_ACTION");
            actionData.put("playerId", playerId);
            actionData.put("playerName", name);
            // 从消息中带过来的数据（由前端提供卡牌信息用于广播，避免二次查询）
            if (msg.containsKey("cardName")) actionData.put("cardName", msg.get("cardName"));
            if (msg.containsKey("cardDefId")) actionData.put("cardDefId", msg.get("cardDefId"));
            if (msg.containsKey("suit")) actionData.put("suit", msg.get("suit"));
            if (msg.containsKey("point")) actionData.put("point", msg.get("point"));
            actionData.put("targetIds", targetIds);

            broadcastToRoom(room, actionData, null);

            // 构建 PLAYER_UPDATE 广播给所有人（更新 HP、手牌数等）
            List<Map<String, Object>> playerUpdates = match.getPlayers().stream()
                    .map(gp -> {
                        Map<String, Object> p = new java.util.HashMap<>();
                        p.put("playerId", gp.getPlayerId());
                        p.put("currentHp", gp.getCurrentHp());
                        p.put("handCardCount", gp.getHandCards().size());
                        p.put("status", gp.getStatus().name());
                        return p;
                    })
                    .toList();

            broadcastToRoom(room, Map.of(
                    "type", "PLAYER_UPDATE",
                    "players", playerUpdates
            ), null);

            // 私发出牌玩家的新手牌
            GamePlayer gp = match.findPlayer(playerId);
            if (gp != null) {
                List<Map<String, Object>> cardList = new java.util.ArrayList<>();
                for (CardInstance card : gp.getHandCards()) {
                    CardDef def = cardManager.getDef(card.getDefId());
                    Map<String, Object> cardMap = new java.util.HashMap<>();
                    cardMap.put("instanceId", card.getInstanceId());
                    cardMap.put("defId", card.getDefId());
                    cardMap.put("name", def != null ? def.getName() : card.getDefId());
                    cardMap.put("suit", card.getSuit().name());
                    cardMap.put("point", card.getPoint());
                    cardList.add(cardMap);
                }
                sessionManager.sendMessage(playerId, toJson(Map.of(
                        "type", "MY_HAND",
                        "cards", cardList
                )));
            }

        } catch (IllegalStateException e) {
            sendJson(session, Map.of("type", "ERROR", "message", e.getMessage()));
        }
    }

    // ──────────────────────────────────────────────
    //  关闭座位
    // ──────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void handleCloseSeat(WebSocketSession session, PlayerSession playerSession, Map<String, Object> msg) {
        String playerId = playerSession.getPlayer().getPlayerId();
        GameRoom room = roomService.findRoomByPlayerId(playerId);

        if (room == null) {
            sendJson(session, Map.of("type", "ERROR", "message", "你不在任何房间中"));
            return;
        }

        // 只有房主可以关闭座位
        if (!room.getOwnerPlayerId().equals(playerId)) {
            sendJson(session, Map.of("type", "ERROR", "message", "只有房主可以关闭座位"));
            return;
        }

        // 提取 seatNumber
        Object seatObj = msg.get("seatNumber");
        if (seatObj == null) {
            sendJson(session, Map.of("type", "ERROR", "message", "缺少 seatNumber 字段"));
            return;
        }
        int seatNumber = ((Number) seatObj).intValue();

        boolean success = roomService.closeSeat(room.getRoomId(), playerId, seatNumber);
        if (!success) {
            sendJson(session, Map.of("type", "ERROR", "message",
                    "关闭座位失败：座位 " + seatNumber + " 已被占用、已关闭或至少需保留 2 个开放座位"));
            return;
        }

        System.out.println("[房间] 关闭座位 " + seatNumber + "，当前关闭的座位: " + room.getClosedSeats());

        // 广播更新后的房间信息给房间内所有人
        GameRoom updatedRoom = roomService.getRoom(room.getRoomId());
        broadcastToRoom(updatedRoom, Map.of(
                "type", "ROOM_UPDATE",
                "room", updatedRoom.toRoomInfoMap()
        ), null);

        // 广播大厅房间列表更新
        broadcastRoomList();
    }

    // ──────────────────────────────────────────────
    //  打开座位
    // ──────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void handleOpenSeat(WebSocketSession session, PlayerSession playerSession, Map<String, Object> msg) {
        String playerId = playerSession.getPlayer().getPlayerId();
        GameRoom room = roomService.findRoomByPlayerId(playerId);

        if (room == null) {
            sendJson(session, Map.of("type", "ERROR", "message", "你不在任何房间中"));
            return;
        }

        // 只有房主可以打开座位
        if (!room.getOwnerPlayerId().equals(playerId)) {
            sendJson(session, Map.of("type", "ERROR", "message", "只有房主可以打开座位"));
            return;
        }

        // 提取 seatNumber
        Object seatObj = msg.get("seatNumber");
        if (seatObj == null) {
            sendJson(session, Map.of("type", "ERROR", "message", "缺少 seatNumber 字段"));
            return;
        }
        int seatNumber = ((Number) seatObj).intValue();

        boolean success = roomService.openSeat(room.getRoomId(), playerId, seatNumber);
        if (!success) {
            sendJson(session, Map.of("type", "ERROR", "message",
                    "打开座位失败：座位 " + seatNumber + " 未关闭或无效"));
            return;
        }

        System.out.println("[房间] 打开座位 " + seatNumber + "，当前关闭的座位: " + room.getClosedSeats());

        // 广播更新后的房间信息给房间内所有人
        GameRoom updatedRoom = roomService.getRoom(room.getRoomId());
        broadcastToRoom(updatedRoom, Map.of(
                "type", "ROOM_UPDATE",
                "room", updatedRoom.toRoomInfoMap()
        ), null);

        // 广播大厅房间列表更新
        broadcastRoomList();
    }

    // ──────────────────────────────────────────────
    //  房间设置
    // ──────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void handleUpdateRoomSettings(WebSocketSession session, PlayerSession playerSession, Map<String, Object> msg) {
        String playerId = playerSession.getPlayer().getPlayerId();
        GameRoom room = roomService.findRoomByPlayerId(playerId);

        if (room == null) {
            sendJson(session, Map.of("type", "ERROR", "message", "你不在任何房间中"));
            return;
        }

        // 只有房主可以修改设置
        if (!room.getOwnerPlayerId().equals(playerId)) {
            sendJson(session, Map.of("type", "ERROR", "message", "只有房主可以修改房间设置"));
            return;
        }

        Map<String, Object> settings = (Map<String, Object>) msg.get("settings");
        if (settings == null) {
            sendJson(session, Map.of("type", "ERROR", "message", "缺少 settings 字段"));
            return;
        }

        // 更新设置
        room.getRoomSettings().putAll(settings);
        System.out.println("[房间] 设置已更新: " + settings);

        // 广播更新后的房间信息给房间内所有人
        broadcastToRoom(room, Map.of(
                "type", "ROOM_UPDATE",
                "room", room.toRoomInfoMap()
        ), null);

        // 通知修改者操作成功
        sendJson(session, Map.of("type", "ROOM_SETTINGS_UPDATED", "settings", room.getRoomSettings()));
    }

    // ──────────────────────────────────────────────
    //  单机模式
    // ──────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void handleStartSinglePlayer(WebSocketSession session, PlayerSession playerSession, Map<String, Object> msg) {
        String playerId = playerSession.getPlayer().getPlayerId();
        String playerName = playerSession.getPlayer().getName();

        int totalPlayers = msg.containsKey("totalPlayers")
                ? ((Number) msg.get("totalPlayers")).intValue()
                : 8;
        String identityConfig = (String) msg.getOrDefault("identityConfig", "standard");

        try {
            // 后端统一创建房间 + 填充 Bot + 分配身份
            GameMatch match = gameService.startSinglePlayer(playerId, playerName, totalPlayers, identityConfig);
            String roomId = match.getRoomId();
            GameRoom room = roomService.getRoom(roomId);
            if (room == null) {
                sendJson(session, Map.of("type", "ERROR", "message", "房间创建失败"));
                return;
            }

            // 广播游戏开始基础信息（Bot 无 session 不会收到，仅人类玩家收到）
            Map<String, Object> gameStartData = buildGameStartData(match, null);

            // 获取出手时间
            int turnTime = 15;
            Map<String, Object> rmSettings = room.getRoomSettings();
            if (rmSettings != null && rmSettings.get("turnTime") instanceof Number) {
                turnTime = ((Number) rmSettings.get("turnTime")).intValue();
            }

            broadcastToRoom(room, Map.of(
                    "type", "GAME_START",
                    "roomId", roomId,
                    "players", gameStartData.get("players"),
                    "currentPlayerIndex", match.getCurrentPlayerIndex(),
                    "currentPhase", match.getCurrentPhase().name(),
                    "round", match.getCurrentRound(),
                    "totalTurns", match.getTotalTurns(),
                    "turnTime", turnTime
            ), null);

            // 私发每个玩家的手牌和身份（Bot 无 session 自动跳过）
            for (GamePlayer gp : match.getPlayers()) {
                sessionManager.sendMessage(gp.getPlayerId(), toJson(Map.of(
                        "type", "YOUR_PRIVATE_INFO",
                        "playerId", gp.getPlayerId(),
                        "role", gp.getRole().name(),
                        "gameSeat", gp.getGameSeat(),
                        "handCardCount", gp.getHandCards().size()
                )));
            }

            // 广播第一回合开始（含轮次和牌堆信息）
            broadcastToRoom(room, buildTurnStartMessage(match, turnTime), null);

            // 如果当前玩家是 Bot，触发自动推进
            triggerBotIfNeeded(roomId);

        } catch (IllegalStateException e) {
            sendJson(session, Map.of("type", "ERROR", "message", e.getMessage()));
        }
    }

    // ──────────────────────────────────────────────
    //  机器人接管
    // ──────────────────────────────────────────────

    /**
     * 将玩家转为机器人接管
     */
    private void handlePlayerToBot(GameRoom room, GameMatch match, String playerId, String playerName) {
        match.lock();
        try {
            // 标记对局中该玩家为机器人
            GamePlayer gp = match.getPlayers().stream()
                    .filter(p -> p.getPlayerId().equals(playerId))
                    .findFirst().orElse(null);
            if (gp == null) return;
            gp.setBot(true);

            log.info("[Bot] 玩家 {} 已转为机器人接管 [roomId={}]", playerName, room.getRoomId());

            // 从房间中移除该玩家（释放 playerRoomMap，允许重新加入其他房间）
            roomService.leaveRoom(room.getRoomId(), playerId);

            // 广播玩家离开/转 Bot 消息
            broadcastToRoom(room, Map.of(
                    "type", "PLAYER_LEFT",
                    "playerId", playerId,
                    "playerName", playerName,
                    "bot", true
            ), playerId);

            // 检查是否所有玩家都是 Bot → 销毁对局+房间
            boolean allBot = match.getPlayers().stream()
                    .allMatch(p -> p.isBot() || p.getStatus().name().equals("DEAD"));
            if (allBot) {
                destroyGameAndRoom(room);
                return;
            }
        } finally {
            match.unlock();
        }

        // 如果是当前玩家离开，触发 Bot 自动推进
        triggerBotIfNeeded(room.getRoomId());
    }

    /**
     * 如果当前行动玩家是 Bot，调度自动推进
     */
    private void triggerBotIfNeeded(String roomId) {
        GameMatch match = gameService.getMatch(roomId);
        if (match == null) return;

        // 防止重复调度
        if (Boolean.TRUE.equals(botAdvancing.putIfAbsent(roomId, Boolean.TRUE))) {
            return;
        }

        scheduleBotAdvance(roomId);
    }

    /**
     * 递归调度 Bot 自动推进阶段
     */
    private void scheduleBotAdvance(String roomId) {
        botScheduler.schedule(() -> {
            try {
                GameMatch match = gameService.getMatch(roomId);
                if (match == null) {
                    botAdvancing.remove(roomId);
                    return;
                }

                match.lock();
                    try {
                        GamePlayer curPlayer = match.currentPlayer();
                        if (curPlayer == null || !curPlayer.isBot()) {
                            // 当前玩家不是 Bot，停止推进
                            botAdvancing.remove(roomId);
                            return;
                        }

                        String currentPhase = match.getCurrentPhase().name();

                        if ("END".equals(currentPhase)) {
                            // 在 END 阶段 → 直接换回合
                            match = gameService.nextTurn(roomId);
                            GameRoom room2 = roomService.getRoom(roomId);
                            if (room2 != null) {
                                broadcastToRoom(room2, buildTurnStartMessage(match, 0), null);
                                // 新回合自动推进 PREPARE → JUDGE → DRAW → PLAY
                                autoAdvanceToPlay(roomId, room2);
                            }
                        } else {
                            // 非 END 阶段 → 推进阶段
                            String fromPhase = currentPhase;
                            match = gameService.nextPhase(roomId);

                            String toPhase = match.getCurrentPhase().name();
                            GameRoom room = roomService.getRoom(roomId);
                            if (room != null) {
                                String curName = match.currentPlayer() != null
                                        ? match.currentPlayer().getPlayerName() : "";
                                broadcastPhaseEvent(room, fromPhase, toPhase,
                                        match.getCurrentPlayerIndex(), curName);
                            }

                            // DISCARD → 自动推进到 END
                            if ("DISCARD".equals(toPhase) && room != null) {
                                fromPhase = toPhase;
                                match = gameService.nextPhase(roomId);
                                toPhase = match.getCurrentPhase().name();

                                String curName2 = match.currentPlayer() != null
                                        ? match.currentPlayer().getPlayerName() : "";
                                broadcastPhaseEvent(room, fromPhase, toPhase,
                                        match.getCurrentPlayerIndex(), curName2);
                            }

                            // 到达 END 阶段 → 自动切换回合
                            if ("END".equals(toPhase)) {
                                match = gameService.nextTurn(roomId);
                                GameRoom room2 = roomService.getRoom(roomId);
                                if (room2 != null) {
                                    broadcastToRoom(room2, buildTurnStartMessage(match, 0), null);
                                    // 新回合自动推进 PREPARE → JUDGE → DRAW → PLAY
                                    autoAdvanceToPlay(roomId, room2);
                                }
                            }
                        }
                    } finally {
                        match.unlock();
                    }

                // 继续检查是否需要推进（延迟 1 秒，让前端能看到阶段变化）
                scheduleBotAdvance(roomId);

            } catch (Exception e) {
                log.error("[Bot] 自动推进异常 [roomId={}]", roomId, e);
                botAdvancing.remove(roomId);
            }
        }, 1000, TimeUnit.MILLISECONDS);
    }

    /**
     * 销毁对局和房间（所有玩家离开/全部 Bot）
     */
    private void destroyGameAndRoom(GameRoom room) {
        String roomId = room.getRoomId();

        // 先广播关闭消息（room 对象还有玩家列表）
        broadcastToRoom(room, Map.of(
                "type", "ROOM_CLOSED",
                "roomId", roomId,
                "message", "所有玩家已离开，房间已销毁"
        ), null);

        gameService.endGame(roomId, null);
        gameService.removeMatch(roomId);
        roomService.removeRoom(roomId);
        botAdvancing.remove(roomId);

        // 通知所有在线玩家房间列表已更新
        broadcastRoomList();
        broadcastOnlinePlayers();

        log.info("[房间] 对局和房间已销毁 [roomId={}]", roomId);
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GameWebSocketHandler.class);

    private void handleNextPhase(WebSocketSession session, PlayerSession playerSession) {
        String playerId = playerSession.getPlayer().getPlayerId();
        GameRoom room = roomService.findRoomByPlayerId(playerId);
        if (room == null) {
            sendJson(session, Map.of("type", "ERROR", "message", "你不在任何房间中"));
            return;
        }

        try {
            GameMatch match = gameService.getMatch(room.getRoomId());
            if (match == null) {
                sendJson(session, Map.of("type", "ERROR", "message", "对局不存在"));
                return;
            }

            // 只有当前行动玩家才能推进阶段
            GamePlayer curPlayer = match.currentPlayer();
            if (curPlayer == null || !curPlayer.getPlayerId().equals(playerId)) {
                sendJson(session, Map.of("type", "ERROR", "message", "当前不是你的回合，无法操作"));
                return;
            }

            String fromPhase = match.getCurrentPhase().name();

            // ====== 第 1 次推进：fromPhase → toPhase ======
            match = gameService.nextPhase(room.getRoomId());
            String toPhase = match.getCurrentPhase().name();

            String curName = match.currentPlayer() != null ? match.currentPlayer().getPlayerName() : "";
            broadcastPhaseEvent(room, fromPhase, toPhase, match.getCurrentPlayerIndex(), curName);

            // ====== 如果到了 DISCARD，自动推进到 END ======
            if ("DISCARD".equals(toPhase)) {
                fromPhase = toPhase;
                match = gameService.nextPhase(room.getRoomId());
                toPhase = match.getCurrentPhase().name();

                curName = match.currentPlayer() != null ? match.currentPlayer().getPlayerName() : "";
                broadcastPhaseEvent(room, fromPhase, toPhase, match.getCurrentPlayerIndex(), curName);
            }

            // ====== 到了 END → 切换回合 ======
            if ("END".equals(toPhase)) {
                match = gameService.nextTurn(room.getRoomId());
                broadcastToRoom(room, buildTurnStartMessage(match, 0), null);

                // 自动快速推进 PREPARE → JUDGE → DRAW → PLAY
                autoAdvanceToPlay(room.getRoomId(), room);

                // 如果新回合玩家是 Bot，触发自动推进
                triggerBotIfNeeded(room.getRoomId());
            }
        } catch (IllegalStateException e) {
            sendJson(session, Map.of("type", "ERROR", "message", e.getMessage()));
        }
    }

    /**
     * 广播阶段变更（PHASE_CHANGE — 供前端渲染界面）
     * <p>战报（BATTLE_REPORT）由 {@link GameEventBroadcaster} 统一推送。</p>
     */
    private void broadcastPhaseEvent(GameRoom room, String fromPhase, String toPhase,
                                      int gameSeat, String playerName) {
        broadcastToRoom(room, Map.of(
                "type", "PHASE_CHANGE",
                "roomId", room.getRoomId(),
                "fromPhase", fromPhase,
                "toPhase", toPhase,
                "gameSeat", gameSeat
        ), null);
    }

    private void handleNextTurn(WebSocketSession session, PlayerSession playerSession) {
        GameRoom room = roomService.findRoomByPlayerId(playerSession.getPlayer().getPlayerId());
        if (room == null) {
            sendJson(session, Map.of("type", "ERROR", "message", "你不在任何房间中"));
            return;
        }

        try {
            GameMatch match = gameService.nextTurn(room.getRoomId());

            broadcastToRoom(room, buildTurnStartMessage(match, 0), null);

            // 自动快速推进 PREPARE → JUDGE → DRAW → PLAY
            autoAdvanceToPlay(room.getRoomId(), room);
        } catch (IllegalStateException e) {
            sendJson(session, Map.of("type", "ERROR", "message", e.getMessage()));
        }
    }

    /**
     * 快速推进 PREPARE → JUDGE → DRAW → PLAY，
     * 每次步骤都广播 PHASE_CHANGE 到前端，使战报能看到每个阶段的经过。
     * 仅在新回合开始时、且当前为 PREPARE 阶段时调用。
     */
    private void autoAdvanceToPlay(String roomId, GameRoom room) {
        try {
            for (int i = 0; i < 3; i++) {
                GameMatch match = gameService.getMatch(roomId);
                if (match == null) break;

                String fromPhase = match.getCurrentPhase().name();
                if (!"PREPARE".equals(fromPhase) && !"JUDGE".equals(fromPhase) && !"DRAW".equals(fromPhase)) {
                    break;
                }

                // ── 判定阶段：如果判定区有牌，停下来等待结算 ──
                if ("JUDGE".equals(fromPhase)) {
                    GamePlayer player = match.currentPlayer();
                    if (player != null && !player.getJudgeArea().isEmpty()) {
                        log.info("[判定] 玩家 {} 判定区有 {} 张牌，等待结算 [roomId={}]",
                                player.getPlayerName(), player.getJudgeArea().size(), roomId);
                        break; // 有判定牌，停住等待
                    }
                    // 判定区无牌，继续推进
                }

                // ── 摸牌阶段：自动从牌堆顶摸 2 张牌 ──
                if ("DRAW".equals(fromPhase)) {
                    GamePlayer player = match.currentPlayer();
                    if (player != null) {
                        // 带锁摸牌
                        gameService.drawCards(roomId, 2);

                        // 广播战报
                        broadcastToRoom(room, Map.of(
                                "type", "BATTLE_REPORT",
                                "message", player.getPlayerName() + "摸了2张牌"
                        ), null);

                        // 私发更新后的手牌
                        match = gameService.getMatch(roomId);
                        player = match.currentPlayer();
                        if (player != null) {
                            List<Map<String, Object>> cardList = new java.util.ArrayList<>();
                            for (CardInstance card : player.getHandCards()) {
                                CardDef def = cardManager.getDef(card.getDefId());
                                Map<String, Object> cardMap = new java.util.HashMap<>();
                                cardMap.put("instanceId", card.getInstanceId());
                                cardMap.put("defId", card.getDefId());
                                cardMap.put("name", def != null ? def.getName() : card.getDefId());
                                cardMap.put("suit", card.getSuit().name());
                                cardMap.put("point", card.getPoint());
                                cardList.add(cardMap);
                            }
                            sessionManager.sendMessage(player.getPlayerId(), toJson(Map.of(
                                    "type", "MY_HAND",
                                    "cards", cardList
                            )));
                        }
                    }
                }

                match = gameService.nextPhase(roomId);
                String toPhase = match.getCurrentPhase().name();

                String curName = match.currentPlayer() != null ? match.currentPlayer().getPlayerName() : "";
                broadcastPhaseEvent(room, fromPhase, toPhase, match.getCurrentPlayerIndex(), curName);
            }
        } catch (IllegalStateException e) {
            log.warn("[自动推进] 阶段推进中断: {}", e.getMessage());
        }
    }

    /**
     * 构建游戏开始时的玩家信息列表
     */
    private Map<String, Object> buildGameStartData(GameMatch match, String excludePlayerId) {
        List<Map<String, Object>> playerList = match.getPlayers().stream()
                .map(gp -> {
                    Map<String, Object> p = new java.util.HashMap<>();
                    p.put("playerId", gp.getPlayerId());
                    p.put("playerName", gp.getPlayerName());
                    p.put("gameSeat", gp.getGameSeat());
                    p.put("maxHp", gp.getMaxHp());
                    p.put("currentHp", gp.getCurrentHp());
                    p.put("handCardCount", gp.getHandCards().size());
                    p.put("kingdom", gp.getKingdom() != null ? gp.getKingdom().getCode() : null);
                    p.put("kingdomColor", gp.getKingdom() != null ? gp.getKingdom().getColor() : null);
                    p.put("bot", gp.isBot());
                    return p;
                })
                .toList();

        return Map.of("players", playerList);
    }

    // ──────────────────────────────────────────────
    // 辅助：构建 TURN_START 消息（含牌堆信息）
    // ──────────────────────────────────────────────

    /**
     * 构建回合开始消息，包含轮次、阶段、牌堆剩余数等
     */
    private Map<String, Object> buildTurnStartMessage(GameMatch match, int turnTime) {
        java.util.HashMap<String, Object> msg = new java.util.HashMap<>();
        msg.put("type", "TURN_START");
        msg.put("roomId", match.getRoomId());
        msg.put("gameSeat", match.getCurrentPlayerIndex());
        msg.put("playerName", match.currentPlayer() != null ? match.currentPlayer().getPlayerName() : "");
        msg.put("round", match.getCurrentRound());
        msg.put("totalTurns", match.getTotalTurns());
        msg.put("phase", match.getCurrentPhase().name());
        if (turnTime > 0) {
            msg.put("turnTime", turnTime);
        }
        // 牌堆信息
        msg.put("drawPileCount", match.getDrawPile() != null ? match.getDrawPile().size() : 0);
        msg.put("discardPileCount", match.getDiscardPile() != null ? match.getDiscardPile().size() : 0);
        return msg;
    }

    // ──────────────────────────────────────────────
    // 辅助方法
    // ──────────────────────────────────────────────

    /**
     * 广播房间列表给所有在线玩家
     */
    private void broadcastRoomList() {
        List<Map<String, Object>> roomList = roomService.getAllRooms().stream()
                .map(GameRoom::toRoomInfoMap)
                .toList();

        sessionManager.broadcast(toJson(Map.of("type", "ROOM_LIST", "rooms", roomList)));
    }

    /**
     * 向指定玩家推送房间列表
     */
    private void pushRoomListToPlayer(String sessionId) {
        List<Map<String, Object>> roomList = roomService.getAllRooms().stream()
                .map(GameRoom::toRoomInfoMap)
                .toList();

        sessionManager.sendMessageBySessionId(sessionId,
                toJson(Map.of("type", "ROOM_LIST", "rooms", roomList)));
    }

    /**
     * 广播在线玩家列表给所有在线玩家
     */
    private void broadcastOnlinePlayers() {
        List<Map<String, Object>> playerList = sessionManager.getAllSessions().stream()
                .map(ps -> {
                    PlayerInfo pi = ps.getPlayer();
                    String playerId = pi.getPlayerId();
                    // 判断玩家当前状态
                    String status;
                    GameRoom room = roomService.findRoomByPlayerId(playerId);
                    if (room != null) {
                        GameMatch match = gameService.getMatch(room.getRoomId());
                        if (match != null && "PLAYING".equals(match.getStatus().name())) {
                            status = "IN_GAME";
                        } else {
                            status = "IN_ROOM";
                        }
                    } else {
                        status = "ONLINE";
                    }
                    return Map.<String, Object>of(
                            "playerId", playerId,
                            "playerName", pi.getName(),
                            "status", status
                    );
                })
                .toList();

        sessionManager.broadcast(toJson(Map.of(
                "type", "ONLINE_PLAYERS",
                "players", playerList,
                "count", playerList.size()
        )));
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