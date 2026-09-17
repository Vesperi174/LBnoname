package com.lbthreecountry.game.event.card;

import com.lbthreecountry.game.GameMatch;
import com.lbthreecountry.game.event.EventBus;
import com.lbthreecountry.game.event.EventPriority;
import com.lbthreecountry.game.event.GameEvent;
import com.lbthreecountry.game.event.GameEventType;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 打出牌事件 — 监听 {@code CARD.PLAY} 触发钩子，执行打出牌生命周期
 *
 * <h3>打出牌生命周期</h3>
 * <pre>
 * CARD.PLAY (触发钩子)
 *   ├── CARD.PLAY.BEFORE  (打出前，可修改 / 可取消)
 *   ├── CARD.PLAY.ACTIVE  (打出时，可修改 / 可取消)
 *   ├── 实际打出行为
 *   └── CARD.PLAY.AFTER   (打出后钩子，仅通知)
 * </pre>
 */
@Component
public class PlayCardEvent {

    private static final Logger log = LoggerFactory.getLogger(PlayCardEvent.class);

    private final EventBus eventBus;

    public PlayCardEvent(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    @PostConstruct
    public void init() {
        eventBus.register(GameEventType.CARD_PLAY, EventPriority.ENGINE, this::onPlayCard);
        log.info("[打出牌事件] 已注册 CARD.PLAY 监听器 (ENGINE 优先级)");
    }

    /**
     * {@code CARD.PLAY} 事件回调 — 打出生命周期
     *
     * <p>TODO: 待实现完整生命周期</p>
     */
    private void onPlayCard(GameEvent event, GameMatch match) {
        // TODO: 按以下流程实现打出生命周期
        //
        // 1. 读取打出参数（playerId, cardDefId, cardName, targetIds 等）
        // 2. CARD.PLAY.BEFORE 钩子 — 监听器可修改数据或取消
        // 3. CARD.PLAY.ACTIVE 钩子 — 监听器可修改数据或取消
        // 4. 实际打出行为
        // 5. CARD.PLAY.AFTER 钩子 — 仅通知
        //
        // cancelled / 数据无效 → 直接 return，不走 AFTER

        log.debug("[打出事件] 收到 CARD.PLAY 事件: sourceId={}", event.getSourceId());
    }
}