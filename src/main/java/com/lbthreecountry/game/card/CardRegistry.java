package com.lbthreecountry.game.card;

import com.lbthreecountry.model.card.def.CardDef;
import com.lbthreecountry.model.card.def.CardRules;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 卡牌注册中心 — 用 Java 代码注册卡牌的规则、类型和效果组件
 * <p>
 * <b>设计说明：</b>卡牌的"固有属性"（名称、描述、花色、点数）保存在 JSON 中，
 * 而"行为属性"（类型、规则、效果组件等）通过此类的 Java 代码注册。
 * 新增卡牌时需要在此类中添加注册代码。
 * </p>
 *
 * <p>
 * 注册内容包括：
 * <ul>
 *   <li>{@code type} — 卡牌大类（BASIC / STRATEGY / EQUIPMENT）</li>
 *   <li>{@code subType} — 卡牌子类型（SHA / SHAN / TAO 等）</li>
 *   <li>{@code rules} — 使用规则（出牌阶段、目标数量、距离限制等）</li>
 *   <li>{@code components} — 效果组件 ID 列表</li>
 * </ul>
 * </p>
 */
@Service
public class CardRegistry {

    private static final Logger log = LoggerFactory.getLogger(CardRegistry.class);

    /** id → 卡牌定义补充数据 */
    private final Map<String, CardDef> registeredDefs = new ConcurrentHashMap<>();

    /**
     * 初始化：注册所有卡牌
     */
    @PostConstruct
    public void init() {
        log.info("[卡牌注册] 开始注册卡牌定义（仅杀/闪）...");
        registerBasicCards();
        log.info("[卡牌注册] 注册完成: 共 {} 张卡牌", registeredDefs.size());
    }

    // ================================================================
    //  基本牌
    // ================================================================

    private void registerBasicCards() {
        register(CardDef.builder()
                .id("sha")
                .type("BASIC").subType("SHA")
                .rules(CardRules.builder()
                        .playablePhase("PLAY")
                        .maxPerTurn(1)
                        .targetCount(1)
                        .targetType("ENEMY")
                        .rangeLimit(-1)
                        .build())
                .components(Map.of("onUse", List.of("sha_effect")))
                .build());

        register(CardDef.builder()
                .id("shan")
                .type("BASIC").subType("SHAN")
                .rules(CardRules.builder()
                        .playablePhase("RESPOND")
                        .canRespondTo(List.of("SHA"))
                        .build())
                .components(Map.of("onRespond", List.of("shan_effect")))
                .build());
    }

    // ================================================================
    //  注册方法
    // ================================================================

    /**
     * 注册一张卡牌的补充定义
     * <p>只包含 type、subType、rules、components，不包含 copies</p>
     */
    private void register(CardDef def) {
        if (def.getId() == null || def.getId().isEmpty()) {
            log.warn("[卡牌注册] 跳过无 ID 的卡牌定义");
            return;
        }
        if (registeredDefs.containsKey(def.getId())) {
            log.warn("[卡牌注册] 重复注册: {}", def.getId());
        }
        registeredDefs.put(def.getId(), def);
    }

    /**
     * 获取卡牌的注册定义（不含 copies）
     *
     * @param id 卡牌 ID
     * @return 注册的定义，未找到返回 null
     */
    public CardDef getRegisteredDef(String id) {
        return registeredDefs.get(id);
    }

    /**
     * 检查卡牌是否已注册
     */
    public boolean isRegistered(String id) {
        return registeredDefs.containsKey(id);
    }

    /**
     * 获取所有已注册的卡牌 ID
     */
    public java.util.Set<String> getAllRegisteredIds() {
        return registeredDefs.keySet();
    }
}