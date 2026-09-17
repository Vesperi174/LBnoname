package com.lbthreecountry.game.hero;

import com.lbthreecountry.model.hero.BaseHero;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 武将管理器 — 管理所有可用武将的注册、查询和随机分配
 *
 * <p>在 {@link PostConstruct 初始化} 阶段通过自动扫描武将包路径，
 * 反射加载所有武将类。新增武将只需在对应包下添加类文件即可。</p>
 */
@Component
public class HeroManager {

    private static final Logger log = LoggerFactory.getLogger(HeroManager.class);

    /** 武将包路径（自动扫描该包下所有继承 BaseHero 的类） */
    private static final String HERO_PACKAGE = "com.lbthreecountry.character.LBLOL.hero";
    private static final String HERO_RESOURCE_PATTERN = "classpath*:" + HERO_PACKAGE.replace('.', '/') + "/*.class";

    /** heroId → BaseHero 映射 */
    private final Map<String, BaseHero> heroMap = new LinkedHashMap<>();

    @PostConstruct
    public void init() {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

        try {
            Resource[] resources = resolver.getResources(HERO_RESOURCE_PATTERN);

            for (Resource resource : resources) {
                // 从文件路径提取类名，如 "/com/lbthreecountry/character/LBLOL/hero/LOL_EZ.class" → "LOL_EZ"
                String filename = resource.getFilename();
                if (filename == null || !filename.endsWith(".class")) continue;
                // 排除内部类（带 $ 的 class 文件）
                if (filename.contains("$")) continue;

                String className = HERO_PACKAGE + "." + filename.replace(".class", "");

                try {
                    Class<?> clazz = Class.forName(className);
                    // 只加载 BaseHero 的子类
                    if (!BaseHero.class.isAssignableFrom(clazz)) continue;

                    BaseHero hero = (BaseHero) clazz.getDeclaredConstructor().newInstance();
                    heroMap.put(hero.getHeroId(), hero);
                    log.debug("[武将] 加载: {} ({})", hero.getHeroName(), hero.getHeroId());
                } catch (Exception e) {
                    log.warn("[武将] 加载失败: {} - {}", className, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("[武将] 扫描包路径失败: {}", HERO_PACKAGE, e);
        }

        log.info("[武将] 共加载 {} 个武将", heroMap.size());
    }

    // ============ 查询方法 ============

    /** 获取所有武将 */
    public List<BaseHero> getAllHeroes() {
        return List.copyOf(heroMap.values());
    }

    /** 根据 ID 获取武将 */
    public BaseHero getHero(String heroId) {
        return heroMap.get(heroId);
    }

    /** 返回所有已注册的 heroId 列表 */
    public Set<String> getAllHeroIds() {
        return heroMap.keySet();
    }

    // ============ 随机选取 ============

    /**
     * 从可用武将池中随机抽取 N 个不重复的武将
     *
     * @param count      抽取数量
     * @param excludeIds 需要排除的武将 ID（已被选走的）
     * @return 随机武将列表（数量 ≤ count）
     */
    public List<BaseHero> pickRandomHeroes(int count, Collection<String> excludeIds) {
        Set<String> exclude = excludeIds == null ? Set.of() : Set.copyOf(excludeIds);
        List<BaseHero> available = heroMap.values().stream()
                .filter(h -> !exclude.contains(h.getHeroId()))
                .collect(Collectors.toList());
        Collections.shuffle(available);
        return available.subList(0, Math.min(count, available.size()));
    }

    /**
     * 为指定 players 列表中的每个玩家随机分配一个武将
     *
     * @param assignedIds 已被占用的武将 ID
     * @param count       需要分配的数量
     * @return 玩家下标 → heroId 的映射
     */
    public List<String> assignRandomHeroes(int count, Collection<String> assignedIds) {
        List<BaseHero> picked = pickRandomHeroes(count, assignedIds);
        return picked.stream().map(BaseHero::getHeroId).toList();
    }

    // ============ 序列化 ============

    /**
     * 将武将信息转为前端可用的 Map
     *
     * <p>前端渲染武将选择卡片需要以下字段：</p>
     * <ul>
     *   <li>{@code heroId} — 武将唯一 ID</li>
     *   <li>{@code heroName} — 武将名称</li>
     *   <li>{@code heroTitle} — 武将称号</li>
     *   <li>{@code kingdomCode} — 势力编码</li>
     *   <li>{@code kingdomName} — 势力名称</li>
     *   <li>{@code kingdomColor} — 势力颜色</li>
     *   <li>{@code maxHp} — 体力上限</li>
     *   <li>{@code gender} — 性别</li>
     *   <li>{@code skills} — 技能列表（[{skillId, skillName, description}]）</li>
     * </ul>
     */
    public Map<String, Object> heroToMap(BaseHero hero) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("heroId", hero.getHeroId());
        map.put("heroName", hero.getHeroName());
        map.put("heroTitle", hero.getHeroTitle());
        map.put("kingdom", hero.getKingdom());
        map.put("maxHp", hero.getMaxHp());
        map.put("startHp", hero.getStartHp());
        map.put("gender", hero.getGender() != null ? hero.getGender().getDescription() : null);
        map.put("skills", hero.getSkills().stream().map(s -> Map.of(
                "skillId", s.getSkillId(),
                "skillName", s.getSkillName(),
                "description", s.getDescription()
        )).toList());
        return map;
    }
}