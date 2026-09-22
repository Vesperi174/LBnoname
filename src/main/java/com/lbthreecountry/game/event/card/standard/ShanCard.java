package com.lbthreecountry.game.event.card.standard;

import com.lbthreecountry.game.GameMatch;
import com.lbthreecountry.game.GamePlayer;
import com.lbthreecountry.game.card.CardPlayabilityChecker;
import com.lbthreecountry.game.event.EventBus;
import com.lbthreecountry.game.event.EventPriority;
import com.lbthreecountry.game.event.GameEvent;
import com.lbthreecountry.game.event.GameEventType;
import com.lbthreecountry.game.hero.HeroManager;
import com.lbthreecountry.game.interaction.handler.HandCardFilter;
import com.lbthreecountry.game.interaction.handler.ResponseHandler;
import com.lbthreecountry.game.interaction.handler.ResponseResult;
import com.lbthreecountry.game.interaction.handler.SelectCardConfig;
import com.lbthreecountry.model.card.CardInstance;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 【闪】卡牌效果 — 监听 {@code CARD.USE.ACTIVE} 事件钩子
 *
 * <p>当【杀】指定目标时：</p>
 * <ol>
 *   <li>通知前端目标玩家的【闪】牌可用，其余牌不可用（HAND_STATUS）</li>
 *   <li>发送交互消息选择【闪】（ACTION_DECISION）</li>
 *   <li>玩家选牌后再发送确认消息，点确定后执行闪效果</li>
 * </ol>
 *
 * <h3>触发事件数据字段</h3>
 * <pre>
 * ┌──────────────┬──────────────┬──────────────────────────────────────┐
 * │ 字段名        │ 类型          │ 说明                                 │
 * ├──────────────┼──────────────┼──────────────────────────────────────┤
 * │ useplayer    │ GamePlayer   │ 使用牌的玩家                          │
 * │ targetplayer │ GamePlayer   │ 目标玩家                              │
 * │ card         │ CardInstance │ 使用的卡牌实例                        │
 * └──────────────┴──────────────┴──────────────────────────────────────┘
 * </pre>
 */
@Component
public class ShanCard {

    private static final Logger log = LoggerFactory.getLogger(ShanCard.class);

    private final EventBus eventBus;
    private final ResponseHandler responseHandler;
    private final HeroManager heroManager;
    private final CardPlayabilityChecker playabilityChecker;

    public ShanCard(EventBus eventBus,
                    ResponseHandler responseHandler,
                    HeroManager heroManager,
                    CardPlayabilityChecker playabilityChecker) {
        this.eventBus = eventBus;
        this.responseHandler = responseHandler;
        this.heroManager = heroManager;
        this.playabilityChecker = playabilityChecker;
    }

    @PostConstruct
    public void init() {
        eventBus.register(GameEventType.CARD_USE_ACTIVE, EventPriority.EQUIP_CARD, this::onUseActive);
    }

