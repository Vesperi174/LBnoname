package com.lbthreecountry.game.card;

import com.lbthreecountry.game.GameMatch;
import com.lbthreecountry.game.card.component.EffectComponent;
import com.lbthreecountry.game.event.EventBus;
import com.lbthreecountry.model.card.CardInstance;
import com.lbthreecountry.model.card.def.CardDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 效果引擎 — 根据卡牌定义中的组件列表执行卡牌效果
 * <p>
 * <b>核心设计原则：</b>引擎不实现任何具体卡牌效果，
 * 只负责查找 {@link EffectComponent} 组件并执行。
 * 所有效果逻辑在独立的组件类中实现。
 * </p>
 *
 * <p>
 * <b>执行流程：</b>
 * <ol>
 *   <li>通过 {@link EffectManager} 根据组件 ID 查找对应的 {@link EffectComponent}</li>
 *   <li>调用组件的 {@code onUse()} / {@code onRespond()} 方法</li>
 *   <li>组件通过 {@link EffectContext} 中的基础方法（damage/heal/draw）操控游戏状态</li>
 * </ol>
 * </p>
 *
 * <p>
 * <b>新增效果的步骤：</b>
 * <ol>
 *   <li>新建一个类实现 {@link EffectComponent} 接口，标注 {@code @Component}</li>
 *   <li>在 JSON 卡牌定义的 {@code components} 字段中引用组件 ID</li>
 *   <li>重启应用即可 — 组件由 {@link EffectManager} 自动注册</li>
 * </ol>
 * </p>
 */
@Service
public class EffectEngine {

    private static final Logger log = LoggerFactory.getLogger(EffectEngine.class);

    private final EffectManager effectManager;
    private final EventBus eventBus;

    public EffectEngine(EffectManager effectManager, EventBus eventBus) {
        this.effectManager = effectManager;
        this.eventBus = eventBus;
    }

    /**
     * 执行卡牌的使用效果
     * <p>
     * 根据卡牌定义中的 {@code components.onUse} 组件列表，依次执行每个组件的效果。
     * 任何组件将 {@link EffectContext#effectNullified} 设为 true 后，后续组件不再执行。
     * </p>
     *
     * @param cardDef  卡牌定义（含组件 ID 列表）
     * @param card     卡牌实例
     * @param match    当前对局
     * @param invoker  使用者 ID
     * @param targets  选中的目标 ID 列表
     */
    public void executeOnUse(CardDef cardDef, CardInstance card, GameMatch match,
                             String invoker, List<String> targets) {
        if (cardDef == null) return;

        List<String> componentIds = cardDef.getOnUseComponents();
        if (componentIds == null || componentIds.isEmpty()) {
            log.debug("[引擎] {} 没有配置 onUse 组件", cardDef.getId());
            return;
        }

        EffectContext ctx = EffectContext.builder()
                .match(match)
                .eventBus(eventBus)
                .sourceCard(card)
                .invokerId(invoker)
                .targetIds(targets)
                .build();

        executeComponents(componentIds, ctx, "onUse");
    }

    /**
     * 执行卡牌的响应效果
     *
     * @param cardDef  卡牌定义
     * @param card     卡牌实例
     * @param match    当前对局
     * @param responder 响应者 ID
     * @param targets  目标 ID 列表
     */
    public void executeOnRespond(CardDef cardDef, CardInstance card, GameMatch match,
                                 String responder, List<String> targets) {
        if (cardDef == null) return;

        List<String> componentIds = cardDef.getOnRespondComponents();
        if (componentIds == null || componentIds.isEmpty()) {
            log.debug("[引擎] {} 没有配置 onRespond 组件", cardDef.getId());
            return;
        }

        EffectContext ctx = EffectContext.builder()
                .match(match)
                .eventBus(eventBus)
                .sourceCard(card)
                .invokerId(responder)
                .targetIds(targets)
                .build();

        executeComponents(componentIds, ctx, "onRespond");
    }

    /**
     * 执行一组组件
     *
     * @param componentIds 组件 ID 列表
     * @param ctx          执行上下文
     * @param eventType    事件类型（onUse / onRespond / onEquip / onUnequip）
     */
    private void executeComponents(List<String> componentIds, EffectContext ctx, String eventType) {
        for (String componentId : componentIds) {
            EffectComponent component = effectManager.getComponent(componentId);
            if (component == null) {
                log.warn("[引擎] 组件未找到: {}", componentId);
                continue;
            }

            // 如果效果已被抵消，跳过后续组件
            if (ctx.isEffectNullified()) {
                log.debug("[引擎] 效果已被抵消，跳过组件: {}", componentId);
                break;
            }

            log.debug("[引擎] 执行组件: {} ({})", componentId, eventType);
            switch (eventType) {
                case "onUse" -> component.onUse(ctx);
                case "onRespond" -> component.onRespond(ctx);
                case "onEquip" -> component.onEquip(ctx);
                case "onUnequip" -> component.onUnequip(ctx);
                default -> log.warn("[引擎] 未知事件类型: {}", eventType);
            }
        }

        // 记录执行摘要
        if (ctx.isEffectNullified()) {
            log.info("[引擎] 效果已被抵消");
        }
    }
}