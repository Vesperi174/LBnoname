package com.lbthreecountry.game.card.component;

import com.lbthreecountry.game.GamePlayer;
import com.lbthreecountry.game.card.EffectContext;
import com.lbthreecountry.model.card.CardInstance;
import com.lbthreecountry.model.enums.impl.CardStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 【杀】的效果组件
 * <p>
 * 逻辑：对选中的目标使用，目标可以出【闪】抵消，否则造成 1 点伤害。
 * </p>
 *
 * <p>
 * <b>响应流程：</b>
 * <ol>
 *   <li>获取使用者选择的目标</li>
 *   <li>检查目标手牌中是否有【闪】</li>
 *   <li>有【闪】→ 自动弃闪，杀被抵消</li>
 *   <li>无【闪】→ 造成 1 点普通伤害</li>
 * </ol>
 * </p>
 */
@Component
public class ShaEffect implements EffectComponent {

    private static final Logger log = LoggerFactory.getLogger(ShaEffect.class);

    @Override
    public String getId() {
        return "sha_effect";
    }

    @Override
    public void onUse(EffectContext ctx) {
        // 获取第一个选中的目标
        String targetId = ctx.getFirstTargetId();
        if (targetId == null) {
            log.warn("[杀] 没有选择目标");
            return;
        }

        GamePlayer target = ctx.getMatch().findPlayer(targetId);
        if (target == null || !target.isAlive()) {
            log.warn("[杀] 目标无效或已死亡");
            return;
        }

        log.info("[杀] {} 对 {} 使用杀", ctx.getInvokerId(), targetId);

        // 检查目标是否有【闪】
        CardInstance shanCard = findShanInHand(target);

        if (shanCard != null) {
            // 目标自动出闪，杀被抵消
            target.getHandCards().remove(shanCard);
            shanCard.setOwnerId(null);
            shanCard.setStatus(CardStatus.DISCARD_PILE);
            ctx.getMatch().getDiscardPile().add(shanCard);

            log.info("[杀] {} 打出【闪】, 杀被抵消", targetId);
            // 目标出闪成功，触发闪的响应效果
            ctx.triggerRespond(targetId, shanCard);
        } else {
            // 目标没有闪，造成 1 点伤害
            log.info("[杀] {} 没有【闪】, 造成 1 点伤害", targetId);
            ctx.damage(targetId, 1);
        }
    }

    /**
     * 在目标手牌中查找【闪】
     */
    private CardInstance findShanInHand(GamePlayer player) {
        for (CardInstance card : player.getHandCards()) {
            if (card.getDefId() != null && "shan".equals(card.getDefId())) {
                return card;
            }
        }
        return null;
    }
}