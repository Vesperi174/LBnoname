package com.lbthreecountry.game.card.component;

import com.lbthreecountry.game.GamePlayer;
import com.lbthreecountry.game.card.EffectContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 【杀】的效果组件
 * <p>
 * 当前简化版：仅在战报中输出使用记录，不执行实际伤害逻辑。
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
        String invokerId = ctx.getInvokerId();
        String targetId = ctx.getFirstTargetId();

        GamePlayer invoker = ctx.findPlayer(invokerId);
        GamePlayer target = ctx.findPlayer(targetId);

        String invokerName = (invoker != null) ? invoker.getPlayerName() : invokerId;
        String targetName = (target != null) ? target.getPlayerName() : (targetId != null ? targetId : "无");

        if (targetId != null) {
            log.info("[杀] {} 对 {} 使用了【杀】", invokerName, targetName);
        } else {
            log.info("[杀] {} 使用了【杀】（无目标）", invokerName);
        }
    }
}