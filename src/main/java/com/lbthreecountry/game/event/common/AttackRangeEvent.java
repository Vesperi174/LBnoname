package com.lbthreecountry.game.event.common;

import com.lbthreecountry.game.card.CardManager;
import com.lbthreecountry.game.event.EventBus;
import com.lbthreecountry.game.event.EventPriority;
import com.lbthreecountry.game.event.GameEvent;
import com.lbthreecountry.game.event.GameEventType;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 攻击距离事件 — 监听 {@code GET_ATTACK_RANGE} 触发钩子，执行攻击距离获取生命周期
 *
 * <p>TODO</p>
 */
@Component
public class AttackRangeEvent {

    private static final Logger log = LoggerFactory.getLogger(AttackRangeEvent.class);

    private final EventBus eventBus;
    private final CardManager cardManager;

    public AttackRangeEvent(EventBus eventBus, CardManager cardManager) {
        this.eventBus = eventBus;
        this.cardManager = cardManager;
    }

    @PostConstruct
    public void init() {
        eventBus.register(GameEventType.GET_ATTACK_RANGE, EventPriority.ENGINE, this::onGetAttackRange);
        log.info("[攻击距离事件] 已注册 GET_ATTACK_RANGE 监听器 (ENGINE 优先级)");
    }

    /**
     * {@code GET_ATTACK_RANGE} 事件回调
     *
     * <p>读取 {@code playerId} 对应的玩家攻击范围，写入事件数据后抛出修正钩子
     * {@code ATTACK_RANGE.MODIFY}。监听器可在钩子中修改 {@code attackRange}，
     * 调用方直接读取事件数据中的最终值即可。</p>
     */
    private void onGetAttackRange(GameEvent event, com.lbthreecountry.game.GameMatch match) {
        // 读取调用方传入的玩家 ID
        String playerId = event.getData("playerId");
        if (playerId == null) {
            log.warn("[攻击距离事件] 事件中无 playerId，忽略");
            return;
        }

        // 查找玩家
        com.lbthreecountry.game.GamePlayer player = match.findPlayer(playerId);
        if (player == null) {
            log.warn("[攻击距离事件] 玩家 {} 不存在，忽略", playerId);
            return;
        }

        // 基础攻击距离默认 1，受武器、技能等影响
        int attackRange = player.getAttackRange();

        // 创建修正事件（包含 playerId 和 attackRange，供监听器修改）
        GameEvent modifyEvent = GameEvent.builder()
                .type(GameEventType.ATTACK_RANGE_MODIFY)
                .sourceId(playerId)
                .build();
        modifyEvent.putData("playerId", playerId);
        modifyEvent.putData("attackRange", attackRange);

        // 抛出修正钩子，监听器可修改 modifyEvent 中的 attackRange
        eventBus.publish(modifyEvent, match);

        // 读取修正后的最终攻击距离，最低为 0，写回原事件供调用方读取
        attackRange = Math.max(0, (int) modifyEvent.getData("attackRange"));
        event.putData("attackRange", attackRange);

        log.debug("[攻击距离事件] 玩家 {} 的攻击距离为 {}", playerId, attackRange);
    }
}