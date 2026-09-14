package com.lbthreecountry.game.card.component;

import com.lbthreecountry.game.card.EffectContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 【闪】的效果组件
 * <p>
 * 逻辑：作为响应牌打出，抵消一次【杀】的伤害效果。
 * </p>
 *
 * <p>
 * <b>响应流程：</b>
 * <ol>
 *   <li>当目标被【杀】攻击时，如果手中有【闪】则自动打出</li>
 *   <li>打出的闪进入弃牌堆，杀的效果被抵消</li>
 * </ol>
 * </p>
 */
@Component
public class ShanEffect implements EffectComponent {

    private static final Logger log = LoggerFactory.getLogger(ShanEffect.class);

    @Override
    public String getId() {
        return "shan_effect";
    }

    @Override
    public void onUse(EffectContext ctx) {
        // 【闪】通常不会主动使用，仅在响应时打出
        log.warn("[闪] 闪不能被主动使用，只能在响应时打出");
    }

    @Override
    public void onRespond(EffectContext ctx) {
        // 标记响应的效果已被抵消
        ctx.setEffectNullified(true);
        log.info("[闪] {} 打出闪，抵消了效果", ctx.getInvokerId());
    }
}