package com.lbthreecountry.game.card;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lbthreecountry.model.card.def.CardDef;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 卡牌图书馆 — 加载卡牌数据并合并 JSON + Java 注册信息
 * <p>
 * <b>数据来源：</b>
 * <ul>
 *   <li><b>JSON 文件</b>（{@code resources/cards/*.json}）：卡牌的固有属性，
 *       即 id、name、description、copies（花色+点数）</li>
 *   <li><b>{@link CardRegistry}</b>（Java 代码）：卡牌的行为属性，
 *       即 type、subType、rules、components</li>
 * </ul>
 * 两者通过 cardId 自动合并，形成完整的 {@link CardDef}。
 * </p>
 *
 * <p>
 * 新增卡牌需要：
 * <ol>
 *   <li>在 JSON 文件中添加卡牌的 id、name、description、copies</li>
 *   <li>在 {@link CardRegistry} 中注册卡牌的 type、rules、components</li>
 * </ol>
 * </p>
 */
@Service
public class CardLibrary {

    private static final Logger log = LoggerFactory.getLogger(CardLibrary.class);

    /** 内部 ObjectMapper，不依赖 Spring Boot 自动配置 */
    private final ObjectMapper objectMapper;

    /** CardRegistry — Java 注册的卡牌行为数据 */
    private final CardRegistry cardRegistry;

    /** id → 完整 CardDef（JSON 数据 + Java 注册数据合并） */
    private final Map<String, CardDef> cardDefMap = new ConcurrentHashMap<>();

    public CardLibrary(CardRegistry cardRegistry) {
        this.cardRegistry = cardRegistry;
        this.objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * 初始化：扫描 JSON 文件 + 合并 Java 注册数据
     */
    @PostConstruct
    public void init() {
        log.info("[卡牌库] 开始加载卡牌定义...");

        // 第一遍：扫描 JSON 文件，收集固有属性
        Map<String, CardDef> jsonDefs = loadFromJsonFiles();
        log.info("[卡牌库] JSON 加载: {} 张卡牌定义", jsonDefs.size());

        // 第二遍：与 CardRegistry 合并，形成完整定义
        int mergedCount = 0;
        for (Map.Entry<String, CardDef> entry : jsonDefs.entrySet()) {
            String id = entry.getKey();
            CardDef jsonDef = entry.getValue();

            // 查找 Java 注册的补充数据
            CardDef registeredDef = cardRegistry.getRegisteredDef(id);
            if (registeredDef == null) {
                log.warn("[卡牌库] 卡牌 '{}' 在 CardRegistry 中未注册，跳过", id);
                continue;
            }

            // 合并：JSON 数据优先（id、name、description、copies）
            //       CardRegistry 补充（type、subType、rules、components）
            CardDef merged = mergeDef(jsonDef, registeredDef);
            cardDefMap.put(id, merged);
            mergedCount++;

            log.debug("[卡牌库]   合并卡牌: {} ({}), 类型={}, 组件={}",
                    merged.getId(), merged.getName(), merged.getType(),
                    merged.getComponents() != null ? merged.getComponents().keySet() : "无");
        }

        log.info("[卡牌库] 加载完成: 共 {} 张卡牌定义, {} 张实体副本",
                mergedCount, totalCopyCount());
    }

    /**
     * 从 JSON 文件加载卡牌的固有属性
     */
    private Map<String, CardDef> loadFromJsonFiles() {
        Map<String, CardDef> result = new LinkedHashMap<>();

        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:cards/*.json");

            if (resources.length == 0) {
                log.warn("[卡牌库] 未找到任何卡牌 JSON 文件 (classpath:cards/*.json)");
                return result;
            }

            for (Resource resource : resources) {
                String filename = resource.getFilename();
                log.info("[卡牌库] 加载文件: {}", filename);

                try (InputStream is = resource.getInputStream()) {
                    JsonNode root = objectMapper.readTree(is);
                    JsonNode cardsNode = root.get("cards");
                    if (cardsNode == null || !cardsNode.isArray()) {
                        log.warn("[卡牌库] 文件 {} 缺少 cards 数组", filename);
                        continue;
                    }

                    for (JsonNode cardNode : cardsNode) {
                        CardDef def = objectMapper.treeToValue(cardNode, CardDef.class);
                        if (def.getId() == null || def.getId().isEmpty()) {
                            log.warn("[卡牌库] 跳过无 ID 的卡牌定义");
                            continue;
                        }

                        if (result.containsKey(def.getId())) {
                            log.warn("[卡牌库] 重复卡牌 ID: {} (文件: {})", def.getId(), filename);
                            continue;
                        }

                        result.put(def.getId(), def);
                        log.debug("[卡牌库]   加载卡牌: {} ({}), {} 张副本",
                                def.getId(), def.getName(),
                                def.getCopies() != null ? def.getCopies().size() : 0);
                    }
                }
            }
        } catch (Exception e) {
            log.error("[卡牌库] 加载卡牌定义失败", e);
        }

        return result;
    }

    /**
     * 合并 JSON 数据和 Java 注册数据
     * <p>
     * JSON 数据提供 id、name、description、copies；
     * CardRegistry 提供 type、subType、rules、components。
     * 如果 CardRegistry 中没有对应卡牌，则仅保留 JSON 数据。
     * </p>
     */
    private CardDef mergeDef(CardDef jsonDef, CardDef registeredDef) {
        if (registeredDef == null) return jsonDef;

        return CardDef.builder()
                // JSON 数据（固有属性）
                .id(jsonDef.getId())
                .name(jsonDef.getName())
                .description(jsonDef.getDescription())
                .copies(jsonDef.getCopies())
                .damage(jsonDef.getDamage())
                .singleTarget(jsonDef.getSingleTarget())
                .areaTarget(jsonDef.getAreaTarget())
                .delayed(jsonDef.getDelayed())
                // CardRegistry 数据（行为属性）
                .type(registeredDef.getType())
                .subType(registeredDef.getSubType())
                .attribute(registeredDef.getAttribute())
                .rules(registeredDef.getRules())
                .components(registeredDef.getComponents())
                .build();
    }

    // ================================================================
    //  查询方法
    // ================================================================

    /**
     * 根据 ID 获取卡牌定义
     *
     * @param id 卡牌 ID（如 "sha"、"tao"）
     * @return 完整的卡牌定义，未找到返回 null
     */
    public CardDef getDef(String id) {
        return cardDefMap.get(id);
    }

    /**
     * 获取所有卡牌定义
     */
    public Collection<CardDef> getAllDefs() {
        return cardDefMap.values();
    }

    /**
     * 根据卡牌大类获取卡牌定义列表
     *
     * @param type 卡牌类型（BASIC / STRATEGY / EQUIPMENT）
     */
    public List<CardDef> getByType(String type) {
        return cardDefMap.values().stream()
                .filter(def -> type.equals(def.getType()))
                .collect(Collectors.toList());
    }

    /**
     * 根据卡牌子类型获取卡牌定义
     *
     * @param subType 子类型值（如 SHA、SHAN、TAO）
     */
    public CardDef getBySubType(String subType) {
        return cardDefMap.values().stream()
                .filter(def -> subType.equals(def.getSubType()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 获取所有卡牌 ID 列表
     */
    public Set<String> getAllIds() {
        return cardDefMap.keySet();
    }

    /**
     * 获取所有实体副本的总数（牌堆总张数）
     */
    public int totalCopyCount() {
        return cardDefMap.values().stream()
                .filter(def -> def.getCopies() != null)
                .mapToInt(def -> def.getCopies().size())
                .sum();
    }

    /**
     * 获取指定类型卡牌的副本总数
     */
    public int copyCountByType(String type) {
        return cardDefMap.values().stream()
                .filter(def -> type.equals(def.getType()) && def.getCopies() != null)
                .mapToInt(def -> def.getCopies().size())
                .sum();
    }

    /**
     * 检查卡牌定义是否存在
     */
    public boolean hasDef(String id) {
        return cardDefMap.containsKey(id);
    }

    /**
     * 获取已加载的卡牌定义数量
     */
    public int defCount() {
        return cardDefMap.size();
    }
}