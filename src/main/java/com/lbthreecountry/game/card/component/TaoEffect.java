package com.lbthreecountry.game.card.component;

import com.lbthreecountry.game.GamePlayer;
import com.lbthreecountry.game.card.EffectContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 【桃】的效果组件
 * <p>
 * 当前简化版：仅在战报中输出使用记录，不执行实际回复逻辑。
 * </p>
 */
@Component
public class TaoEffect implements EffectComponent {

    private static final Logger log = LoggerFactory.getLogger(TaoEffect.class);

    @Override
    public String getId() {
        return "tao_effect";
    }

    @Override
    public void onUse(EffectContext ctx) {
        String invokerId = ctx.getInvokerId();

        GamePlayer invoker = ctx.findPlayer(invokerId);
        String invokerName = (invoker != null) ? invoker.getPlayerName() : invokerId;

        log.info("[桃] {} 使用了【桃】回复体力", invokerName);
    }
}