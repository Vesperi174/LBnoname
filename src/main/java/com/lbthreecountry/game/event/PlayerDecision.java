package com.lbthreecountry.game.event;

import com.lbthreecountry.game.state.PendingDecisionManager;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 玩家决策 — 出牌阶段中，前端返回的决策结果
 *
 * <p>当 {@link PlayPhaseHandler} 发送 {@code ACTION_DECISION} 消息给前端后，
 * 前端通过 WebSocket 返回决策，由 {@link PendingDecisionManager} 解析为此对象。</p>
 *
 * <h3>决策类型</h3>
 * <pre>
 * ┌──────────────┬──────────────────────────────────────────────────┐
 * │ action       │ 说明                                             │
 * ├──────────────┼──────────────────────────────────────────────────┤
 * │ end_turn     │ 结束回合（点击"结束回合"按钮）                       │
 * │ play_card    │ 使用一张牌（附带 cardInstanceId + targetIds）       │
 * │ cancel       │ 取消（等同于 end_turn）                            │
 * └──────────────┴──────────────────────────────────────────────────┘
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlayerDecision {

    /** 决策动作：play_card / end_turn / cancel */
    private String action;

    /** 使用的卡牌实例 ID（action=play_card 时有效） */
    private Long cardInstanceId;

    /** 目标玩家 ID 列表（action=play_card 时有效） */
    private List<String> targetIds;

    // ================================================================
    //  便捷工厂
    // ================================================================

    /** 创建"结束回合"决策 */
    public static PlayerDecision endTurn() {
        return PlayerDecision.builder().action("end_turn").build();
    }

    /** 创建"取消"决策 */
    public static PlayerDecision cancel() {
        return PlayerDecision.builder().action("cancel").build();
    }

    /** 创建"出牌"决策 */
    public static PlayerDecision playCard(long cardInstanceId, List<String> targetIds) {
        return PlayerDecision.builder()
                .action("play_card")
                .cardInstanceId(cardInstanceId)
                .targetIds(targetIds)
                .build();
    }

    // ================================================================
    //  便捷判断
    // ================================================================

    public boolean isEndTurn() {
        return "end_turn".equals(action) || "cancel".equals(action);
    }

    public boolean isPlayCard() {
        return "play_card".equals(action);
    }
}