package com.lbthreecountry.game.card;

import com.lbthreecountry.game.GameMatch;
import com.lbthreecountry.game.GamePlayer;
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
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EffectContext {

    /** 当前对局 */
    private GameMatch match;

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
    //  基础操作
    // ================================================================

    /**
     * 造成伤害
     *
     * @param targetId 目标玩家 ID
     * @param amount   伤害量
     */
    public void damage(String targetId, int amount) {
        GamePlayer target = findPlayer(targetId);
        if (target == null || !target.isAlive()) return;

        int actualDamage = Math.min(amount, target.getCurrentHp());
        target.setCurrentHp(target.getCurrentHp() - actualDamage);

        // 检查濒死
        if (target.getCurrentHp() <= 0) {
            target.setCurrentHp(0);
            checkDying(target);
        }
    }

    /**
     * 回复体力
     *
     * @param targetId 目标玩家 ID
     * @param amount   回复量（999 表示回满）
     */
    public void heal(String targetId, int amount) {
        GamePlayer target = findPlayer(targetId);
        if (target == null || !target.isAlive()) return;

        int actualHeal;
        if (amount >= 999) {
            actualHeal = target.getMaxHp() - target.getCurrentHp();
            target.setCurrentHp(target.getMaxHp());
        } else {
            int newHp = Math.min(target.getCurrentHp() + amount, target.getMaxHp());
            actualHeal = newHp - target.getCurrentHp();
            target.setCurrentHp(newHp);
        }
    }

    /**
     * 从牌堆摸牌
     *
     * @param targetId 目标玩家 ID
     * @param count    摸牌数量
     */
    public void draw(String targetId, int count) {
        GamePlayer target = findPlayer(targetId);
        if (target == null || !target.isAlive()) return;

        for (int i = 0; i < count; i++) {
            if (match.getDrawPile().isEmpty()) {
                reshuffleDiscardToDraw();
                if (match.getDrawPile().isEmpty()) break;
            }
            CardInstance card = match.getDrawPile().remove(match.getDrawPile().size() - 1);
            card.setOwnerId(targetId);
            target.getHandCards().add(card);
        }
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
        // 查找卡牌定义中的响应组件并执行
        if (card == null || card.getDefId() == null) return;

        // 当前简化版：在 ShaEffect 中已直接处理闪的效果
        // 后续可通过 CardLibrary 获取卡牌定义，查找响应组件并执行
    }

    // ================================================================
    //  内部辅助
    // ================================================================

    /** 检查濒死 */
    private void checkDying(GamePlayer player) {
        if (player.getCurrentHp() <= 0) {
            player.setCurrentHp(0);
            // TODO: 发送濒死事件，等待桃/酒拯救
        }
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