package com.lbthreecountry.game.event.common;

import com.lbthreecountry.game.GameMatch;
import com.lbthreecountry.game.GamePlayer;
import com.lbthreecountry.game.event.EventBus;
import com.lbthreecountry.game.event.EventPriority;
import com.lbthreecountry.game.event.GameEvent;
import com.lbthreecountry.game.event.GameEventType;
import com.lbthreecountry.model.card.CardInstance;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 获取目标事件 — 监听 {@code GET_TARGET} 触发钩子，执行获取目标生命周期
 *
 * <h3>事件数据格式</h3>
 * <pre>
 * ┌──────────────┬──────────┬──────────────────────────────────────────┐
 * │ 字段          │ 类型      │ 说明                                     │
 * ├──────────────┼──────────┼──────────────────────────────────────────┤
 * │ playerId     │ String   │ 发起目标的玩家 ID（必填）                   │
 * │ filterType   │ String   │ 目标筛选类型（必填）                       │
 * │              │          │  ALL / SELF / IN_ATTACK_RANGE /         │
 * │              │          │  CAN_ATTACK_ME / DISTANCE_WITHIN         │
 * │ source       │ Source   │ 来源对象，含 type 和 name（推荐）          │
 * │ sourceType   │ String   │ 来源类型（兼容旧版）                       │
 * │              │          │  SKILL / BASIC_CARD /                     │
 * │              │          │  STRATEGY_CARD / EQUIPMENT_CARD           │
 * │ sourceName   │ String   │ 具体来源名称（兼容旧版）                   │
 * │ distance     │ int      │ 筛选距离（filterType=DISTANCE_WITHIN 时）  │
 * │ includeSelf  │ boolean  │ 是否可以将自身作为目标（默认 false）         │
 * ├──────────────┴──────────┴──────────────────────────────────────────┤
 * │ 处理完成后，以下字段会写入事件数据：                                  │
 * ├──────────────┬──────────┬──────────────────────────────────────────┤
 * │ targets      │ List     │ 筛选后的目标玩家 ID 列表                   │
 * └──────────────┴──────────┴──────────────────────────────────────────┘
 * </pre>
 *
 * <h3>筛选类型说明</h3>
 * <ul>
 *   <li>{@code SELF} — 仅自身</li>
 *   <li>{@code ALL} — 所有存活玩家（默认）</li>
 *   <li>{@code IN_ATTACK_RANGE} — 在 playerId 攻击范围内的目标</li>
 *   <li>{@code CAN_ATTACK_ME} — 能攻击到 playerId 的目标（对方攻击范围包含 playerId）</li>
 *   <li>{@code DISTANCE_WITHIN} — 与 playerId 距离在 {@code distance} 以内的目标</li>
 * </ul>
 */
@Component
public class GetTargetEvent {

    private static final Logger log = LoggerFactory.getLogger(GetTargetEvent.class);

    /** 目标筛选类型常量 */
    public static final String FILTER_SELF = "SELF";
    public static final String FILTER_ALL = "ALL";
    public static final String FILTER_IN_ATTACK_RANGE = "IN_ATTACK_RANGE";
    public static final String FILTER_CAN_ATTACK_ME = "CAN_ATTACK_ME";
    public static final String FILTER_DISTANCE_WITHIN = "DISTANCE_WITHIN";

    /** 来源类型常量 */
    public static final String SOURCE_SKILL = "SKILL";
    public static final String SOURCE_BASIC_CARD = "BASIC_CARD";
    public static final String SOURCE_STRATEGY_CARD = "STRATEGY_CARD";
    public static final String SOURCE_EQUIPMENT_CARD = "EQUIPMENT_CARD";

    private final EventBus eventBus;
    private final DistanceManager distanceManager;

    public GetTargetEvent(EventBus eventBus, DistanceManager distanceManager) {
        this.eventBus = eventBus;
        this.distanceManager = distanceManager;
    }

    @PostConstruct
    public void init() {
        eventBus.register(GameEventType.GET_TARGET, EventPriority.ENGINE, this::onGetTarget);
        
    }

