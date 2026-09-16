package com.lbthreecountry.game.card;

import com.lbthreecountry.game.GameMatch;
import com.lbthreecountry.game.GamePlayer;
import com.lbthreecountry.game.event.EventBus;
import com.lbthreecountry.game.event.GameEvent;
import com.lbthreecountry.game.event.GameEventType;
import com.lbthreecountry.model.card.CardInstance;
import com.lbthreecountry.model.enums.impl.CardActionStatus;
import com.lbthreecountry.model.enums.impl.GamePhase;
import com.lbthreecountry.websocket.WebSocketSessionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 卡牌可用性检测器 — 判断每张手牌在当前游戏状态下能否被使用
 *
 * <p>此检测器通过监听游戏事件钩子来维护实时的卡牌可用性状态。
 * 后续将逐步接入各事件钩子，实现精确的状态判定。</p>
 *
 * <h3>卡牌状态（对应前端渲染样式）</h3>
 * <ul>
 *   <li>{@link CardActionStatus#PLAYABLE PLAYABLE} — 高亮，可点击使用</li>
 *   <li>{@link CardActionStatus#NOT_PLAYABLE NOT_PLAYABLE} — 灰显，附带不可用原因</li>
 *   <li>{@link CardActionStatus#NOT_CLICKABLE NOT_CLICKABLE} — 完全锁定</li>
 * </ul>
 *
 * <h3>未来事件监听规划（将逐步添加）</h3>
 * <pre>
 * ┌──────────────────────────────────────────────────────────────────┐
 * │  事件类型                    │  影响                           │
 * ├──────────────────────────────────────────────────────────────────┤
 * │  PHASE.ACTIVE.PLAY          │  进入出牌阶段 → 重新检测所有卡牌   │
 * │  PHASE.BEFORE.PLAY          │  出牌阶段前 → 重置本回合标记       │
 * │  CARD.PLAYED                │  有卡牌被使用 → 更新次数限制       │
 * │  TURN.ACTIVE                │  轮到当前玩家 → 重置回合标记       │
 * │  EQUIPMENT.CHANGE           │  装备变化 → 更新装备类卡牌状态     │
 * │  PLAYER.UPDATE              │  玩家状态变化 → 更新目标判定       │
 * └──────────────────────────────────────────────────────────────────┘
 * </pre>
 */
@Service
public class CardPlayabilityChecker {

    private static final Logger log = LoggerFactory.getLogger(CardPlayabilityChecker.class);

    private final CardManager cardManager;
    private final EventBus eventBus;
    private final WebSocketSessionManager sessionManager;
    private final ObjectMapper objectMapper;

    public CardPlayabilityChecker(CardManager cardManager, EventBus eventBus,
                                   WebSocketSessionManager sessionManager) {
        this.cardManager = cardManager;
        this.eventBus = eventBus;
        this.sessionManager = sessionManager;
        this.objectMapper = new ObjectMapper();
    }

    // ================================================================
    //  检测结果缓存（key = "matchId:playerId", value = instanceId → 检测结果）
    //
    //  缓存由 checkAllHandCards 写入，供后端其他模块快速查询。
    //  每次状态变化（摸牌、出牌、阶段切换等）会自动更新。
    // ================================================================

    /** 检测结果缓存 */
    private final ConcurrentHashMap<String, Map<Long, CardCheckResult>> resultsCache = new ConcurrentHashMap<>();

    /**
     * 生成缓存 key
     */
    private String cacheKey(GameMatch match, GamePlayer player) {
        return match.getRoomId() + ":" + player.getPlayerId();
    }

    /**
     * 查询一张卡牌的后端检测结果
     *
     * @param match      当前对局
     * @param player     卡牌持有者
     * @param instanceId 卡牌实例 ID
     * @return 检测结果，如果缓存中没有则实时检测
     */
    public CardCheckResult getCardStatus(GameMatch match, GamePlayer player, long instanceId) {
        String key = cacheKey(match, player);
        Map<Long, CardCheckResult> playerResults = resultsCache.get(key);
        if (playerResults != null && playerResults.containsKey(instanceId)) {
            return playerResults.get(instanceId);
        }
        // 缓存未命中 → 实时检测单张
        CardInstance card = player.getHandCards().stream()
                .filter(c -> c.getInstanceId() == instanceId)
                .findFirst()
                .orElse(null);
        if (card == null) {
            return new CardCheckResult(CardActionStatus.NOT_CLICKABLE, "卡牌不存在");
        }
        return checkSingleCard(match, player, card);
    }

    /**
     * 快捷查询 — 某张卡牌当前是否可用
     *
     * @param match      当前对局
     * @param player     卡牌持有者
     * @param instanceId 卡牌实例 ID
     * @return true 表示 {@link CardActionStatus#PLAYABLE}
     */
    public boolean isPlayable(GameMatch match, GamePlayer player, long instanceId) {
        return getCardStatus(match, player, instanceId).getStatus() == CardActionStatus.PLAYABLE;
    }

    /**
     * 清除指定玩家在指定对局中的缓存（对局结束时调用）
     */
    public void clearCache(GameMatch match, GamePlayer player) {
        resultsCache.remove(cacheKey(match, player));
    }

    /**
     * 清除指定对局中所有玩家的缓存（对局彻底结束时调用）
     */
    public void clearAllCache(GameMatch match) {
        String prefix = match.getRoomId() + ":";
        resultsCache.keySet().removeIf(k -> k.startsWith(prefix));
    }

    // ================================================================
    //  事件钩子注册（模板 — 后续逐步添加实际监听逻辑）
    // ================================================================

    @PostConstruct
    public void registerHooks() {
        // ── 摸牌后检测：CardManager.draw() 摸完牌后触发 ──
        eventBus.register(GameEventType.CARD_DRAW_CHECK, 0, (event, match) -> {
            String playerId = event.getData("playerId");
            log.info("[卡牌检测]玩家 {} 摸了牌 → 重新检测手牌状态并推送 HAND_STATUS", playerId);

            GamePlayer player = match.findPlayer(playerId);
            if (player != null) {
                Map<Long, CardCheckResult> results = checkAllHandCards(match, player);
                log.info("[卡牌检测]玩家 {} 手牌检测完成，{} 张牌（默认全部 NOT_CLICKABLE）",
                        playerId, results.size());
            }
        });

        // ── 战斗开始：初始手牌分发完毕，重新检测所有玩家手牌状态 ──
        eventBus.register(GameEventType.BATTLE_START, 0, (event, match) -> {
            log.info("[卡牌检测]战斗开始，重新检测所有玩家手牌状态");
            for (GamePlayer gp : match.getPlayers()) {
                checkAllHandCards(match, gp);
            }
        });

        // ── TODO: 进入出牌阶段 → 重新检测所有卡牌 ──
        // eventBus.register("PHASE.ACTIVE.PLAY", 0, (event, match) -> {
        //     // 重新计算当前玩家手牌状态并推送给前端
        // });

        // ── TODO: 阶段切换 → 更新卡牌可点击状态 ──
        // eventBus.register("PHASE.CHANGE", 0, (event, match) -> {
        //     // 不是出牌阶段 → 所有卡牌不可点击
        // });

        // ── TODO: 卡牌被使用 → 更新次数限制（如"杀"已使用） ──
        // eventBus.register("CARD.PLAYED", 0, (event, match) -> {
        //     // 更新本回合已出杀的标记
        // });

        // ── TODO: 回合开始 → 重置本回合使用标记 ──
        // eventBus.register("TURN.ACTIVE", 0, (event, match) -> {
        //     // 重置所有次数限制
        //     // 重新检测手牌状态
        // });

        log.info("[卡牌检测] 事件钩子已注册（已接入: CARD.DRAW.CHECK + BATTLE.START | 模板阶段默认 NOT_CLICKABLE）");
    }

    // ================================================================
    //  对外接口
    // ================================================================

    /**
     * 检测指定玩家的所有手牌可用性，并将结果推送给前端
     *
     * <p><b>依次遍历所有手牌 → 检测每张 → 构造 HAND_STATUS 消息 → 推送给玩家</b></p>
     *
     * <p><b>当前阶段：</b>简化模式，所有卡牌返回 {@link CardActionStatus#NOT_CLICKABLE}。
     * 后续事件钩子接入后将逐步实现精确检测。</p>
     *
     * @param match  当前对局
     * @param player 要检测的玩家
     * @return 手牌 instanceId → 检测结果的映射
     */
    public Map<Long, CardCheckResult> checkAllHandCards(GameMatch match, GamePlayer player) {
        // ── 依次遍历手牌，检测每张卡牌的状态 ──
        Map<Long, CardCheckResult> results = new LinkedHashMap<>();
        for (CardInstance card : player.getHandCards()) {
            results.put(card.getInstanceId(), checkSingleCard(match, player, card));
        }

        // ── 写入缓存，供后端其他模块快速查询 ──
        resultsCache.put(cacheKey(match, player), results);

        // ── 构造并推送 HAND_STATUS 消息给前端 ──
        pushHandStatus(match, player, results);

        return results;
    }

    /**
     * 将手牌检测结果推送给前端
     *
     * @param match   当前对局
     * @param player  检测的玩家
     * @param results 检测结果
     */
    private void pushHandStatus(GameMatch match, GamePlayer player,
                                 Map<Long, CardCheckResult> results) {
        GamePlayer curPlayer = match.currentPlayer();
        boolean isMyTurn = curPlayer != null && curPlayer.getPlayerId().equals(player.getPlayerId());

        List<Map<String, Object>> cardStatusList = results.entrySet().stream()
                .map(entry -> {
                    Map<String, Object> cardMap = new LinkedHashMap<>();
                    cardMap.put("instanceId", entry.getKey());
                    cardMap.put("status", entry.getValue().getStatus().getCode());
                    cardMap.put("statusName", entry.getValue().getStatus().name());
                    cardMap.put("reason", entry.getValue().getReason());
                    return cardMap;
                })
                .toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("type", "HAND_STATUS");
        response.put("cards", cardStatusList);
        response.put("phase", match.getCurrentPhase().name());
        response.put("isMyTurn", isMyTurn);

        sessionManager.sendMessage(player.getPlayerId(), toJson(response));

        log.info("[卡牌检测] 📤 已推送 HAND_STATUS 给玩家 {} ({} 张牌, 阶段: {}, isMyTurn: {})",
                player.getPlayerName(), cardStatusList.size(), match.getCurrentPhase(), isMyTurn);
    }

    /**
     * 检测一张手牌的可用性
     *
     * <p><b>当前阶段：</b>简化模式，所有卡牌返回 {@link CardActionStatus#NOT_CLICKABLE}。</p>
     *
     * @param match  当前对局
     * @param player 卡牌持有者
     * @param card   要检测的卡牌实例
     * @return 检测结果（目前始终为 PLAYABLE）
     */
    public CardCheckResult checkSingleCard(GameMatch match, GamePlayer player, CardInstance card) {
        // ================================================================
        //  【模板阶段】所有卡牌返回 NOT_CLICKABLE
        //  后续通过事件钩子逐步接入精确判定：
        //
        //  1. 接入 PHASE 事件 → 非出牌阶段返回 NOT_CLICKABLE
        //  2. 接入 TURN 事件 → 非当前回合玩家返回 NOT_CLICKABLE
        //  3. 接入 CARD.PLAYED 事件 → 已出杀的卡牌返回 NOT_PLAYABLE
        //  4. 接入 EQUIPMENT 事件 → 已有同类型装备返回 NOT_PLAYABLE
        //  5. 接入 PLAYER 事件 → 无合法目标返回 NOT_PLAYABLE
        //  6. 特殊牌规则逐一添加
        // ================================================================
        return new CardCheckResult(CardActionStatus.NOT_CLICKABLE, "未接入检测逻辑，默认不可用");
    }

    // ================================================================
    //  检测方法模板（后续逐步实现）
    // ================================================================

    // ── 全局前置检测模板 ──
    // private CardActionStatus checkGlobalClickable(GameMatch match, GamePlayer player) {
    //     if (match.getStatus() != GameStatus.PLAYING)     return NOT_CLICKABLE;
    //     if (player.getStatus() != PlayerStatus.ALIVE)    return NOT_CLICKABLE;
    //     if (!isCurrentTurnPlayer(match, player))         return NOT_CLICKABLE;
    //     if (match.getCurrentPhase() != GamePhase.PLAY)   return NOT_CLICKABLE;
    //     return PLAYABLE;
    // }

    // ── 卡牌级检测模板 ──
    // private CardCheckResult checkCardPlayability(GameMatch match, GamePlayer player, CardInstance card) {
    //     CardDef def = cardManager.getDef(card.getDefId());
    //     if (def == null || def.getRules() == null)       return NOT_PLAYABLE("卡牌规则未定义");
    //     if (!checkPhaseMatch(def))                       return NOT_PLAYABLE("仅可在出牌阶段使用");
    //     if (!checkPerTurnLimit(match, player, card))     return NOT_PLAYABLE("本回合已达使用次数上限");
    //     if (isEquipment(def) && !checkEquipmentSlot(...)) return NOT_PLAYABLE("已有同类型装备");
    //     if (needsTargets(def) && !hasValidTargets(...))   return NOT_PLAYABLE("无合法目标");
    //     if (!checkSpecialRules(card, player))            return NOT_PLAYABLE("特殊规则不满足");
    //     return PLAYABLE;
    // }

    // ================================================================
    //  结果封装
    // ================================================================

    /**
     * 卡牌检测结果
     *
     * <p>包含卡牌的动作状态和不可用原因（仅 NOT_PLAYABLE 时有原因文字）。</p>
     */
    public static class CardCheckResult {

        private final CardActionStatus status;
        private final String reason;

        public CardCheckResult(CardActionStatus status, String reason) {
            this.status = status;
            this.reason = reason;
        }

        /** 卡牌动作状态 */
        public CardActionStatus getStatus() {
            return status;
        }

        /** 不可用原因（仅 NOT_PLAYABLE 时有效，其余为 null） */
        public String getReason() {
            return reason;
        }
    }

    // ================================================================
    //  JSON 工具
    // ================================================================

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.error("[卡牌检测] JSON 序列化失败", e);
            return "{\"type\":\"ERROR\",\"message\":\"序列化失败\"}";
        }
    }
}