package com.lbthreecountry.game.event.common;

import com.lbthreecountry.game.GameMatch;
import com.lbthreecountry.game.GamePlayer;
import com.lbthreecountry.game.event.EventBus;
import com.lbthreecountry.game.event.GameEvent;
import com.lbthreecountry.game.event.GameEventType;
import org.springframework.stereotype.Component;

/**
 * 距离事件管理器 — 计算两名玩家之间的最终距离
 *
 * <p>职责：</p>
 * <ol>
 *   <li>调用 {@link GameMatch#calculateDistance(GamePlayer, GamePlayer)} 得到座次原始距离</li>
 *   <li>发布 {@link GameEventType#DISTANCE_CALC} 事件，携带原始距离和可修改的最终距离</li>
 *   <li>监听器（坐骑、技能等）可通过修改事件数据中的 {@code distance} 来影响最终结果</li>
 *   <li>返回被修正后的距离</li>
 * </ol>
 *
 * <h3>事件数据格式</h3>
 * <pre>{@code
 * {
 *   "fromId":      "<源玩家ID>",   // 不可修改
 *   "toId":        "<目标玩家ID>", // 不可修改
 *   "seatDistance": 2,            // 座次原始距离，不可修改
 *   "distance":    2              // 最终距离，监听器可修改
 * }
 * }</pre>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // ── 装备-1马：距离 -1 ──
 * eventBus.register(DISTANCE_CALC, EventPriority.EQUIP_CARD, (event, match) -> {
 *     int dist = (int) event.getData("distance");
 *     event.putData("distance", dist - 1);
 * });
 *
 * // ── 调用方 ──
 * int dist = distanceManager.getDistance(match, attacker, target);
 * // dist 可能是座次距离 -1（如果装备了-1马）
 * }</pre>
 */
@Component
public class DistanceManager {

    private final EventBus eventBus;

    public DistanceManager(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    /**
     * 计算从 {@code from} 到 {@code to} 的最终距离
     *
     * <p>先通过座次算出原始距离，再发布 {@link GameEventType#DISTANCE_CALC} 事件，
     * 所有监听器执行完毕后返回被修正过的距离。</p>
     *
     * @param match 当前对局
     * @param from  源玩家
     * @param to    目标玩家
     * @return 最终距离（最低为 1，可能被技能、装备等监听器修改过）
     */
    public int getDistance(GameMatch match, GamePlayer from, GamePlayer to) {
        if (from == null || to == null) return Integer.MAX_VALUE;
        if (from.getPlayerId().equals(to.getPlayerId())) return 0;

        // ── ① 座次原始距离 ──
        int seatDistance = match.calculateDistance(from, to);

        // ── ② 构造并发布 DISTANCE.CALC 事件 ──
        GameEvent event = GameEvent.builder()
                .type(GameEventType.DISTANCE_CALC)
                .sourceId(from.getPlayerId())
                .build();
        event.putData("fromId", from.getPlayerId());
        event.putData("toId", to.getPlayerId());
        event.putData("seatDist", seatDistance);
        event.putData("distance", seatDistance);

        eventBus.publish(event, match);

        // ── ③ 返回被监听器修改后的最终距离，最低为 1 ──
        return Math.max(1, (int) event.getData("distance"));
    }

    /**
     * 批量获取 {@code from} 到 {@code targets} 中每个目标的距离
     *
     * @param match   当前对局
     * @param from    源玩家
     * @param targets 目标玩家列表
     * @return 每个目标玩家ID → 最终距离 的映射
     */
    public java.util.Map<String, Integer> getDistances(GameMatch match,
                                                        GamePlayer from,
                                                        java.util.List<GamePlayer> targets) {
        java.util.Map<String, Integer> result = new java.util.LinkedHashMap<>();
        for (GamePlayer to : targets) {
            result.put(to.getPlayerId(), getDistance(match, from, to));
        }
        return result;
    }
}