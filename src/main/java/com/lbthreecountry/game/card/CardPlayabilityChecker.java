package com.lbthreecountry.game.card;

import com.lbthreecountry.game.GameMatch;
import com.lbthreecountry.game.GamePlayer;
import com.lbthreecountry.game.event.EventBus;
import com.lbthreecountry.game.event.EventPriority;
import com.lbthreecountry.game.event.GameEvent;
import com.lbthreecountry.game.event.GameEventType;
import com.lbthreecountry.model.card.CardInstance;
import com.lbthreecountry.model.card.def.CardDef;
import com.lbthreecountry.model.enums.impl.CardActionStatus;
import com.lbthreecountry.model.enums.impl.GamePhase;
import com.lbthreecountry.websocket.WebSocketSessionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 卡牌可用性检测器 — 判断每张手牌在当前游戏状态下能否被使用
 *
 * <p>此检测器通过监听游戏事件钩子来维护实时的卡牌可用性状态。
 * 后续将逐步接入各事件钩子，实现精确的状态判定。</p>
 *
 * <h3>卡牌状态（对应前端渲染样式）</h3>
 * <ul>
 *   <li>{@link CardActionStatus#PLAYABLE PLAYABLE} — 亮（正常渲染），可点击选择</li>
 *   <li>{@link CardActionStatus#NOT_SELECTABLE NOT_SELECTABLE} — 暗（30% 黑色半透明遮罩），不可点击</li>
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
            return new CardCheckResult(CardActionStatus.NOT_SELECTABLE, "卡牌不存在");
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
        // ── 摸牌后：仅新摸到的牌不可选，已有牌保持原状态 ──
        eventBus.register(GameEventType.CARD_DRAW_CHECK, EventPriority.EQUIP_CARD, (event, match) -> {
            String playerId = event.getData("playerId");
            log.info("[卡牌检测]玩家 {} 摸了牌 → 新牌强制 NOT_SELECTABLE", playerId);

            GamePlayer player = match.findPlayer(playerId);
            if (player == null) return;

            // 取出新摸牌的 instanceId 列表
            List<Long> drawnIds = event.getData("drawnInstanceIds");
            if (drawnIds == null || drawnIds.isEmpty()) return;

            // 从缓存中获取已有结果，没有则全部重新检测
            String key = cacheKey(match, player);
            Map<Long, CardCheckResult> results = resultsCache.get(key);
            if (results == null) {
                // 无缓存 → 所有牌全量检测
                results = checkAllHandCards(match, player);
                return;
            }

            // 只覆盖新摸的牌为 NOT_SELECTABLE，已缓存的保留不变
            for (Long id : drawnIds) {
                results.put(id, new CardCheckResult(CardActionStatus.NOT_SELECTABLE, "刚摸到的牌不可使用"));
            }
            resultsCache.put(key, results);
            pushHandStatus(match, player, results);
        });

        // ── 战斗开始：初始手牌分发完毕，所有卡牌强制不可点击 ──
        eventBus.register(GameEventType.BATTLE_START, EventPriority.EQUIP_CARD, (event, match) -> {
            log.info("[卡牌检测]战斗开始，所有玩家手牌强制不可点击");
            for (GamePlayer gp : match.getPlayers()) {
                forceAllNotSelectable(match, gp, "非出牌阶段不可使用");
            }
        });

        // ── 进入出牌阶段 → 重新检测当前玩家手牌 ──
        eventBus.register(GameEventType.phaseActive(GamePhase.PLAY), EventPriority.EQUIP_CARD, (event, match) -> {
            GamePlayer current = match.currentPlayer();
            if (current != null) {
                log.info("[卡牌检测]玩家 {} 进入出牌阶段 → 重新检测手牌状态", current.getPlayerId());
                checkAllHandCards(match, current);
            }
        });

        // ── TODO: 阶段切换 → 非出牌阶段时推送全暗状态（当前 checkSingleCard 已自动处理） ──

        // ── TODO: 卡牌被使用 → 更新次数限制（如"杀"已使用） ──
        // eventBus.register("CARD.PLAYED", EventPriority.EQUIP_CARD, (event, match) -> {
        //     // 更新本回合已出杀的标记
        // });

        // ── 回合开始前 → 重置本回合使用标记 ──
        eventBus.register(GameEventType.TURN_BEFORE, EventPriority.EQUIP_CARD, (event, match) -> {
            String playerId = event.getData("playerId");
            GamePlayer player = match.findPlayer(playerId);
            if (player == null) return;
            player.getTurnUsedCounts().clear();
            log.debug("[卡牌检测] 玩家 {} 回合开始 → 重置本回合使用次数计数", playerId);
        });

        log.info("[卡牌检测] 事件钩子已注册（已接入: CARD.DRAW.CHECK + BATTLE.START 均强制不可点击）");
    }

    // ================================================================
    //  对外接口
    // ================================================================

    /**
     * 检测指定玩家的所有手牌可用性，并将结果推送给前端
     *
     * <p><b>依次遍历所有手牌 → 检测每张 → 构造 HAND_STATUS 消息 → 推送给玩家</b></p>
     *
     * <p><b>当前阶段：</b>简化模式，所有卡牌返回 {@link CardActionStatus#NOT_SELECTABLE}。
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
     * 强制玩家所有手牌为不可点击状态，并推送 HAND_STATUS 给前端
     *
     * @param match  当前对局
     * @param player 目标玩家
     * @param reason 不可用原因文字
     */
    public void forceAllNotSelectable(GameMatch match, GamePlayer player, String reason) {
        Map<Long, CardCheckResult> results = new LinkedHashMap<>();
        for (CardInstance card : player.getHandCards()) {
            results.put(card.getInstanceId(),
                    new CardCheckResult(CardActionStatus.NOT_SELECTABLE, reason));
        }
        resultsCache.put(cacheKey(match, player), results);
        pushHandStatus(match, player, results);
    }

    /**
     * 强制指定卡牌以外的所有手牌不可选，并推送 HAND_STATUS 给前端
     *
     * <p>用于出牌阶段玩家点击一张牌后，锁定其余手牌，
     * 被点击的牌仍保持 {@link CardActionStatus#PLAYABLE PLAYABLE} 状态，
     * 让前端可以将其高亮显示为"已选中"。</p>
     *
     * @param match            当前对局
     * @param player           目标玩家
     * @param exceptInstanceIds 保持可选的手牌 instanceId 列表（被点击的那张）
     * @param reason           其它牌不可用的原因文字
     */
    public void forceOthersNotSelectable(GameMatch match, GamePlayer player,
                                          List<Long> exceptInstanceIds, String reason) {
        Map<Long, CardCheckResult> results = new LinkedHashMap<>();
        Set<Long> exceptSet = exceptInstanceIds != null
                ? new HashSet<>(exceptInstanceIds)
                : Collections.emptySet();

        for (CardInstance card : player.getHandCards()) {
            long id = card.getInstanceId();
            if (exceptSet.contains(id)) {
                // 选中的牌保持 PLAYABLE（前端可高亮显示为"已选中"）
                results.put(id, new CardCheckResult(CardActionStatus.PLAYABLE, null));
            } else {
                results.put(id, new CardCheckResult(CardActionStatus.NOT_SELECTABLE, reason));
            }
        }
        resultsCache.put(cacheKey(match, player), results);
        pushHandStatus(match, player, results);

        if (exceptInstanceIds != null && !exceptInstanceIds.isEmpty()) {
            log.debug("[卡牌检测] 玩家 {} 选中卡牌 {}，其余 {} 张手牌已锁定",
                    player.getPlayerName(), exceptInstanceIds,
                    player.getHandCards().size() - exceptInstanceIds.size());
        }
    }

    /**
     * 检测一张手牌的可用性
     *
     * <p>执行流程：</p>
     * <ol>
     *   <li>非出牌阶段 → 直接返回 NOT_SELECTABLE</li>
     *   <li>抛出钩子收集数据：不限次数、已使用次数、可使用次数、是否满血</li>
     *   <li>逐项判定：是否自己回合、是否可在出牌阶段使用、是否可主动使用、次数是否用尽、桃满血不可用</li>
     *   <li>抛出 {@code CARD.PLAYABILITY.MODIFY} 修正钩子</li>
     *   <li>返回被修改后的最终结果</li>
     * </ol>
     */
    public CardCheckResult checkSingleCard(GameMatch match, GamePlayer player, CardInstance card) {
        // ════════════════════════════════════════════════════════════
        //  抛出钩子收集数据
        // ════════════════════════════════════════════════════════════

        // 1. 获取是否不限次数
        GameEvent unlimitedEvent = GameEvent.builder()
                .type(GameEventType.CARD_UNLIMITED_CHECK)
                .sourceId(player.getPlayerId())
                .build()
                .putData("player", player)
                .putData("card", card);
        eventBus.publish(unlimitedEvent, match);
        boolean unlimited = unlimitedEvent.getDataOrDefault("unlimited", false);

        // 2. 获取已使用次数
        GameEvent usedCountEvent = GameEvent.builder()
                .type(GameEventType.CARD_USED_COUNT)
                .sourceId(player.getPlayerId())
                .build()
                .putData("player", player)
                .putData("card", card);
        eventBus.publish(usedCountEvent, match);
        int usedCount = usedCountEvent.getDataOrDefault("usedCount", 0);

        // 3. 获取可使用次数
        GameEvent availableCountEvent = GameEvent.builder()
                .type(GameEventType.CARD_AVAILABLE_COUNT)
                .sourceId(player.getPlayerId())
                .build()
                .putData("player", player)
                .putData("card", card);
        eventBus.publish(availableCountEvent, match);
        int availableCount = availableCountEvent.getDataOrDefault("availableCount", 0);

        // 4. 获取玩家是否满血
        GameEvent fullHpEvent = GameEvent.builder()
                .type(GameEventType.PLAYER_FULL_HP_CHECK)
                .sourceId(player.getPlayerId())
                .build()
                .putData("player", player);
        eventBus.publish(fullHpEvent, match);
        boolean fullHp = fullHpEvent.getDataOrDefault("fullHp", false);

        // ════════════════════════════════════════════════════════════
        //  逐项检测
        // ════════════════════════════════════════════════════════════

        boolean tag = true;
        String unavailableReason = null;

        // ── 获取卡牌定义 ──
        CardDef def = cardManager.getDef(card.getDefId());

        // ── 是否可在出牌阶段使用？（playablePhase = PLAY 或 ANY 才可；null 表示不限制） ──
        if (tag && def != null && def.getRules() != null) {
            String phase = def.getRules().getPlayablePhase();
            if (phase != null && !"PLAY".equals(phase) && !"ANY".equals(phase)) {
                tag = false;
                unavailableReason = "不可在出牌阶段使用";
            }
        }

        // ── 是否可主动使用？ ──
        if (tag && def != null && def.getRules() != null) {
            if (Boolean.FALSE.equals(def.getRules().getCanActiveUse())) {
                tag = false;
                unavailableReason = "不可主动使用";
            }
        }

        // ── 有限次 → 已使用次数是否大于等于可使用次数？ ──
        if (tag && !unlimited) {
            if (usedCount >= availableCount) {
                tag = false;
                unavailableReason = "本回合使用次数已用尽";
            }
        }

        // ── 桃 → 满血不能使用 ──
        if (tag && "tao".equals(card.getDefId()) && fullHp) {
            tag = false;
            unavailableReason = "当前为满血，无需使用桃";
        }

        // ════════════════════════════════════════════════════════════
        //  抛出修正钩子，监听器可修改 tag
        // ════════════════════════════════════════════════════════════

        GameEvent modifyEvent = GameEvent.builder()
                .type(GameEventType.CARD_PLAYABILITY_MODIFY)
                .sourceId(player.getPlayerId())
                .build();
        modifyEvent.putData("player", player);
        modifyEvent.putData("card", card);
        modifyEvent.putData("tag", tag);
        eventBus.publish(modifyEvent, match);
        tag = modifyEvent.getDataOrDefault("tag", false);

        // ════════════════════════════════════════════════════════════
        //  返回最终结果
        // ════════════════════════════════════════════════════════════

        String defName = def != null ? def.getName() : card.getDefId();
        if (tag) {
            log.info("[卡牌检测] 玩家 {} 的【{}】(instanceId={}) → PLAYABLE",
                    player.getPlayerName(), defName, card.getInstanceId());
            return new CardCheckResult(CardActionStatus.PLAYABLE, null);
        }
        log.info("[卡牌检测] 玩家 {} 的【{}】(instanceId={}) → NOT_SELECTABLE · 原因: {}",
                player.getPlayerName(), defName, card.getInstanceId(), unavailableReason);
        return new CardCheckResult(CardActionStatus.NOT_SELECTABLE, unavailableReason);
    }

    // ================================================================
    //  检测方法模板（后续逐步实现）
    // ================================================================

    // ── 全局前置检测模板 ──
    // private CardActionStatus checkGlobalClickable(GameMatch match, GamePlayer player) {
    //     if (match.getStatus() != GameStatus.PLAYING)     return NOT_SELECTABLE;
    //     if (player.getStatus() != PlayerStatus.ALIVE)    return NOT_SELECTABLE;
    //     if (!isCurrentTurnPlayer(match, player))         return NOT_SELECTABLE;
    //     if (match.getCurrentPhase() != GamePhase.PLAY)   return NOT_SELECTABLE;
    //     return PLAYABLE;
    // }

    // ── 卡牌级检测模板 ──
    // private CardCheckResult checkCardPlayability(GameMatch match, GamePlayer player, CardInstance card) {
    //     CardDef def = cardManager.getDef(card.getDefId());
    //     if (def == null || def.getRules() == null)       return NOT_SELECTABLE("卡牌规则未定义");
    //     if (!checkPhaseMatch(def))                       return NOT_SELECTABLE("仅可在出牌阶段使用");
    //     if (!checkPerTurnLimit(match, player, card))     return NOT_SELECTABLE("本回合已达使用次数上限");
    //     if (isEquipment(def) && !checkEquipmentSlot(...)) return NOT_SELECTABLE("已有同类型装备");
    //     if (needsTargets(def) && !hasValidTargets(...))   return NOT_SELECTABLE("无合法目标");
    //     if (!checkSpecialRules(card, player))            return NOT_SELECTABLE("特殊规则不满足");
    //     return PLAYABLE;
    // }

    // ================================================================
    //  结果封装
    // ================================================================

    /**
     * 卡牌检测结果
     *
     * <p>包含卡牌的动作状态和不可用原因（仅 NOT_SELECTABLE 时有原因文字）。</p>
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

        /** 不可用原因（仅 NOT_SELECTABLE 时有效，其余为 null） */
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