    /**
     * {@code GET_TARGET} 事件回调
     *
     * <p>从事件数据中读取筛选条件，执行目标获取逻辑，将结果写回事件数据。</p>
     */
    private void onGetTarget(GameEvent event, GameMatch match) {
        // ── 读取调用方传入的参数 ──
        String playerId = event.getData("playerId");
        String filterType = event.getData("filterType");
        Source source = event.getData("source");
        if (source == null) {
            // 兼容旧版：从独立字符串构造，并尝试附加原始对象
            String sourceType = event.getData("sourceType");
            String sourceName = event.getData("sourceName");
            if (sourceType != null) {
                Object origin = null;
                CardInstance card = event.getData("card");
                if (card != null) origin = card;
                source = new Source(sourceType, sourceName, origin);
            }
        }
        Integer distance = event.getData("distance");
        Boolean includeSelf = event.getData("includeSelf");
        if (includeSelf == null) includeSelf = false;

        if (playerId == null) {
            log.warn("[获取目标事件] 事件中无 playerId，忽略");
            return;
        }
        if (filterType == null) {
            filterType = FILTER_ALL;
        }

        log.debug("[获取目标事件] playerId={}, filterType={}, source={}, distance={}, includeSelf={}",
                playerId, filterType, source, distance, includeSelf);

        // ── 根据 filterType 执行目标筛选 ──
        java.util.List<String> targets = new java.util.ArrayList<>();

        switch (filterType) {
            case FILTER_SELF -> {
                // 仅自身
                GamePlayer self = match.findPlayer(playerId);
                if (self == null) {
                    log.warn("[获取目标事件] 玩家 {} 不存在", playerId);
                    break;
                }
                targets.add(playerId);
            }

            case FILTER_ALL -> {
                // 全部存活目标（可选包含自身）
                for (GamePlayer p : match.getPlayers()) {
                    if (!p.isAlive()) continue;
                    if (!includeSelf && p.getPlayerId().equals(playerId)) continue;
                    targets.add(p.getPlayerId());
                }
            }

            case FILTER_IN_ATTACK_RANGE -> {
                // 攻击范围内目标
                GamePlayer from = match.findPlayer(playerId);
                if (from == null) {
                    log.warn("[获取目标事件] 玩家 {} 不存在", playerId);
                    break;
                }

                // 抛出攻击范围钩子，获取该玩家的攻击距离
                GameEvent arEvent = GameEvent.builder()
                        .type(GameEventType.GET_ATTACK_RANGE)
                        .sourceId(playerId)
                        .build();
                arEvent.putData("playerId", playerId);
                eventBus.publish(arEvent, match);
                int attackRange = arEvent.getData("attackRange");

                // 遍历存活玩家，筛选在攻击范围内的目标
                for (GamePlayer p : match.getPlayers()) {
                    if (!p.isAlive()) continue;
                    if (!includeSelf && p.getPlayerId().equals(playerId)) continue;

                    int dist = distanceManager.getDistance(match, from, p);
                    if (dist <= attackRange) {
                        targets.add(p.getPlayerId());
                    }
                }
            }

            case FILTER_CAN_ATTACK_ME -> {
                // 能攻击到 playerId 的目标
                GamePlayer me = match.findPlayer(playerId);
                if (me == null) {
                    log.warn("[获取目标事件] 玩家 {} 不存在", playerId);
                    break;
                }

                // 遍历其他存活玩家，判断是否能攻击到自己
                for (GamePlayer p : match.getPlayers()) {
                    if (!p.isAlive()) continue;
                    if (!includeSelf && p.getPlayerId().equals(playerId)) continue;

                    // 抛出攻击范围钩子，获取该玩家的攻击距离
                    GameEvent arEvent = GameEvent.builder()
                            .type(GameEventType.GET_ATTACK_RANGE)
                            .sourceId(p.getPlayerId())
                            .build();
                    arEvent.putData("playerId", p.getPlayerId());
                    eventBus.publish(arEvent, match);
                    int otherAttackRange = arEvent.getData("attackRange");

                    // 该玩家到 playerId 的距离
                    int dist = distanceManager.getDistance(match, p, me);
                    if (dist <= otherAttackRange) {
                        targets.add(p.getPlayerId());
                    }
                }
            }

            case FILTER_DISTANCE_WITHIN -> {
                // 距离 x 以内的目标
                GamePlayer from = match.findPlayer(playerId);
                if (from == null) {
                    log.warn("[获取目标事件] 玩家 {} 不存在", playerId);
                    break;
                }
                int maxDist = distance != null ? distance : 1;

                for (GamePlayer p : match.getPlayers()) {
                    if (!p.isAlive()) continue;
                    if (!includeSelf && p.getPlayerId().equals(playerId)) continue;

                    int dist = distanceManager.getDistance(match, from, p);
                    if (dist <= maxDist) {
                        targets.add(p.getPlayerId());
                    }
                }
            }
        }

        // ── 遍历目标列表，逐个抛出"即将成为目标"钩子 ──
        String finalSourceType = source != null ? source.getType() : SOURCE_SKILL;
        String finalSourceName = source != null ? source.getName() : "unknown";

        for (String targetId : targets) {
            GameEvent becomeEvent = GameEvent.builder()
                    .type(GameEventType.BECOME_TARGET)
                    .sourceId(playerId)
                    .build();
            becomeEvent.putData("initiatorId", playerId);
            becomeEvent.putData("playerId", targetId);
            becomeEvent.putData("source", source);
            becomeEvent.putData("sourceType", finalSourceType);
            becomeEvent.putData("sourceName", finalSourceName);
            eventBus.publish(becomeEvent, match);
        }

        // 将结果写回事件数据
        event.putData("targets", targets);

        log.debug("[获取目标事件] 筛选到 {} 个目标: {}", targets.size(), targets);
    }
}