    /**
     * {@code CARD.USE.ACTIVE} 事件回调 — 检测【杀】的目标，推送 HAND_STATUS 和交互消息
     *
     * <p>判断逻辑：</p>
     * <ol>
     *   <li>使用的卡牌是否为【杀】</li>
     *   <li>目标玩家是否存在</li>
     * </ol>
     * <p>通过后：</p>
     * <ol>
     *   <li>推送 HAND_STATUS — 只有【闪】可选</li>
     *   <li>发送 ACTION_DECISION — 玩家选择一张【闪】</li>
     *   <li>玩家选牌后发送确认 ACTION_DECISION — 点确定后执行效果</li>
     * </ol>
     */
    private void onUseActive(GameEvent event, GameMatch match) {
        // ── 判断使用的卡牌是否为【杀】 ──
        CardInstance card = event.getData("card");
        if (card == null || !"sha".equals(card.getDefId())) {
            return;
        }

        // ── 判断目标玩家是否存在 ──
        GamePlayer targetplayer = event.getData("targetplayer");
        if (targetplayer == null) {
            return;
        }

        GamePlayer useplayer = event.getData("useplayer");

        // ── 获取使用者的角色名 ──
        String useplayerName = "未知";
        if (useplayer != null) {
            String heroId = useplayer.getHeroId();
            if (heroId != null && heroManager.getHero(heroId) != null) {
                useplayerName = heroManager.getHero(heroId).getHeroName();
            } else {
                useplayerName = useplayer.getPlayerId();
            }
        }

        log.info("[闪] 检测到 【{}】 对 【{}】 使用【杀】，准备闪响应",
                useplayerName, targetplayer.getPlayerId());

        int turnTime = responseHandler.getTurnTime(match.getRoomId());

        // ── 构建选牌配置 ──
        SelectCardConfig config = SelectCardConfig.builder()
                .selectDescription(String.format("【%s】对你使用一张【杀】，请使用一张【闪】", useplayerName))
                .confirmDescription("确定要使用【闪】来抵消【杀】吗？")
                .filter(HandCardFilter.allowOnly("shan"))
                .requireConfirm(true)
                .timeout(turnTime)
                .thinkingDescription("玩家【闪】思考中")
                .build();

        // ── 使用通用框架：选牌 → 确认 → 取消重选循环 ──
        ResponseResult<Long> result = responseHandler.selectCardWithConfirm(match, targetplayer, config);

        // ── 处理结果：放弃（取消/超时/中断）→ 杀继续执行 ──
        if (!result.isConfirmed()) {
            log.info("[闪] 玩家 {} 放弃出闪（{}），杀效果继续执行",
                    targetplayer.getPlayerId(), result.getStatus());
            return;
        }

        // ── 确认打出 → 执行闪效果 ──
        Long selectedInstanceId = result.getValue();
        log.info("[闪] 玩家 {} 确认打出 【闪】(instanceId={})，开始执行效果",
                targetplayer.getPlayerId(), selectedInstanceId);

        // ── 从手牌中取出选中的【闪】实例 ──
        CardInstance shanCard = targetplayer.getHandCards().stream()
                .filter(c -> c.getInstanceId() == selectedInstanceId)
                .findFirst()
                .orElse(null);
        if (shanCard == null) {
            log.warn("[闪] 找不到选中的【闪】(instanceId={})，跳过", selectedInstanceId);
            return;
        }

        // ── 闪的使用牌生命周期（不含 EFFECT，改为取消原【杀】） ──
        String sid = targetplayer.getPlayerId();

        // ① 移入牌桌中央
        eventBus.publish(GameEvent.builder()
                        .type(GameEventType.CARD_MOVE)
                        .sourceId(sid).build()
                        .putData("player", targetplayer)
                        .putData("cards", List.of(shanCard))
                        .putData("destination", "TABLE_CENTER"),
                match);

        // ── ①.5) 闪已打出 → HAND_STATUS 全部不可选（动画期间禁止操作） ──
        playabilityChecker.forceAllNotSelectable(match, targetplayer, "卡牌使用中");
        log.debug("[闪] 闪移到桌面后 → 玩家 {} 所有手牌设为不可选", targetplayer.getPlayerId());

        // ② BEFORE
        eventBus.publish(GameEvent.builder()
                        .type(GameEventType.CARD_USE_BEFORE)
                        .sourceId(sid).build()
                        .putData("useplayer", targetplayer)
                        .putData("targetplayer", null)
                        .putData("card", shanCard),
                match);

        // ③ ACTIVE
        eventBus.publish(GameEvent.builder()
                        .type(GameEventType.CARD_USE_ACTIVE)
                        .sourceId(sid).build()
                        .putData("useplayer", targetplayer)
                        .putData("targetplayer", null)
                        .putData("card", shanCard),
                match);

        // ④ 代替 EFFECT → 取消原【杀】的效果，并将杀移入弃牌堆
        event.setCancelled(true);
        eventBus.publish(GameEvent.builder()
                        .type(GameEventType.CARD_MOVE)
                        .sourceId(useplayer.getPlayerId()).build()
                        .putData("player", useplayer)
                        .putData("cards", List.of(card))
                        .putData("destination", "DISCARD_PILE"),
                match);

        // ⑤ AFTER
        eventBus.publish(GameEvent.builder()
                        .type(GameEventType.CARD_USE_AFTER)
                        .sourceId(sid).build()
                        .putData("useplayer", targetplayer)
                        .putData("targetplayer", null)
                        .putData("card", shanCard)
                        .putData("cancelled", false),
                match);

        // ⑥ 移入弃牌堆
        eventBus.publish(GameEvent.builder()
                        .type(GameEventType.CARD_MOVE)
                        .sourceId(sid).build()
                        .putData("player", targetplayer)
                        .putData("cards", List.of(shanCard))
                        .putData("destination", "DISCARD_PILE"),
                match);
    }
}