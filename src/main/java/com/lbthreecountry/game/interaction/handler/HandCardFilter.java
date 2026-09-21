package com.lbthreecountry.game.interaction.handler;

import com.lbthreecountry.game.GameMatch;
import com.lbthreecountry.game.GamePlayer;
import com.lbthreecountry.model.card.CardInstance;

/**
 * 手牌过滤器 — 判断一张手牌在当前交互场景下是否可用
 *
 * <p>函数式接口，配合 {@link ResponseHandler#pushHandStatus} 使用。
 * 实现类根据卡牌定义、玩家状态、对局上下文决定某张牌是否可选，及其不可选的原因。</p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 只有【闪】可选
 * HandCardFilter shanOnly = (card, player, match) -> {
 *     if ("shan".equals(card.getDefId())) {
 *         return CardCheckResult.playable();
 *     }
 *     return CardCheckResult.notSelectable("不可使用");
 * };
 *
 * // 使用快捷方法
 * HandCardFilter shanOnly = HandCardFilter.allowOnly("shan");
 * }</pre>
 */
@FunctionalInterface
public interface HandCardFilter {

    /**
     * 检测一张手牌是否可用
     *
     * @param card   手牌实例
     * @param player 所属玩家
     * @param match  当前对局
     * @return 检测结果，不可为 null
     */
    CardCheckResult check(CardInstance card, GamePlayer player, GameMatch match);

    // ── 快捷工厂方法 ──

    /**
     * 只允许指定 defId 的卡牌可选，其余均不可选
     *
     * @param allowedDefId 允许的卡牌 defId（如 {@code "shan"}）
     * @return 过滤器实例
     */
    static HandCardFilter allowOnly(String allowedDefId) {
        return (card, player, match) -> {
            if (allowedDefId.equals(card.getDefId())) {
                return CardCheckResult.playable();
            }
            return CardCheckResult.notSelectable("不可使用");
        };
    }

    /**
     * 所有手牌均不可选
     *
     * @param reason 原因
     * @return 过滤器实例
     */
    static HandCardFilter none(String reason) {
        return (card, player, match) -> CardCheckResult.notSelectable(reason);
    }

    /**
     * 所有手牌均可选
     *
     * @return 过滤器实例
     */
    static HandCardFilter all() {
        return (card, player, match) -> CardCheckResult.playable();
    }
}