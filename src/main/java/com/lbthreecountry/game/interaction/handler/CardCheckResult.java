package com.lbthreecountry.game.interaction.handler;

import com.lbthreecountry.model.enums.impl.CardActionStatus;

import java.util.Objects;

/**
 * 手牌检测结果 — 标识一张手牌在特定交互中是否可用
 *
 * <p>由 {@link HandCardFilter} 返回，用于构建 {@code HAND_STATUS} 消息
 * 告知前端哪些手牌可选、哪些不可选及原因。</p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * HandCardFilter filter = (card, player, match) -> {
 *     if ("shan".equals(card.getDefId())) {
 *         return CardCheckResult.playable();
 *     }
 *     return CardCheckResult.notSelectable("不可使用");
 * };
 * }</pre>
 */
public final class CardCheckResult {

    private final CardActionStatus status;
    private final String reason;

    private CardCheckResult(CardActionStatus status, String reason) {
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.reason = reason;
    }

    // ── 工厂方法 ──

    /** 该牌可选 */
    public static CardCheckResult playable() {
        return new CardCheckResult(CardActionStatus.PLAYABLE, null);
    }

    /** 该牌不可选，附带原因 */
    public static CardCheckResult notSelectable(String reason) {
        return new CardCheckResult(CardActionStatus.NOT_SELECTABLE, reason);
    }

    /** 该牌不可选，默认原因 */
    public static CardCheckResult notSelectable() {
        return new CardCheckResult(CardActionStatus.NOT_SELECTABLE, "不可使用");
    }

    // ── Getter ──

    public CardActionStatus getStatus() { return status; }
    public String getReason()           { return reason; }
}