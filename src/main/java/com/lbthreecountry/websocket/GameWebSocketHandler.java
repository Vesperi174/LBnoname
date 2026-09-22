package com.lbthreecountry.websocket;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lbthreecountry.entity.GameRoom;
import com.lbthreecountry.entity.RoomPlayer;
import com.lbthreecountry.game.GameMatch;
import com.lbthreecountry.game.GamePlayer;
import com.lbthreecountry.game.card.CardLibrary;
import com.lbthreecountry.game.card.CardManager;
import com.lbthreecountry.game.card.CardPlayabilityChecker;
import com.lbthreecountry.game.event.EventBus;
import com.lbthreecountry.game.event.GameEvent;
import com.lbthreecountry.game.event.GameEventType;
import com.lbthreecountry.game.event.common.DrawCardEvent;
import com.lbthreecountry.game.state.RoundStateMachine;
import com.lbthreecountry.game.hero.HeroManager;
import com.lbthreecountry.game.event.common.DistanceManager;
import com.lbthreecountry.game.interaction.InteractionMessageStack;
import com.lbthreecountry.model.card.CardInstance;
import com.lbthreecountry.model.card.def.CardCopy;
import com.lbthreecountry.model.card.def.CardDef;
import com.lbthreecountry.model.enums.impl.CardPoint;
import com.lbthreecountry.model.enums.impl.CardSuit;
import com.lbthreecountry.model.hero.BaseHero;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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
 *   <li>{@code DEV_CHEAT} — 开发调试，返回所有已加载卡牌数据（含花色点数名称）</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class GameWebSocketHandler extends TextWebSocketHandler {

    private final WebSocketSessionManager sessionManager;
    private final RoomService roomService;
    private final GameService gameService;
    private final CardManager cardManager;
    private final HeroManager heroManager;
    private final CardPlayabilityChecker cardPlayabilityChecker;
    private final EventBus eventBus;
    private final DistanceManager distanceManager;
    private final InteractionMessageStack interactionStack;
    private final RoundStateMachine roundStateMachine;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 机器人自动推进调度器 */
    private final ScheduledExecutorService botScheduler = Executors.newSingleThreadScheduledExecutor();

    /** 对局中是否正在执行机器人推进（roomId → true），防止重复调度 */
    private final Map<String, Boolean> botAdvancing = new ConcurrentHashMap<>();

    /** 等待客户端初始化完成 ACK（roomId → 未回复的 playerId 集合） */
    private final Map<String, Set<String>> pendingInitAcks = new ConcurrentHashMap<>();

    /** 所有玩家初始化完成后的回调（roomId → Runnable） */
    private final Map<String, Runnable> initCompleteCallbacks = new ConcurrentHashMap<>();

    /** 等待非主公玩家选将完成（roomId → 未选完的真人 playerId 集合） */
    private final Map<String, Set<String>> pendingHeroSelectAcks = new ConcurrentHashMap<>();

    /** 房间的 turnTime 缓存（roomId → turnTime），供选将流程后续阶段使用 */
    private final Map<String, Integer> roomTurnTimeCache = new ConcurrentHashMap<>();

    // ──────────────────────────────────────────────
    // 连接生命周期
    // ──────────────────────────────────────────────

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Map<String, Object> attributes = session.getAttributes();
        String playerName = (String) attributes.get("playerName");
        String avatar = (String) attributes.get("avatar");

        // ── 检测同名的旧会话（刷新页面重连）──
        PlayerSession oldSession = sessionManager.getByPlayerName(playerName);
        PlayerInfo playerInfo;
        if (oldSession != null) {
            // 复用旧的 PlayerInfo（保持同一 playerId），避免房间数据混乱
            playerInfo = oldSession.getPlayer();
            // 更新头像（可能变了）
            if (avatar != null) {
                playerInfo.setAvatar(avatar);
            }
            // 清理旧会话（从 sessionMap 移除并关闭底层连接）
            sessionManager.removeSessionAndClose(oldSession.getSessionId());
            System.out.println("[重连] 玩家 " + playerName + " 重新连接，复用 playerId=" + playerInfo.getPlayerId());
        } else {
            playerInfo = PlayerInfo.create(playerName);
            if (avatar != null) {
                playerInfo.setAvatar(avatar);
            }
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
            System.out.println("[连接关闭] 玩家 " + playerSession.getPlayer().getName()
                    + " 在房间 " + room.getRoomName()
                    + " (playerCount=" + room.getPlayerCount() + ")");

            // 检查游戏是否正在进行
            GameMatch match = gameService.getMatch(roomId);
            if (match != null && "PLAYING".equals(match.getStatus().name())) {
                // 游戏进行中断线 → 转为机器人
                System.out.println("[连接关闭] 游戏进行中 → 转 Bot");
                handlePlayerToBot(room, match, playerId, playerSession.getPlayer().getName());
            } else {
                // 非游戏状态，正常离开
                System.out.println("[连接关闭] 非游戏状态 → 正常离开");
                roomService.leaveRoom(roomId, playerId);

                GameRoom updatedRoom = roomService.getRoom(roomId);
                System.out.println("[连接关闭] leaveRoom 后 room=" + (updatedRoom == null ? "已删除"
                        : ("存在, playerCount=" + updatedRoom.getPlayerCount())));

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
        } else {
            System.out.println("[连接关闭] 玩家 " + playerSession.getPlayer().getName() + " 不在任何房间中");
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

        // 打印收到消息
        log.info("[收到消息] type={}, payload={}", type, message.getPayload());

        switch (type) {
            case "HEARTBEAT"     -> handleHeartbeat(session);
            case "CREATE_ROOM"   -> handleCreateRoom(session, playerSession, msg);
            case "JOIN_ROOM"     -> handleJoinRoom(session, playerSession, msg);
            case "LEAVE_ROOM"    -> handleLeaveRoom(session, playerSession, msg);
            case "ROOM_LIST"     -> pushRoomListToPlayer(session.getId());
            case "PLAYER_READY"  -> handlePlayerReady(session, playerSession, msg);
            case "START_GAME"    -> handleStartGame(session, playerSession, msg);
            case "LIST_ONLINE_PLAYERS" -> broadcastOnlinePlayers();
            case "UPDATE_ROOM_SETTINGS" -> handleUpdateRoomSettings(session, playerSession, msg);
            case "PLAY_CARD"            -> handlePlayCard(session, playerSession, msg);
            case "CHECK_CARDS"          -> handleCheckCards(session, playerSession);
            case "SELECT_HERO"          -> handleSelectHero(session, playerSession, msg);
            case "CLIENT_READY"         -> handleClientReady(playerSession);
            case "NEXT_PHASE"                    -> handleNextPhase(session, playerSession);
            case "VIEW_DISTANCE"                 -> handleViewDistance(session, playerSession, msg);
            case "ACTION_DECISION_RESPONSE"      -> handleActionDecisionResponse(playerSession, msg);
            case "DEV_CHEAT"                     -> handleDevCheat(session, playerSession);
            case "DEV_CHEAT_PICK"                -> handleDevCheatPick(session, playerSession, msg);
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

        System.out.println("[主动离开] " + playerName + " 离开 " + room.getRoomName()
                + " (playerCount=" + room.getPlayerCount() + ")");

        // 检查游戏是否正在进行
        GameMatch match = gameService.getMatch(roomId);
        if (match != null && "PLAYING".equals(match.getStatus().name())) {
            // 游戏进行中 → 转为机器人
            System.out.println("[主动离开] 游戏进行中 → 转 Bot");
            handlePlayerToBot(room, match, playerId, playerName);
            sendJson(session, Map.of("type", "ROOM_LEFT", "roomId", roomId));
            broadcastRoomList();
            broadcastOnlinePlayers();
            return;
        }

        boolean wasOwner = room.getOwnerPlayerId().equals(playerId);
        roomService.leaveRoom(roomId, playerId);

        GameRoom updatedRoom = roomService.getRoom(roomId);
        System.out.println("[主动离开] leaveRoom 后 room=" + (updatedRoom == null ? "已删除"
                : ("存在, playerCount=" + updatedRoom.getPlayerCount())));

        // 通知离开者
        sendJson(session, Map.of("type", "ROOM_LEFT", "roomId", roomId));

        // 通知房间内其他人
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
            // ── 用 Bot 填满空位 ──
            int botsNeeded = room.getMaxPlayers() - room.getPlayerCount();
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

            final int turnTime = (settings != null && settings.get("turnTime") instanceof Number)
                    ? ((Number) settings.get("turnTime")).intValue() : 15;

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

            // 广播谁是主公
            final GamePlayer lord = match.getPlayers().stream()
                    .filter(p -> p.getRole().name().equals("LORD"))
                    .findFirst().orElse(null);
            if (lord != null) {
                broadcastToRoom(room, Map.of(
                        "type", "KINGDOM_REVEAL",
                        "lordPlayerId", lord.getPlayerId(),
                        "lordPlayerName", lord.getPlayerName(),
                        "lordSeat", lord.getGameSeat()
                ), null);
            }

            // 私发每个玩家自己的手牌和身份
            for (GamePlayer gp : match.getPlayers()) {
                sessionManager.sendMessage(gp.getPlayerId(), toJson(Map.of(
                        "type", "YOUR_PRIVATE_INFO",
                        "playerId", gp.getPlayerId(),
                        "role", gp.getRole().name(),
                        "handCardCount", gp.getHandCards().size()
                )));

                // TODO: 暂不发送 MY_HAND，后续回合开始时再推
                // List<Map<String, Object>> cardList = new java.util.ArrayList<>();
                // for (CardInstance card : gp.getHandCards()) {
                //     CardDef def = cardManager.getDef(card.getDefId());
                //     Map<String, Object> cardMap = new java.util.HashMap<>();
                //     cardMap.put("instanceId", card.getInstanceId());
                //     cardMap.put("defId", card.getDefId());
                //     cardMap.put("name", def != null ? def.getName() : card.getDefId());
                //     cardMap.put("suit", card.getSuit().name());
                //     cardMap.put("point", card.getPoint());
                //     cardList.add(cardMap);
                // }
                // sessionManager.sendMessage(gp.getPlayerId(), toJson(Map.of(
                //         "type", "MY_HAND",
                //         "cards", cardList
                // )));
            }

            // ── 等待所有玩家前端初始化完毕（ACK）──
            Set<String> pending = ConcurrentHashMap.newKeySet();
            for (GamePlayer gp : match.getPlayers()) {
                // Bot 没有前端，不需要 ACK，自动算 ready
                if (!gp.isBot()) {
                    pending.add(gp.getPlayerId());
                }
            }

            if (pending.isEmpty()) {
                // 全是 Bot，直接进入武将选择
                System.out.println("[初始化等待] 全是 Bot，无需等待 ACK");
                pushHeroSelect(room, match, lord, turnTime);
            } else {
                pendingInitAcks.put(room.getRoomId(), pending);

                // 保存回调：所有人 ready 后进入武将选择
                initCompleteCallbacks.put(room.getRoomId(), () -> {
                    pushHeroSelect(room, match, lord, turnTime);
                });

                System.out.println("[初始化等待] 房间 " + room.getRoomName()
                        + " 等待 " + pending.size() + " 个真人玩家 CLIENT_READY");

                // 15 秒超时：强制继续，跳过未回复玩家
                botScheduler.schedule(() -> {
                    Set<String> remain = pendingInitAcks.remove(room.getRoomId());
                    if (remain != null && !remain.isEmpty()) {
                        System.out.println("[初始化等待] 超时！跳过 " + remain.size()
                                + " 个未回复玩家，强制继续");
                        Runnable cb = initCompleteCallbacks.remove(room.getRoomId());
                        if (cb != null) cb.run();
                    }
                }, 15, TimeUnit.SECONDS);
            }

            // 通知大厅中的玩家房间状态已更新
            broadcastRoomList();
            broadcastOnlinePlayers();

        } catch (IllegalStateException e) {
            sendJson(session, Map.of("type", "ERROR", "message", e.getMessage()));
        }
    }

    /**
     * 所有人初始化完毕后，推送武将选择给主公
     */
    private void pushHeroSelect(GameRoom room, GameMatch match, GamePlayer lord, int turnTime) {
        if (lord == null) return;

        roomTurnTimeCache.put(room.getRoomId(), turnTime);

        List<BaseHero> candidates = heroManager.pickRandomHeroes(3, List.of());
        List<Map<String, Object>> candidateList = candidates.stream()
                .map(heroManager::heroToMap)
                .toList();

        sessionManager.sendMessage(lord.getPlayerId(), toJson(Map.of(
                "type", "HERO_SELECT_OPTIONS",
                "candidates", candidateList,
                "timeout", 30
        )));

        log.info("[武将选择] 主公 {} 收到 {} 个武将候选",
                lord.getPlayerName(), candidates.size());

        if (lord.isBot() && !candidates.isEmpty()) {
            String autoHeroId = candidates.get(0).getHeroId();
            log.info("[武将选择] 主公 {} 是 Bot，自动选择武将 [{}]",
                    lord.getPlayerName(), autoHeroId);

            gameService.selectHero(room.getRoomId(), lord.getPlayerId(), autoHeroId);

            broadcastLordHero(room, lord);

            processFollowerHeroSelect(room, match);
        }
    }

    /**
     * 主公选完武将后，处理所有非主公玩家的选将：
     * Bot 自动选第一个候选，真人发候选列表等待前端回复
     */
    private void processFollowerHeroSelect(GameRoom room, GameMatch match) {
        String roomId = room.getRoomId();
        Set<String> pending = ConcurrentHashMap.newKeySet();

        for (GamePlayer gp : match.getPlayers()) {
            if (gp.getHeroId() != null) continue;

            List<BaseHero> candidates = heroManager.pickRandomHeroes(3, List.of());

            if (gp.isBot()) {
                String autoHeroId = candidates.isEmpty() ? null : candidates.get(0).getHeroId();
                if (autoHeroId != null) {
                    log.info("[武将选择] Bot 玩家 {} 自动选择武将 [{}]", gp.getPlayerName(), autoHeroId);
                    gameService.selectHero(roomId, gp.getPlayerId(), autoHeroId);
                    BaseHero hero = heroManager.getHero(autoHeroId);
                    sessionManager.sendMessage(gp.getPlayerId(), toJson(Map.of(
                            "type", "HERO_SELECTED",
                            "playerId", gp.getPlayerId(),
                            "hero", heroManager.heroToMap(hero)
                    )));
                }
            } else {
                List<Map<String, Object>> candidateList = candidates.stream()
                        .map(heroManager::heroToMap)
                        .toList();
                sessionManager.sendMessage(gp.getPlayerId(), toJson(Map.of(
                        "type", "HERO_SELECT_OPTIONS",
                        "candidates", candidateList,
                        "timeout", 30
                )));
                log.info("[武将选择] 玩家 {} 收到 {} 个武将候选", gp.getPlayerName(), candidates.size());
                pending.add(gp.getPlayerId());
            }
        }

        if (!pending.isEmpty()) {
            pendingHeroSelectAcks.put(roomId, pending);
            System.out.println("[武将选择] 等待 " + pending.size() + " 位玩家选将: " + pending);
        } else {
            checkFollowerHeroProgress(roomId);
        }
    }

    /**
     * 检查非主公玩家选将进度，全部完成则广播武将信息并分发初始手牌
     */
    private void checkFollowerHeroProgress(String roomId) {
        Set<String> pending = pendingHeroSelectAcks.get(roomId);
        if (pending != null && !pending.isEmpty()) {
            System.out.println("[武将选择] 等待中，剩余 " + pending.size() + " 人: " + pending);
            return;
        }
        if (pending != null) {
            pendingHeroSelectAcks.remove(roomId);
        }

        GameRoom room = roomService.getRoom(roomId);
        if (room == null) return;

        roomTurnTimeCache.remove(roomId);
        GameMatch updated = gameService.finalizeHeroSelection(roomId);

        // 所有武将分配完成，广播全量武将信息给前端渲染
        broadcastHeroAssignment(room, updated);

        // ── HERO_ASSIGNMENT 之后，分发初始手牌（含事件钩子） ──
        GameMatch matchWithHands = gameService.distributeInitialHands(roomId);

        // 广播初始手牌动画（通知前端渲染每人摸 4 张）
        broadcastInitialDraw(room, matchWithHands);

        // 私发每个玩家更新后的手牌（MY_HAND）和武将+手牌信息
        broadcastInitialGameState(room, matchWithHands);

        // ── 战斗开始事件钩子 + 前端广播 ──
        // 先广播 BATTLE_START 给前端，让前端做好战斗准备
        // 再把 BATTLE_START 事件发布搬到 botScheduler 上执行
        // 让整个状态机链条在游戏线程上跑，到了 PLAY 阶段可以安全地 pushAndAwait()
        broadcastToRoom(room, Map.of("type", "BATTLE_START"), null);

        GameEvent battleStartEvent = GameEvent.builder()
                .type(GameEventType.BATTLE_START)
                .sourceId("system")
                .build();
        battleStartEvent.putData("roomId", roomId);

        GameMatch finalMatch = matchWithHands;
        botScheduler.submit(() -> {
            log.info("[战斗开始] BATTLE_START 事件在 botScheduler 上发布");
            eventBus.publish(battleStartEvent, finalMatch);

            // 第 1 轮已同步跑完（在 botScheduler 上）
            if (finalMatch.isRoundFinished()) {
                asyncGameLoop(finalMatch);
            }
        });

        log.info("[战斗开始] BATTLE_START 已广播到前端，事件已提交到 botScheduler");
    }

    // ================================================================
    //  异步游戏循环
    // ================================================================

    /**
     * 异步游戏循环 — 驱动后续轮次
     *
     * <p>此方法在 {@link #botScheduler} 线程池中运行，与 Netty EventLoop 线程解耦。
     * 每轮 {@link RoundStateMachine#onRoundComplete} 在完全展开的调用栈中执行，
     * 彻底避免轮次间栈累积导致 StackOverflowError。</p>
     *
     * <h3>并发安全</h3>
     * <ul>
     *   <li>{@link com.lbthreecountry.game.GameMatch} 使用 {@code ReentrantLock} 保护</li>
     *   <li>{@link com.lbthreecountry.game.event.EventBus} 使用 {@code ConcurrentHashMap} 线程安全</li>
     *   <li>WebSocket 消息处理器通过 {@link #botAdvancing} 标记避免重复调度</li>
     * </ul>
     *
     * @param match 当前对局（BATTLE_START 处理完毕后的状态）
     */
    private void asyncGameLoop(GameMatch match) {
        botScheduler.submit(() -> {
            try {
                while (match.isRoundFinished()) {
                    match.setRoundFinished(false);
                    int round = match.getCurrentRound();
                    log.info("[游戏主循环] 🌀 第 {} 轮完成 → 驱动下一轮 (第 {} 轮)",
                            round, round + 1);
                    roundStateMachine.onRoundComplete(match);
                }
                int finalRound = match.getCurrentRound();
                if (finalRound > 0) {
                    log.info("[游戏主循环] 🏁 游戏第 {} 轮后结束（roundFinished=false，游戏终止条件已满足）",
                            finalRound);
                }
            } catch (Exception e) {
                log.error("[游戏主循环] 轮次处理异常，游戏循环终止", e);
            }
        });
    }

    /**
     * 处理前端 CLIENT_READY 消息：确认初始化完毕
     */
    private void handleClientReady(PlayerSession playerSession) {
        String playerId = playerSession.getPlayer().getPlayerId();
        GameRoom room = roomService.findRoomByPlayerId(playerId);
        if (room == null) return;

        Set<String> pending = pendingInitAcks.get(room.getRoomId());
        if (pending == null) return;

        pending.remove(playerId);
        System.out.println("[初始化ACK] " + playerSession.getPlayer().getName()
                + " 已就绪，剩余 " + pending.size() + " 人");

        if (pending.isEmpty()) {
            pendingInitAcks.remove(room.getRoomId());
            Runnable cb = initCompleteCallbacks.remove(room.getRoomId());
            if (cb != null) {
                System.out.println("[初始化ACK] 所有人就绪，进入下一阶段");
                cb.run();
            }
        }
    }

    // ──────────────────────────────────────────────
    //  距离
    // ──────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void handleViewDistance(WebSocketSession session, PlayerSession playerSession, Map<String, Object> msg) {
        String playerId = (String) msg.get("playerId");
        if (playerId == null) {
            sendJson(session, Map.of("type", "ERROR", "message", "缺少 playerId"));
            return;
        }

        GameRoom room = roomService.findRoomByPlayerId(playerId);
        if (room == null) {
            sendJson(session, Map.of("type", "ERROR", "message", "玩家不在房间中"));
            return;
        }

        GameMatch match = gameService.getMatch(room.getRoomId());
        if (match == null) {
            sendJson(session, Map.of("type", "ERROR", "message", "对局不存在"));
            return;
        }

        GamePlayer from = match.findPlayer(playerId);
        if (from == null) {
            sendJson(session, Map.of("type", "ERROR", "message", "玩家不在对局中"));
            return;
        }

        // 收集所有存活玩家，用 DistanceManager 获取距离
        java.util.Map<String, Integer> distances = new java.util.LinkedHashMap<>();
        for (GamePlayer target : match.getPlayers()) {
            if (target.isAlive() && !target.getPlayerId().equals(playerId)) {
                int dist = distanceManager.getDistance(match, from, target);
                distances.put(target.getPlayerId(), dist);
            }
        }

        sendJson(session, Map.of(
                "type", "VIEW_DISTANCE",
                "playerId", playerId,
                "distances", distances
        ));
    }

    // ──────────────────────────────────────────────
    //  武将选择
    // ──────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void handleSelectHero(WebSocketSession session, PlayerSession playerSession, Map<String, Object> msg) {
        String playerId = playerSession.getPlayer().getPlayerId();
        GameRoom room = roomService.findRoomByPlayerId(playerId);
        if (room == null) {
            sendJson(session, Map.of("type", "ERROR", "message", "你不在任何房间中"));
            return;
        }

        String roomId = room.getRoomId();
        String heroId = (String) msg.get("heroId");
        if (heroId == null || heroId.isEmpty()) {
            sendJson(session, Map.of("type", "ERROR", "message", "缺少 heroId"));
            return;
        }

        if (heroManager.getHero(heroId) == null) {
            sendJson(session, Map.of("type", "ERROR", "message", "武将不存在: " + heroId));
            return;
        }

        try {
            GameMatch match = gameService.selectHero(roomId, playerId, heroId);
            BaseHero hero = heroManager.getHero(heroId);

            // 选将后读取玩家实际体力（主公已 +1）
            GamePlayer self = match.findPlayer(playerId);

            sendJson(session, Map.of(
                    "type", "HERO_SELECTED",
                    "playerId", playerId,
                    "hero", heroManager.heroToMap(hero),
                    "maxHp", self != null ? self.getMaxHp() : 0,
                    "currentHp", self != null ? self.getCurrentHp() : 0
            ));

            log.info("[武将选择] {} 选择了 [{}]", playerSession.getPlayer().getName(), heroId);

            boolean isLord = self != null && self.getRole().name().equals("LORD");

            if (isLord) {
                broadcastLordHero(room, self);
                processFollowerHeroSelect(room, match);
            } else {
                Set<String> pending = pendingHeroSelectAcks.get(roomId);
                if (pending != null) {
                    pending.remove(playerId);
                }
                checkFollowerHeroProgress(roomId);
            }

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

            // ── 交互消息栈弹栈 ──
            // 用前端传来的消息体 (msg) 完成 pending future，唤醒阻塞的线程
            int depthAfter = interactionStack.getDepth(roomId, playerId);
            if (depthAfter > 0) {
                interactionStack.resolve(roomId, playerId, msg, sessionManager);
                log.debug("[消息栈] 出牌后弹栈，当前栈深={}", interactionStack.getDepth(roomId, playerId));
            }

        } catch (IllegalStateException e) {
            sendJson(session, Map.of("type", "ERROR", "message", e.getMessage()));
        }
    }

    // ──────────────────────────────────────────────
    //  卡牌检测
    // ──────────────────────────────────────────────

    /**
     * 处理前端卡牌可用性查询
     *
     * <p>前端在进入出牌阶段或刷新界面时发送 {@code CHECK_CARDS}，
     * 服务端返回每张手牌的 {@link com.lbthreecountry.model.enums.impl.CardActionStatus 动作状态}。</p>
     *
     * <p><b>请求（前端 → 服务端）：</b></p>
     * <pre>{@code
     * { "type": "CHECK_CARDS" }
     * }</pre>
     *
     * <p><b>响应（服务端 → 前端）：</b></p>
     * <pre>{@code
     * {
     *   "type": "HAND_STATUS",
     *   "cards": [
     *     {
     *       "instanceId": 12345,
     *       "status": 1,               // CardActionStatus 的 code
     *       "statusName": "PLAYABLE",  // 枚举名
     *       "reason": null             // 不可用的原因
     *     },
     *     ...
     *   ],
     *   "phase": "PLAY",               // 当前阶段
     *   "isMyTurn": true               // 是否是当前回合玩家
     * }
     * }</pre>
     */
    private void handleCheckCards(WebSocketSession session, PlayerSession playerSession) {
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

            GamePlayer player = match.findPlayer(playerId);
            if (player == null) {
                sendJson(session, Map.of("type", "ERROR", "message", "未找到玩家"));
                return;
            }

            // 检测所有手牌的可用性（自动推送 HAND_STATUS 给前端）
            cardPlayabilityChecker.checkAllHandCards(match, player);

        } catch (Exception e) {
            log.error("[卡牌检测] 检测失败", e);
            sendJson(session, Map.of("type", "ERROR", "message", "卡牌检测失败: " + e.getMessage()));
        }
    }

    // ──────────────────────────────────────────────
    //  DEV_CHEAT — 开发调试：返回所有已加载的卡牌
    // ──────────────────────────────────────────────

    /**
     * 处理 DEV_CHEAT 请求 — 返回所有已加载的卡牌数据（含花色、点数、名称）
     *
     * <p>前端发送 {@code { "type": "DEV_CHEAT" }} 时触发，
     * 服务端将 {@link CardLibrary} 中所有已加载的卡牌定义及其副本
     * （花色+点数）返回给请求玩家，便于前端调试展示所有卡牌信息。</p>
     *
     * <p><b>响应格式：</b></p>
     * <pre>{@code
     * {
     *   "type": "DEV_CHEAT",
     *   "cards": [
     *     {
     *       "defId": "sha",
     *       "name": "杀",
     *       "type": "BASIC",
     *       "subType": "SHA",
     *       "copies": [
     *         { "suit": "SPADES", "suitName": "黑桃", "point": 7, "pointName": "7" },
     *         { "suit": "HEARTS", "suitName": "红桃", "point": 3, "pointName": "3" }
     *       ]
     *     }
     *   ]
     * }
     * }</pre>
     */
    private void handleDevCheat(WebSocketSession session, PlayerSession playerSession) {
        // 获取所有已加载的卡牌定义
        java.util.Collection<CardDef> allDefs = cardManager.getAllDefs();

        List<Map<String, Object>> cardList = new java.util.ArrayList<>();

        for (CardDef def : allDefs) {
            Map<String, Object> cardMap = new LinkedHashMap<>();
            cardMap.put("defId", def.getId());
            cardMap.put("name", def.getName());
            cardMap.put("type", def.getType());
            cardMap.put("subType", def.getSubType());

            // 展开所有副本（花色+点数）
            List<Map<String, Object>> copyList = new java.util.ArrayList<>();
            if (def.getCopies() != null) {
                for (CardCopy copy : def.getCopies()) {
                    Map<String, Object> copyMap = new LinkedHashMap<>();
                    String suitStr = copy.getSuit();
                    int pointVal = copy.getPoint();

                    // 花色信息
                    copyMap.put("suit", suitStr);
                    try {
                        CardSuit suitEnum = CardSuit.valueOf(suitStr);
                        copyMap.put("suitName", suitEnum.getDescription());
                    } catch (IllegalArgumentException e) {
                        copyMap.put("suitName", suitStr);
                    }

                    // 点数信息
                    copyMap.put("point", pointVal);
                    CardPoint pointEnum = CardPoint.of(pointVal);
                    copyMap.put("pointName", pointEnum != null ? pointEnum.getDescription() : String.valueOf(pointVal));

                    copyList.add(copyMap);
                }
            }
            cardMap.put("copies", copyList);

            cardList.add(cardMap);
        }

        sendJson(session, Map.of(
                "type", "DEV_CHEAT",
                "cards", cardList
        ));

        log.info("[DEV_CHEAT] 已返回 {} 张卡牌定义给玩家 {}", cardList.size(), playerSession.getPlayer().getName());
    }

    // ──────────────────────────────────────────────
    //  DEV_CHEAT_PICK — 开发调试：从场上检索指定牌移到玩家手牌
    // ──────────────────────────────────────────────

    /**
     * 处理 DEV_CHEAT_PICK 请求 — 从场上所有区域检索指定卡牌并移入目标玩家手牌
     *
     * <p>前端发送：</p>
     * <pre>{@code
     * {
     *   "type": "DEV_CHEAT_PICK",
     *   "card": {
     *     "defId": "sha",
     *     "suit": "SPADES",
     *     "suitName": "黑桃",
     *     "point": 7,
     *     "pointName": "7",
     *     "playerId": "player_xxx"
     *   }
     * }
     * }</pre>
     *
     * <p>服务端搜索所有区域（摸牌堆、弃牌堆、所有玩家的手牌/装备区/判定区），
     * 找到 {@code defId + suit + point} 完全匹配的第一张牌，移入 {@code playerId}
     * 对应的玩家手牌，然后推送手牌更新和玩家状态广播。</p>
     */
    @SuppressWarnings("unchecked")
    private void handleDevCheatPick(WebSocketSession session, PlayerSession playerSession, Map<String, Object> msg) {
        Map<String, Object> cardParam = (Map<String, Object>) msg.get("card");
        if (cardParam == null) {
            sendJson(session, Map.of("type", "ERROR", "message", "缺少 card 字段"));
            return;
        }

        String targetDefId = (String) cardParam.get("defId");
        String targetSuit = (String) cardParam.get("suit");
        Object targetPointObj = cardParam.get("point");
        String targetPlayerId = (String) cardParam.get("playerId");

        if (targetDefId == null || targetSuit == null || targetPointObj == null) {
            sendJson(session, Map.of("type", "ERROR", "message", "card 缺少 defId/suit/point"));
            return;
        }
        int targetPoint = ((Number) targetPointObj).intValue();

        // 如果没有指定 targetPlayerId，默认给发送者
        if (targetPlayerId == null) {
            targetPlayerId = playerSession.getPlayer().getPlayerId();
        }

        // 查找目标玩家所在的房间和对局
        GameRoom room = roomService.findRoomByPlayerId(targetPlayerId);
        if (room == null) {
            sendJson(session, Map.of("type", "ERROR", "message", "目标玩家不在任何房间中"));
            return;
        }

        GameMatch match = gameService.getMatch(room.getRoomId());
        if (match == null) {
            sendJson(session, Map.of("type", "ERROR", "message", "对局不存在"));
            return;
        }

        GamePlayer targetPlayer = match.findPlayer(targetPlayerId);
        if (targetPlayer == null) {
            sendJson(session, Map.of("type", "ERROR", "message", "未找到目标玩家"));
            return;
        }

        match.lock();
        try {
            // 搜索所有区域，找到匹配的卡牌
            CardInstance found = findCardInGame(match, targetDefId, targetSuit, targetPoint);

            if (found == null) {
                sendJson(session, Map.of("type", "ERROR", "message",
                        "未找到匹配的卡牌: " + targetDefId + " " + targetSuit + " " + targetPoint));
                return;
            }

            // 将牌移入目标玩家手牌
            cardManager.moveToZone(match, found, "HAND", targetPlayer);

            log.info("[DEV_CHEAT_PICK] 玩家 {} 获取卡牌: {} ({} {} {}), instanceId={}",
                    targetPlayer.getPlayerName(), targetDefId,
                    targetSuit, targetPoint, found.getInstanceId());

            // 私发目标玩家更新后的手牌
            List<Map<String, Object>> cardList = new java.util.ArrayList<>();
            for (CardInstance card : targetPlayer.getHandCards()) {
                CardDef def = cardManager.getDef(card.getDefId());
                Map<String, Object> cardMap = new LinkedHashMap<>();
                cardMap.put("instanceId", card.getInstanceId());
                cardMap.put("defId", card.getDefId());
                cardMap.put("name", def != null ? def.getName() : card.getDefId());
                cardMap.put("suit", card.getSuit().name());
                cardMap.put("point", card.getPoint());
                cardList.add(cardMap);
            }
            sessionManager.sendMessage(targetPlayerId, toJson(Map.of(
                    "type", "MY_HAND",
                    "cards", cardList
            )));

            // 广播全玩家状态更新（手牌数变化）
            List<Map<String, Object>> playerUpdates = match.getPlayers().stream()
                    .map(gp -> {
                        Map<String, Object> p = new LinkedHashMap<>();
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

            // 通知请求方操作成功
            sendJson(session, Map.of(
                    "type", "DEV_CHEAT_PICK_SUCCESS",
                    "message", "已将 " + targetDefId + " 移入 " + targetPlayer.getPlayerName() + " 的手牌",
                    "instanceId", found.getInstanceId()
            ));

        } catch (Exception e) {
            log.error("[DEV_CHEAT_PICK] 操作失败", e);
            sendJson(session, Map.of("type", "ERROR", "message", "操作失败: " + e.getMessage()));
        } finally {
            match.unlock();
        }
    }

    /**
     * 在对局所有区域中搜索匹配的卡牌
     *
     * <p>搜索顺序：</p>
     * <ol>
     *   <li>所有存活玩家的手牌区</li>
     *   <li>所有存活玩家的装备区</li>
     *   <li>所有存活玩家的判定区</li>
     *   <li>摸牌堆</li>
     *   <li>弃牌堆</li>
     * </ol>
     *
     * @param match  当前对局
     * @param defId  卡牌定义 ID
     * @param suit   花色枚举名（如 "SPADES"）
     * @param point  点数（如 7）
     * @return 匹配的 CardInstance，未找到返回 null
     */
    private CardInstance findCardInGame(GameMatch match, String defId, String suit, int point) {
        // 1) 搜索所有玩家的手牌
        for (GamePlayer player : match.getPlayers()) {
            for (CardInstance card : player.getHandCards()) {
                if (card.getDefId().equals(defId)
                        && card.getSuit().name().equals(suit)
                        && card.getPoint() == point) {
                    return card;
                }
            }
        }

        // 2) 搜索所有玩家的装备区
        for (GamePlayer player : match.getPlayers()) {
            for (CardInstance card : player.getEquipCards()) {
                if (card.getDefId().equals(defId)
                        && card.getSuit().name().equals(suit)
                        && card.getPoint() == point) {
                    return card;
                }
            }
        }

        // 3) 搜索所有玩家的判定区
        for (GamePlayer player : match.getPlayers()) {
            for (CardInstance card : player.getJudgeArea()) {
                if (card.getDefId().equals(defId)
                        && card.getSuit().name().equals(suit)
                        && card.getPoint() == point) {
                    return card;
                }
            }
        }

        // 4) 搜索摸牌堆
        for (CardInstance card : match.getDrawPile()) {
            if (card.getDefId().equals(defId)
                    && card.getSuit().name().equals(suit)
                    && card.getPoint() == point) {
                return card;
            }
        }

        // 5) 搜索弃牌堆
        for (CardInstance card : match.getDiscardPile()) {
            if (card.getDefId().equals(defId)
                    && card.getSuit().name().equals(suit)
                    && card.getPoint() == point) {
                return card;
            }
        }

        return null;
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

        // ── 处理 maxPlayers 动态修改 ──
        if (settings.containsKey("maxPlayers")) {
            Object maxPlayersObj = settings.get("maxPlayers");
            int newMaxPlayers;
            if (maxPlayersObj instanceof Number) {
                newMaxPlayers = ((Number) maxPlayersObj).intValue();
            } else {
                sendJson(session, Map.of("type", "ERROR", "message", "maxPlayers 必须是数字"));
                return;
            }

            // 取值范围：2 ~ 8
            if (newMaxPlayers < 2 || newMaxPlayers > 8) {
                sendJson(session, Map.of("type", "ERROR", "message", "maxPlayers 取值范围为 2 ~ 8"));
                return;
            }

            // 不能小于当前 playerCount
            if (newMaxPlayers < room.getPlayerCount()) {
                sendJson(session, Map.of(
                        "type", "ERROR",
                        "message", "maxPlayers 不能小于当前玩家数（" + room.getPlayerCount() + "）"
                ));
                return;
            }

            // 更新房间的 maxPlayers 字段
            room.setMaxPlayers(newMaxPlayers);
            System.out.println("[房间] 最大人数已更新: " + newMaxPlayers);
        }

        // 更新其他设置
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
    //  机器人接管
    // ──────────────────────────────────────────────

    /**
     * 处理前端 ACTION_DECISION_RESPONSE — 唤醒消息栈中阻塞的线程
     *
     * <p>当玩家在前端做出出牌决策后，前端发送此消息。</p>
     *
     * <h3>消息格式</h3>
     * <pre>{@code
     * {
     *   type: "ACTION_DECISION_RESPONSE",
     *   value: "play_slash",          // 玩家点击的按钮 value
     *   selectedIds: ["1002", "pid3"] // 手牌 instanceId + 目标 playerId
     * }
     * }</pre>
     */
    @SuppressWarnings("unchecked")
    private void handleActionDecisionResponse(PlayerSession playerSession, Map<String, Object> msg) {
        String playerId = playerSession.getPlayer().getPlayerId();

        // 从 playerRoomMap 查找 roomId
        GameRoom room = roomService.findRoomByPlayerId(playerId);
        if (room == null) {
            log.warn("[ACTION_DECISION_RESPONSE] 玩家 {} 不在任何房间中", playerId);
            return;
        }

        String roomId = room.getRoomId();

        // 解析前端响应数据
        String value = (String) msg.get("value");
        List<String> selectedCardIds = (List<String>) msg.get("selectedCardIds");
        List<String> selectedPlayerIds = (List<String>) msg.get("selectedPlayerIds");

        // 构造响应体传给 resolve()，阻塞的 pushAndAwait() 会收到这个 Map
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("type", "ACTION_DECISION_RESPONSE");
        response.put("action", value != null ? value : "confirm");
        response.put("value", value);
        if (selectedCardIds != null) {
            response.put("selectedCardIds", selectedCardIds);
        }
        if (selectedPlayerIds != null) {
            response.put("selectedPlayerIds", selectedPlayerIds);
        }

        // 弹栈：完成 future，唤醒 botScheduler 上阻塞的线程
        int depthBefore = interactionStack.getDepth(roomId, playerId);
        if (depthBefore > 0) {
            interactionStack.resolve(roomId, playerId, response, sessionManager);
            log.info("[ACTION_DECISION_RESPONSE] 玩家 {} 决策: action={}, 已弹栈唤醒 (栈深={})",
                    playerId, value, depthBefore - 1);

            // ── 如果玩家选择了卡牌（非空 selectedCardIds），立即锁定其余手牌 ──
            // 禁止在卡牌效果处理/目标选择期间继续点击其他牌，被选中的牌保持 PLAYABLE
            if (selectedCardIds != null && !selectedCardIds.isEmpty()) {
                try {
                    GameMatch match = gameService.getMatch(roomId);
                    if (match != null) {
                        GamePlayer player = match.findPlayer(playerId);
                        if (player != null) {
                            List<Long> exceptIds = selectedCardIds.stream()
                                    .map(id -> {
                                        try {
                                            return Long.parseLong(id);
                                        } catch (NumberFormatException e) {
                                            return null;
                                        }
                                    })
                                    .filter(java.util.Objects::nonNull)
                                    .toList();
                            cardPlayabilityChecker.forceOthersNotSelectable(
                                    match, player, exceptIds, "已选择卡牌，请等待处理");
                            log.debug("[ACTION_DECISION_RESPONSE] 玩家 {} 已选中卡牌 {}，其余手牌已锁定",
                                    playerId, exceptIds);
                        }
                    }
                } catch (Exception e) {
                    log.warn("[ACTION_DECISION_RESPONSE] 锁定手牌失败", e);
                }
            }
        } else {
            log.warn("[ACTION_DECISION_RESPONSE] 玩家 {} 无待处理的消息栈条目", playerId);
        }
    }

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

        // 清理消息栈：唤醒所有阻塞线程 + 清除条目
        interactionStack.clearRoom(roomId);
        log.debug("[消息栈] 消息栈已清理 [roomId={}]", roomId);

        // 通知所有在线玩家房间列表已更新
        broadcastRoomList();
        broadcastOnlinePlayers();

        log.info("[房间] 对局和房间已销毁 [roomId={}]", roomId);
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GameWebSocketHandler.class);

    // ──────────────────────────────────────────────
    //  武将选择流程辅助方法
    // ──────────────────────────────────────────────

    /**
     * 主公选将后，单独先推一条消息告诉所有人主公选了哪个武将
     */
    private void broadcastLordHero(GameRoom room, GamePlayer lord) {
        String heroId = lord.getHeroId();
        BaseHero hero = heroId != null ? heroManager.getHero(heroId) : null;

        Map<String, Object> msg = new java.util.HashMap<>();
        msg.put("type", "LORD_HERO_SELECTED");
        msg.put("lordPlayerId", lord.getPlayerId());
        msg.put("lordPlayerName", lord.getPlayerName());
        msg.put("lordSeat", lord.getGameSeat());
        msg.put("heroId", heroId);
        msg.put("heroName", hero != null ? hero.getHeroName() : null);
        msg.put("maxHp", lord.getMaxHp());
        msg.put("currentHp", lord.getCurrentHp());
        msg.put("kingdom", hero != null ? hero.getKingdom() : null);

        broadcastToRoom(room, msg, null);
    }

    /**
     * 所有玩家武将分配完成后：广播全量武将信息给前端渲染
     *
     * <p>每个条目的字段与 {@code LORD_HERO_SELECTED} 保持一致（通用化字段名），
     * 前端据此渲染所有玩家的武将头像/名称/体力/势力等。</p>
     */
    private void broadcastHeroAssignment(GameRoom room, GameMatch match) {
        List<Map<String, Object>> heroList = match.getPlayers().stream()
                .map(gp -> {
                    Map<String, Object> entry = new java.util.HashMap<>();
                    entry.put("playerId", gp.getPlayerId());
                    entry.put("playerName", gp.getPlayerName());
                    entry.put("gameSeat", gp.getGameSeat());
                    entry.put("heroId", gp.getHeroId());
                    BaseHero h = gp.getHeroId() != null ? heroManager.getHero(gp.getHeroId()) : null;
                    entry.put("heroName", h != null ? h.getHeroName() : null);
                    entry.put("maxHp", gp.getMaxHp());
                    entry.put("currentHp", gp.getCurrentHp());
                    entry.put("kingdom", h != null ? h.getKingdom() : null);
                    return entry;
                })
                .toList();

        broadcastToRoom(room, Map.of(
                "type", "HERO_ASSIGNMENT",
                "heroes", heroList
        ), null);

        log.info("[武将选择] 所有武将分配完成 → HERO_ASSIGNMENT 已广播");
    }

    /**
     * 广播初始手牌动画 — 通知前端渲染每人摸牌
     *
     * <p>在分发初始手牌后调用，前端据此播放动画。</p>
     *
     * <p><b>前端消息格式：</b></p>
     * <pre>{@code
     * {
     *   "type": "INITIAL_DRAW_ACTION",
     *   "count": 4                     // 每人摸的张数
     * }
     * }</pre>
     */
    private void broadcastInitialDraw(GameRoom room, GameMatch match) {
        // 从事件数据中读取实际分发数
        int count = 4; // 默认值
        broadcastToRoom(room, Map.of(
                "type", "INITIAL_DRAW_ACTION",
                "count", count
        ), null);
        log.info("[初始手牌] INITIAL_DRAW_ACTION 已广播 (每人 {} 张)", count);
    }

    /**
     * 分发初始手牌后，私发每个玩家手牌信息并广播全玩家状态
     *
     * <p>发送内容：</p>
     * <ul>
     *   <li>每名玩家收到自己的手牌列表（{@code MY_HAND}）</li>
     *   <li>广播所有玩家的状态更新（{@code PLAYER_UPDATE}，含手牌数变化）</li>
     * </ul>
     */
    private void broadcastInitialGameState(GameRoom room, GameMatch match) {
        // 私发每个玩家自己的手牌
        for (GamePlayer gp : match.getPlayers()) {
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
            log.info("[初始手牌] 已发送 {} 的手牌 ({} 张)",
                    gp.getPlayerName(), cardList.size());
        }

        // 广播全玩家状态更新（含手牌数）
        List<Map<String, Object>> playerUpdates = match.getPlayers().stream()
                .map(gp -> {
                    Map<String, Object> p = new java.util.HashMap<>();
                    p.put("playerId", gp.getPlayerId());
                    p.put("playerName", gp.getPlayerName());
                    p.put("gameSeat", gp.getGameSeat());
                    p.put("heroId", gp.getHeroId());
                    p.put("maxHp", gp.getMaxHp());
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
        log.info("[初始手牌] PLAYER_UPDATE 已广播 ({} 人)", playerUpdates.size());
    }

    /**
     * 广播阶段变更（PHASE_CHANGE — 供前端渲染界面）
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

    /**
     * 执行摸牌阶段并广播动画
     *
     * <p>此方法封装了摸牌阶段的完整流程：</p>
     * <ol>
     *   <li>调用 {@link GameService#drawCards}（内部通过 {@link DrawCardEvent} 触发完整生命周期）</li>
     *   <li>广播 {@code DRAW_ACTION} 给所有玩家，前端据此播放摸牌动画</li>
     *   <li>私发摸牌玩家更新后的手牌（{@code MY_HAND}）</li>
     *   <li>广播所有玩家的状态更新（{@code PLAYER_UPDATE}，含手牌数变化）</li>
     * </ol>
     *
     * <p><b>前端 {@code DRAW_ACTION} 消息格式：</b></p>
     * <pre>{@code
     * {
     *   "type": "DRAW_ACTION",
     *   "playerId": "player_xxx",       // 摸牌玩家 ID
     *   "playerName": "张三",           // 摸牌玩家名称
     *   "count": 2,                     // 摸牌张数
     *   "gameSeat": 0                   // 摸牌玩家座位号
     * }
     * }</pre>
     *
     * @param roomId    房间 ID
     * @param room      房间对象（用于广播）
     * @param match     当前对局（需已加锁或调用前确保线程安全）
     * @param player    摸牌的玩家
     * @param drawCount 摸牌张数
     */
    private void broadcastDrawPhase(String roomId, GameRoom room, GameMatch match,
                                     GamePlayer player, int drawCount) {
        // 1) 执行摸牌（通过 DrawCardEvent 生命周期：BEFORE → ACTIVE → 摸牌 → AFTER）
        gameService.drawCards(roomId, drawCount);

        // 2) 广播摸牌动画到所有玩家
        broadcastToRoom(room, Map.of(
                "type", "DRAW_ACTION",
                "playerId", player.getPlayerId(),
                "playerName", player.getPlayerName(),
                "count", drawCount,
                "gameSeat", player.getGameSeat()
        ), null);

        // 3) 刷新对局数据，私发摸牌玩家更新后的手牌
        match = gameService.getMatch(roomId);
        player = match.currentPlayer();
        if (player == null) return;

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

        // 4) 广播全玩家状态更新（其他玩家看到手牌数变化）
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

        log.info("[摸牌动画] {} 摸了 {} 张牌 → DRAW_ACTION 已广播",
                player.getPlayerName(), drawCount);
    }

    /**
     * 处理前端手动推进阶段请求
     *
     * <p>玩家点击"下一阶段"时触发。当处于摸牌阶段（DRAW）时，
     * 自动执行摸牌并广播 {@code DRAW_ACTION} 动画消息。</p>
     */
    private void handleNextPhase(WebSocketSession session, PlayerSession playerSession) {
        GameRoom room = roomService.findRoomByPlayerId(playerSession.getPlayer().getPlayerId());
        if (room == null) {
            sendJson(session, Map.of("type", "ERROR", "message", "你不在任何房间中"));
            return;
        }

        String roomId = room.getRoomId();
        String playerId = playerSession.getPlayer().getPlayerId();

        try {
            GameMatch match = gameService.getMatch(roomId);
            if (match == null) {
                sendJson(session, Map.of("type", "ERROR", "message", "对局不存在"));
                return;
            }

            // 校验：只有当前回合玩家才能推进阶段
            if (!playerSession.getPlayer().getPlayerId().equals(match.currentPlayer().getPlayerId())) {
                sendJson(session, Map.of("type", "ERROR", "message", "当前不是你的回合"));
                return;
            }

            String fromPhase = match.getCurrentPhase().name();

            // ── 摸牌阶段：先摸牌再推进 ──
            if ("DRAW".equals(fromPhase)) {
                GamePlayer player = match.currentPlayer();
                broadcastDrawPhase(roomId, room, match, player, 2);
            }

            // ── 推进到下一阶段 ──
            match = gameService.nextPhase(roomId);
            String toPhase = match.getCurrentPhase().name();

            // 广播阶段变更
            String curName = match.currentPlayer() != null ? match.currentPlayer().getPlayerName() : "";
            broadcastPhaseEvent(room, fromPhase, toPhase, match.getCurrentPlayerIndex(), curName);

            // ── 如果推进到弃牌阶段(DISCARD)，自动执行弃牌后进入结束阶段 ──
            if ("DISCARD".equals(toPhase)) {
                // 自动推进到 END 阶段
                String fromDiscard = toPhase;
                match = gameService.nextPhase(roomId);
                String toEnd = match.getCurrentPhase().name();
                broadcastPhaseEvent(room, fromDiscard, toEnd, match.getCurrentPlayerIndex(), curName);
            }

            // ── 如果推进到结束阶段(END)，自动切换到下一玩家回合 ──
            if ("END".equals(toPhase) || "END".equals(match.getCurrentPhase().name())) {
                match = gameService.nextTurn(roomId);
                broadcastToRoom(room, buildTurnStartMessage(match, 0), null);
                autoAdvanceToPlay(roomId, room);
            }

            // ── 交互消息栈弹栈 ──
            // 阶段推进完成后，用默认响应完成 pending future
            int depthAfter = interactionStack.getDepth(roomId, playerId);
            if (depthAfter > 0) {
                interactionStack.resolve(roomId, playerId,
                        Map.of("type", "NEXT_PHASE", "action", "next_phase"),
                        sessionManager);
                log.debug("[消息栈] 阶段推进后弹栈，当前栈深={}", interactionStack.getDepth(roomId, playerId));
            }

        } catch (IllegalStateException e) {
            sendJson(session, Map.of("type", "ERROR", "message", e.getMessage()));
        }
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
                        broadcastDrawPhase(roomId, room, match, player, 2);
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