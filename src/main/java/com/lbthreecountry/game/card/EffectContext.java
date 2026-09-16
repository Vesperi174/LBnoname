package com.lbthreecountry.game.card;

import com.lbthreecountry.game.GameMatch;
import com.lbthreecountry.game.GamePlayer;
import com.lbthreecountry.game.event.EventBus;
import com.lbthreecountry.game.event.GameEvent;
import com.lbthreecountry.game.event.GameEventType;
import com.lbthreecountry.model.card.CardInstance;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 效果执行上下文 — 传递对局状态并提供基础操作
 * <p>
 * 贯穿卡牌效果执行的整个流程，存储当前对局状态、目标引用、临时变量等。
 * 提供 {@link #damage(String, int)}、{@link #heal(String, int)} 等基础操作方法，
 * 效果组件通过此上下文直接操控游戏状态。
 * </p>
 *
 * <p><b>事件驱动设计：</b>damage/heal/draw 等方法不再直接修改状态，而是
 * 通过 {@link EventBus} 发布对应的事件钩子（如 DAMAGE.BEFORE / DAMAGE.AFTER），
 * 由事件监听器（技能等）介入后，再执行实际操作。嵌套事件通过结算栈 LIFO 调度。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EffectContext {

    /** 当前对局 */
    private GameMatch match;

    /** 事件总线（通过 EffectEngine 注入） */
    private EventBus eventBus;

    /** 发起效果的卡牌实例 */
    private CardInstance sourceCard;

    /** 使用卡牌的玩家 ID */
    private String invokerId;

    /** 选中的目标玩家 ID 列表（按选择顺序） */
    private List<String> targetIds;

    /** forEach 中的当前目标玩家 ID */
    private String currentTargetId;

    /** 效果是否被抵消（闪/无懈可击设置为 true） */
    @Builder.Default
    private boolean effectNullified = false;

    /** 临时变量（可在组件之间传递数据） */
    @Builder.Default
    private Map<String, Object> vars = new HashMap<>();

    // ================================================================
    //  目标解析
    // ================================================================

    /** 获取第一个选中的目标 ID */
    public String getFirstTargetId() {
        if (targetIds == null || targetIds.isEmpty()) return null;
        return targetIds.get(0);
    }

    /** 获取选中的全部目标 ID */
    public List<String> getTargetIds() {
        return targetIds != null ? targetIds : List.of();
    }

    /** 获取卡牌使用者的 GamePlayer */
    public GamePlayer invoker() {
        if (match == null || invokerId == null) return null;
        return match.findPlayer(invokerId);
    }

    /** 获取第一个选中的目标 GamePlayer */
    public GamePlayer firstTarget() {
        String id = getFirstTargetId();
        return id != null ? match.findPlayer(id) : null;
    }

    /** 根据玩家 ID 获取 GamePlayer */
    public GamePlayer findPlayer(String playerId) {
        return match != null ? match.findPlayer(playerId) : null;
    }

    // ================================================================
    //  基础操作（事件驱动版）
    // ================================================================

    /**
     * 造成伤害
     * <p>流程：发布 DAMAGE.BEFORE → 监听器可修改/取消 → 扣血 → 发布 DAMAGE.AFTER → 检查濒死</p>
     */
    public void damage(String targetId, int amount) {
        GamePlayer target = findPlayer(targetId);
        if (target == null || !target.isAlive()) return;

        if (eventBus == null || match == null) {
            // 降级：没有 EventBus 时直接扣血（兼容旧式调用）
            applyDirectDamage(target, amount);
            return;
        }

        // 1) 发布 DAMAGE.BEFORE — 监听器可修改伤害量或取消
        GameEvent beforeEvent = GameEvent.builder()
                .type(GameEventType.BEFORE_DAMAGE)
                .sourceId(invokerId)
                .targetId(targetId)
                .build();
        beforeEvent.putData("amount", amount)
                .putData("playerId", targetId)
                .putData("playerName", target.getPlayerName());
        eventBus.publish(beforeEvent, match);

        // 2) 如果被取消，不打伤害
        if (beforeEvent.isCancelled()) {
            logDamage("[伤害] 伤害被取消: {} → {}", invokerId, targetId);
            return;
        }

        // 3) 取监听器可能修改后的伤害值
        int finalAmount = beforeEvent.getData("amount");
        if (finalAmount <= 0) return;

        // 4) 扣血
        int actualDamage = Math.min(finalAmount, target.getCurrentHp());
        target.setCurrentHp(target.getCurrentHp() - actualDamage);
        logDamage("[伤害] {} 对 {} 造成 {} 点伤害", invokerId, targetId, actualDamage);

        // 5) 发布 DAMAGE.AFTER
        GameEvent afterEvent = GameEvent.builder()
                .type(GameEventType.AFTER_DAMAGE)
                .sourceId(invokerId)
                .targetId(targetId)
                .build();
        afterEvent.putData("amount", actualDamage)
                .putData("playerId", targetId)
                .putData("playerName", target.getPlayerName());
        eventBus.publish(afterEvent, match);

        // 6) 检查濒死
        if (target.getCurrentHp() <= 0) {
            target.setCurrentHp(0);
            publishDying(target);
        }
    }

    /**
     * 回复体力
     * <p>流程：发布 HEAL.BEFORE → 回血 → 发布 HEAL.AFTER</p>
     *
     * @param targetId 目标玩家 ID
     * @param amount   回复量（999 表示回满）
     */
    public void heal(String targetId, int amount) {
        GamePlayer target = findPlayer(targetId);
        if (target == null || !target.isAlive()) return;

        if (eventBus == null || match == null) {
            applyDirectHeal(target, amount);
            return;
        }

        int actualHeal;
        if (amount >= 999) {
            actualHeal = target.getMaxHp() - target.getCurrentHp();
            target.setCurrentHp(target.getMaxHp());
        } else {
            int newHp = Math.min(target.getCurrentHp() + amount, target.getMaxHp());
            actualHeal = newHp - target.getCurrentHp();
            target.setCurrentHp(newHp);
        }

        if (actualHeal > 0) {
            logDamage("[治疗] {} 回复了 {} 点体力 (当前: {}/{})",
                    target.getPlayerName(), actualHeal, target.getCurrentHp(), target.getMaxHp());
        }
    }

    /**
     * 从牌堆摸牌
     * <p>流程：发布 CARD_DRAW.BEFORE → 摸牌 → 发布 CARD_DRAW.AFTER</p>
     *
     * @param targetId 目标玩家 ID
     * @param count    摸牌数量
     */
    public void draw(String targetId, int count) {
        GamePlayer target = findPlayer(targetId);
        if (target == null || !target.isAlive()) return;

        if (eventBus == null || match == null) {
            applyDirectDraw(target, count);
            return;
        }

        // 1) 摸牌前
        GameEvent beforeEvent = GameEvent.builder()
                .type(GameEventType.CARD_DRAW_BEFORE)
                .sourceId(targetId)
                .build();
        beforeEvent.putData("count", count)
                .putData("playerId", targetId)
                .putData("playerName", target.getPlayerName());
        eventBus.publish(beforeEvent, match);

        if (beforeEvent.isCancelled()) return;

        // 2) 摸牌中
        int actualCount = beforeEvent.getData("count");
        GameEvent activeEvent = GameEvent.builder()
                .type(GameEventType.CARD_DRAW_ACTIVE)
                .sourceId(targetId)
                .build();
        activeEvent.putData("count", actualCount);
        eventBus.publish(activeEvent, match);

        // 3) 执行摸牌
        int drawnCount = 0;
        for (int i = 0; i < actualCount; i++) {
            if (match.getDrawPile().isEmpty()) {
                reshuffleDiscardToDraw();
                if (match.getDrawPile().isEmpty()) break;
            }
            CardInstance card = match.getDrawPile().remove(match.getDrawPile().size() - 1);
            card.setOwnerId(targetId);
            target.getHandCards().add(card);
            drawnCount++;
        }

        // 4) 摸牌后
        GameEvent afterEvent = GameEvent.builder()
                .type(GameEventType.CARD_DRAW_AFTER)
                .sourceId(targetId)
                .build();
        afterEvent.putData("count", drawnCount)
                .putData("playerId", targetId)
                .putData("playerName", target.getPlayerName());
        eventBus.publish(afterEvent, match);

        logDamage("[摸牌] {} 摸了 {} 张牌", target.getPlayerName(), drawnCount);
    }

    // ================================================================
    //  效果抵消机制
    // ================================================================

    /** 标记当前效果已被抵消 */
    public void setEffectNullified(boolean nullified) {
        this.effectNullified = nullified;
    }

    /** 检查当前效果是否被抵消 */
    public boolean isEffectNullified() {
        return effectNullified;
    }

    // ================================================================
    //  触发响应效果
    // ================================================================

    /**
     * 触发一张卡牌的响应效果
     * <p>
     * 当玩家打出响应牌（如闪）时，通过此方法查找该卡牌的响应组件并执行。
     * </p>
     *
     * @param playerId 打出响应牌的玩家 ID
     * @param card     打出的卡牌实例
     */
    public void triggerRespond(String playerId, CardInstance card) {
        if (card == null || card.getDefId() == null) return;

        // 当前简化版：在 ShaEffect 中已直接处理闪的效果
        // 后续可通过 CardLibrary 获取卡牌定义，查找响应组件并执行
    }

    // ================================================================
    //  内部辅助
    // ================================================================

    /** 发布濒死事件 */
    private void publishDying(GamePlayer player) {
        GameEvent dyingEvent = GameEvent.builder()
                .type(GameEventType.PLAYER_DYING)
                .sourceId(invokerId)
                .targetId(player.getPlayerId())
                .build();
        dyingEvent.putData("playerId", player.getPlayerId())
                .putData("playerName", player.getPlayerName());
        eventBus.publish(dyingEvent, match);
    }

    /** 直接扣血（无 EventBus 降级） */
    private void applyDirectDamage(GamePlayer target, int amount) {
        int actualDamage = Math.min(amount, target.getCurrentHp());
        target.setCurrentHp(target.getCurrentHp() - actualDamage);
        if (target.getCurrentHp() <= 0) {
            target.setCurrentHp(0);
        }
    }

    /** 直接回血（无 EventBus 降级） */
    private void applyDirectHeal(GamePlayer target, int amount) {
        if (amount >= 999) {
            target.setCurrentHp(target.getMaxHp());
        } else {
            target.setCurrentHp(Math.min(target.getCurrentHp() + amount, target.getMaxHp()));
        }
    }

    /** 直接摸牌（无 EventBus 降级） */
    private void applyDirectDraw(GamePlayer target, int count) {
        for (int i = 0; i < count; i++) {
            if (match.getDrawPile().isEmpty()) {
                reshuffleDiscardToDraw();
                if (match.getDrawPile().isEmpty()) break;
            }
            CardInstance card = match.getDrawPile().remove(match.getDrawPile().size() - 1);
            card.setOwnerId(target.getPlayerId());
            target.getHandCards().add(card);
        }
    }

    /** 记录伤害日志 */
    private void logDamage(String format, Object... args) {
        org.slf4j.LoggerFactory.getLogger(getClass()).info(format, args);
    }

    /** 将弃牌堆洗回牌堆 */
    private void reshuffleDiscardToDraw() {
        if (match.getDiscardPile() != null && !match.getDiscardPile().isEmpty()) {
            match.getDrawPile().addAll(match.getDiscardPile());
            match.getDiscardPile().clear();
            java.util.Collections.shuffle(match.getDrawPile());
        }
    }
}