package com.lbthreecountry.game.skill;

import com.lbthreecountry.game.GameMatch;
import com.lbthreecountry.game.GamePlayer;
import com.lbthreecountry.game.event.EventBus;
import com.lbthreecountry.game.event.EventListener;
import com.lbthreecountry.game.event.EventPriority;
import com.lbthreecountry.game.event.GameEvent;
import com.lbthreecountry.game.event.GameEventType;
import com.lbthreecountry.game.hero.HeroManager;
import com.lbthreecountry.model.hero.BaseHero;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 技能管理器 — 负责在 BATTLE_START 时按座位顺序注册所有武将技能
 *
 * <p>这是游戏技能系统的核心框架：</p>
 * <ul>
 *   <li>监听 {@link GameEventType#BATTLE_START BATTLE_START} 事件（{@link EventPriority#FRAMEWORK 框架优先级}）</li>
 *   <li>按玩家座位号升序遍历，为每个存活玩家调用武将的 {@link BaseHero#registerSkillListeners} 注册技能</li>
 *   <li>技能监听器使用 {@code EventPriority.SKILL + seatIndex} 作为优先级，保证先座位先执行</li>
 *   <li>提供 {@link #registerSkillListener} / {@link #unregisterSkill} 支持游戏中动态增删技能</li>
 * </ul>
 *
 * <h3>执行顺序示例（8 人局）</h3>
 * <pre>
 * BATTLE_START 发布
 *   │
 *   ├─ FRAMEWORK (-100)  SkillManager.onBattleStart()
 *   │   ├─ 注册座位 0 的技能 → SKILL + 0
 *   │   ├─ 注册座位 1 的技能 → SKILL + 1
 *   │   ├─ 注册座位 2 的技能 → SKILL + 2
 *   │   └─ ...
 *   │
 *   ├─ SKILL (0~N)        具体技能监听器（按座位顺序触发）
 *   ├─ EQUIP_CARD (100)   装备与卡牌效果
 *   └─ ENGINE (200)       游戏引擎
 * </pre>
 *
 * <h3>动态增删技能</h3>
 * <pre>{@code
 * // 装备武器时获得技能监听
 * skillManager.registerSkillListener("DAMAGE.BEFORE", myListener, "weapon_fx", seatIndex);
 *
 * // 卸下装备时移除技能
 * skillManager.unregisterSkill("weapon_fx");
 * }</pre>
 */
@Component
public class SkillManager {

    private static final Logger log = LoggerFactory.getLogger(SkillManager.class);

    /** 已注册的技能监听器索引：skillId → [注册信息] */
    private final Map<String, List<SkillRegistration>> registeredSkills = new ConcurrentHashMap<>();

    /** 玩家技能索引：playerId → [skillId] */
    private final Map<String, List<String>> playerSkills = new ConcurrentHashMap<>();

    private final EventBus eventBus;
    private final HeroManager heroManager;

    public SkillManager(EventBus eventBus, HeroManager heroManager) {
        this.eventBus = eventBus;
        this.heroManager = heroManager;
    }

    @PostConstruct
    public void init() {
        eventBus.register(GameEventType.BATTLE_START, EventPriority.FRAMEWORK, this::onBattleStart);
        
    }

    // ================================================================
    //  BATTLE_START — 按座位顺序注册所有武将技能
    // ================================================================

    /**
     * 战斗开始回调 — 按游戏座位号升序遍历所有存活玩家，注册各武将技能
     *
     * <p>此方法在 {@link EventPriority#FRAMEWORK} 优先级执行，
     * 确保在所有技能监听器响应 BATTLE_START 之前完成注册。</p>
     */
    void onBattleStart(GameEvent event, GameMatch match) {
        List<GamePlayer> players = match.getPlayers();

        log.info("[技能管理器] ⚔️ BATTLE_START → 开始按座位顺序注册技能共 {} 个玩家", players.size());

        for (int seat = 0; seat < players.size(); seat++) {
            GamePlayer player = players.get(seat);
            if (!player.isAlive()) {
                log.debug("[技能管理器]   座位 {} {} 已阵亡，跳过", seat, player.getPlayerName());
                continue;
            }
            registerPlayerSkills(match, player, seat);
        }

        log.info("[技能管理器] ✅ 所有技能注册完成");
    }

    /**
     * 为一个玩家注册其武将的所有技能
     *
     * @param match     当前对局
     * @param player    目标玩家
     * @param seatIndex 玩家座位号（用于计算监听器优先级）
     */
    private void registerPlayerSkills(GameMatch match, GamePlayer player, int seatIndex) {
        String heroId = player.getHeroId();
        if (heroId == null || heroId.isEmpty()) {
            log.warn("[技能管理器]   座位 {} {} 没有选择武将，跳过", seatIndex, player.getPlayerName());
            return;
        }

        BaseHero hero = heroManager.getHero(heroId);
        if (hero == null) {
            log.warn("[技能管理器]   武将 {} 未找到，跳过", heroId);
            return;
        }

        log.info("[技能管理器]   ↳ 座位 {} {} → {} ({}), 注册技能中...",
                seatIndex, player.getPlayerName(), hero.getHeroName(), hero.getHeroId());

        // 由武将实现类注册其具体技能的监听器
        hero.registerSkillListeners(this, match, player, seatIndex);
    }

    // ================================================================
    //  注册 / 注销 API
    // ================================================================

    /**
     * 注册一个技能监听器
     *
     * <p>优先级自动设为 {@code EventPriority.SKILL + seatIndex}，
     * 保证先座次的技能在同级钩子中优先执行。</p>
     *
     * @param eventType 要监听的事件类型（如 "DAMAGE.BEFORE"、"TURN.START"）
     * @param listener  监听器回调
     * @param skillId   技能 ID（用于后续取消注册 {@link #unregisterSkill}）
     * @param seatIndex 玩家座位号（0-based，用于计算优先级）
     */
    public void registerSkillListener(String eventType, EventListener listener, String skillId, int seatIndex) {
        int priority = EventPriority.SKILL + seatIndex;
        eventBus.register(eventType, priority, listener);

        registeredSkills.computeIfAbsent(skillId, k -> new CopyOnWriteArrayList<>())
                .add(new SkillRegistration(eventType, priority, listener));

        log.debug("[技能管理器]   注册技能监听: {} @ {} (优先级 {}, 座位 {})",
                skillId, eventType, priority, seatIndex);
    }

    /**
     * 注销一个技能的所有监听器
     *
     * <p>适用于：武将阵亡、装备被卸下、技能被封锁等场景。</p>
     *
     * @param skillId 要注销的技能 ID
     */
    public void unregisterSkill(String skillId) {
        List<SkillRegistration> registrations = registeredSkills.remove(skillId);
        if (registrations == null || registrations.isEmpty()) {
            log.debug("[技能管理器]   技能 {} 未注册，无需注销", skillId);
            return;
        }

        for (SkillRegistration reg : registrations) {
            eventBus.unregister(reg.listener);
        }
        log.info("[技能管理器]   已注销技能 {} ({} 个监听器)", skillId, registrations.size());
    }

    /**
     * 注销一个玩家的所有技能
     *
     * <p>适用于：玩家阵亡离场时清理其所有技能。</p>
     *
     * @param playerId 玩家 ID
     */
    public void unregisterAllPlayerSkills(String playerId) {
        List<String> skillIds = playerSkills.remove(playerId);
        if (skillIds == null) return;
        for (String skillId : skillIds) {
            unregisterSkill(skillId);
        }
    }

    // ================================================================
    //  内部记录
    // ================================================================

    /** 一次技能注册的信息 */
    private record SkillRegistration(String eventType, int priority, EventListener listener) {
    }
}