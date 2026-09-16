package com.lbthreecountry.game.card;

import com.lbthreecountry.game.GameMatch;
import com.lbthreecountry.game.GamePlayer;
import com.lbthreecountry.game.event.EventBus;
import com.lbthreecountry.game.event.GameEvent;
import com.lbthreecountry.game.event.GameEventType;
import com.lbthreecountry.model.card.CardInstance;
import com.lbthreecountry.model.enums.impl.CardActionStatus;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

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

    public CardPlayabilityChecker(CardManager cardManager, EventBus eventBus) {
        this.cardManager = cardManager;
        this.eventBus = eventBus;
    }

    // ================================================================
    //  事件钩子注册（模板 — 后续逐步添加实际监听逻辑）
    // ================================================================

    @PostConstruct
    public void registerHooks() {
        // ── 摸牌后检测：CardManager.draw() 摸完牌后触发 ──
        eventBus.register(GameEventType.CARD_DRAW_CHECK, 0, (event, match) -> {
            String playerId = event.getData("playerId");
            int drawnCount = event.getDataOrDefault("drawnCount", 0);
            log.debug("[卡牌检测] 玩家 {} 摸了 {} 张牌，可在此处重新检测手牌状态",
                    playerId, drawnCount);
            // TODO: 后续在此处重新计算该玩家的手牌状态并推送给前端
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

        log.info("[卡牌检测] 事件钩子已注册（已接入: CARD.DRAW.CHECK | 模板阶段所有卡牌返回 PLAYABLE）");
    }

    // ================================================================
    //  对外接口
    // ================================================================

    /**
     * 检测指定玩家的所有手牌可用性
     *
     * <p><b>当前阶段：</b>简化模式，所有卡牌返回 {@link CardActionStatus#PLAYABLE}。
     * 后续事件钩子接入后将逐步实现精确检测。</p>
     *
     * @param match  当前对局
     * @param player 要检测的玩家
     * @return 手牌 instanceId → 检测结果的映射
     */
    public Map<Long, CardCheckResult> checkAllHandCards(GameMatch match, GamePlayer player) {
        Map<Long, CardCheckResult> results = new java.util.LinkedHashMap<>();
        for (CardInstance card : player.getHandCards()) {
            results.put(card.getInstanceId(), checkSingleCard(match, player, card));
        }
        return results;
    }

    /**
     * 检测一张手牌的可用性
     *
     * <p><b>当前阶段：</b>简化模式，所有卡牌返回 {@link CardActionStatus#PLAYABLE}。</p>
     *
     * @param match  当前对局
     * @param player 卡牌持有者
     * @param card   要检测的卡牌实例
     * @return 检测结果（目前始终为 PLAYABLE）
     */
    public CardCheckResult checkSingleCard(GameMatch match, GamePlayer player, CardInstance card) {
        // ================================================================
        //  【模板阶段】所有卡牌返回 PLAYABLE
        //  后续通过事件钩子逐步接入精确判定：
        //
        //  1. 接入 PHASE 事件 → 非出牌阶段返回 NOT_CLICKABLE
        //  2. 接入 TURN 事件 → 非当前回合玩家返回 NOT_CLICKABLE
        //  3. 接入 CARD.PLAYED 事件 → 已出杀的卡牌返回 NOT_PLAYABLE
        //  4. 接入 EQUIPMENT 事件 → 已有同类型装备返回 NOT_PLAYABLE
        //  5. 接入 PLAYER 事件 → 无合法目标返回 NOT_PLAYABLE
        //  6. 特殊牌规则逐一添加
        // ================================================================
        return new CardCheckResult(CardActionStatus.PLAYABLE, null);
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
}