package com.lbthreecountry.game.event.common;

import com.lbthreecountry.game.GameMatch;
import com.lbthreecountry.game.GamePlayer;
import com.lbthreecountry.game.event.EventBus;
import com.lbthreecountry.game.event.EventPriority;
import com.lbthreecountry.game.event.GameEvent;
import com.lbthreecountry.game.event.GameEventType;
import com.lbthreecountry.game.event.card.standard.TaoCard;
import com.lbthreecountry.game.hero.HeroManager;
import com.lbthreecountry.game.interaction.InteractionMessageStack;
import com.lbthreecountry.model.card.CardInstance;
import com.lbthreecountry.model.enums.impl.CardActionStatus;
import com.lbthreecountry.model.enums.impl.PlayerStatus;
import com.lbthreecountry.websocket.WebSocketSessionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 濒死事件 — 监听 {@code PLAYER.DYING} 触发濒死流程
 *
 * <p>当玩家体力降到 0 或以下时，由伤害/失去体力事件发布 {@code PLAYER.DYING}，
 * 本组件监听到后执行濒死处理流程：广播、抛钩子、交互。</p>
 *
 * <h3>触发事件数据字段</h3>
 * <pre>
 * ┌──────────────┬──────────────────┬──────────────────────────────────┐
 * │ 字段          │ 类型              │ 说明                             │
 * ├──────────────┼──────────────────┼──────────────────────────────────┤
 * │ player       │ GamePlayer       │ 进入濒死的玩家（必填）            │
 * │ source       │ GamePlayer / null│ 造成濒死的来源玩家                │
 * │ sourceCard   │ CardInstance / String / null │ 造成濒死的来源卡牌     │
 * │ damage       │ int              │ 造成的伤害数值                    │
 * └──────────────┴──────────────────┴──────────────────────────────────┘
 * </pre>
 */
@Component
public class DyingEvent {

    private static final Logger log = LoggerFactory.getLogger(DyingEvent.class);

    /** 后端超时（second），比前端超时多 5s 作为缓冲 */
    private static final int BACKEND_TIMEOUT = 20;

    /** 濒死阶段钩子：濒死开始，可在此检查/修改濒死状态 */
    public static final String PLAYER_DYING_ENTER = "PLAYER.DYING.ENTER";

    private final EventBus eventBus;
    private final WebSocketSessionManager sessionManager;
    private final InteractionMessageStack interactionStack;
    private final HeroManager heroManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DyingEvent(EventBus eventBus,
                      WebSocketSessionManager sessionManager,
                      InteractionMessageStack interactionStack,
                      HeroManager heroManager) {
        this.eventBus = eventBus;
        this.sessionManager = sessionManager;
        this.interactionStack = interactionStack;
        this.heroManager = heroManager;
    }

    @PostConstruct
    public void init() {
        eventBus.register(GameEventType.PLAYER_DYING, EventPriority.ENGINE, this::onPlayerDying);
    }

    /**
     * {@code PLAYER.DYING} 事件回调
     *
     * <p>执行濒死处理流程：</p>
     * <ol>
     *   <li>广播 {@code DYING} 消息给所有玩家</li>
     *   <li>抛出 {@code PLAYER.DYING.ENTER} 钩子（XX进入濒死阶段）</li>
     *   <li>向濒死角色发送交互消息（告知需要 X 个桃）</li>
     * </ol>
     */
    private void onPlayerDying(GameEvent event, GameMatch match) {
        // ── 读取调用方传入的参数 ──
        GamePlayer player = event.getData("player");
        if (player == null) {
            log.warn("[濒死事件] 缺少 player，忽略");
            return;
        }

        GamePlayer source = event.getData("source");
        Object sourceCard = event.getData("sourceCard");

        log.info("[濒死事件] {} 进入濒死状态 (当前体力: {}/{})" +
                        (source != null ? "，由 {} 造成" : ""),
                player.getPlayerId(), player.getCurrentHp(), player.getMaxHp(),
                source != null ? source.getPlayerId() : "");

        // ── 设置玩家状态为"濒死" ──
        player.setStatus(PlayerStatus.DANGER);

        // ── ① 抛出濒死阶段钩子 ──
        GameEvent enterEvent = GameEvent.builder()
                .type(PLAYER_DYING_ENTER)
                .sourceId(source != null ? source.getPlayerId() : null)
                .targetId(player.getPlayerId())
                .build();
        enterEvent.putData("player", player);
        enterEvent.putData("source", source);
        enterEvent.putData("sourceCard", sourceCard);
        eventBus.publish(enterEvent, match);

        // ── ② 广播濒死消息到前端，告知让谁进濒死 ──
        broadcastDying(match, player, source, sourceCard);

        // ── ③ 计算需要桃的数量并通知濒死玩家 ──
        int peachesNeeded = calculatePeachesNeeded(player);
        sendDyingNotification(match, player, peachesNeeded);
    }

    // ================================================================
    //  濒死桃计算
    // ================================================================

    /**
     * 计算需要多少桃才能从濒死状态恢复到 1 点体力
     *
     * <p>公式: {@code 1 - currentHp}（currentHp ≤ 0）</p>
     * <p>例如: 体力 0 → 1 桃, 体力 -1 → 2 桃, 体力 -2 → 3 桃</p>
     */
    private int calculatePeachesNeeded(GamePlayer player) {
        return 1 - player.getCurrentHp();
    }

    // ================================================================
    //  交互
    // ================================================================

    /**
     * 向濒死玩家发送交互消息 — 逐张选桃，每张用完即检查是否脱离濒死
     *
     * <p>流程：</p>
     * <ol>
     *   <li>推送 {@code HAND_STATUS}（桃=可选，非桃=不可选）</li>
     *   <li>发送 {@code ACTION_DECISION} → "请使用一张桃"+[确定/取消]</li>
     *   <li>玩家选中一张桃并点"确定" → 立即使用该桃（回 1 血）</li>
     *   <li>检查 {@code currentHp > 0 ?}</li>
     *   <ul>
     *     <li>是 → 通知前端脱离濒死，结束事件</li>
     *     <li>否 → 回到第 1 步继续循环</li>
     *   </ul>
     *   <li>点"取消"或超时 → 处理死亡</li>
     * </ol>
     */
    private void sendDyingNotification(GameMatch match, GamePlayer player, int peachesNeeded) {
        try {
            List<String> allPlayerIds = match.getPlayers().stream()
                    .map(GamePlayer::getPlayerId)
                    .collect(Collectors.toList());

            int totalTimeout = BACKEND_TIMEOUT - 5;
            long startTime = System.currentTimeMillis();

            // ── 广播 thinking：其他玩家知道濒死玩家正在决策 ──
            broadcastThinking(allPlayerIds, player.getPlayerId(),
                    totalTimeout, totalTimeout, "玩家【桃】思考中");

            // ── 逐张选桃循环（每次选一张 → 确认 → 使用 → 检查） ──
            while (player.getCurrentHp() <= 0) {

                // ── 计算剩余超时（取消重选/继续选桃不重置） ──
                int elapsed = (int) ((System.currentTimeMillis() - startTime) / 1000);
                int remaining = totalTimeout - elapsed;
                if (remaining <= 0) {
                    log.warn("[濒死事件] 玩家 {} 濒死交互超时（已用 {}s）", player.getPlayerId(), elapsed);
                    break;
                }
                int remainingBackend = remaining + 5;

                // ════════════════════════════════════════════
                // Phase 1: 选牌 — 所有桃可选，有取消按钮
                // ════════════════════════════════════════════
                // 同步广播 thinking（每次交互前告知其他玩家）
                broadcastThinking(allPlayerIds, player.getPlayerId(),
                        remaining, totalTimeout, "玩家【桃】思考中");
                pushTaoSelectionStatus(player, match);

                Map<String, Object> selectMsg = new LinkedHashMap<>();
                selectMsg.put("type", "ACTION_DECISION");
                selectMsg.put("timeout", remaining);
                selectMsg.put("totalTimeout", totalTimeout);
                selectMsg.put("description", "你已濒死，请点击一张桃");
                selectMsg.put("actions", List.of(
                        Map.of("text", "取消", "value", "cancel", "type", "default")
                ));
                selectMsg.put("handSelectable", true);
                selectMsg.put("handSelectMode", "single");
                selectMsg.put("targetSelectable", false);
                selectMsg.put("selectedCardIds", List.of());

                Map<String, Object> extra = new LinkedHashMap<>();
                extra.put("remainingHp", player.getCurrentHp());
                extra.put("maxHp", player.getMaxHp());
                extra.put("peachesNeeded", peachesNeeded);
                selectMsg.put("extra", extra);

                Map<String, Object> response = interactionStack.pushAndAwait(
                        match.getRoomId(), player.getPlayerId(),
                        selectMsg, sessionManager, remainingBackend);

                // ── 超时 → 死亡 ──
                if (response == null) {
                    log.warn("[濒死事件] 玩家 {} 选桃超时", player.getPlayerId());
                    break;
                }

                // ── 房间已销毁 ──
                String respType = (String) response.get("type");
                if ("CANCELLED".equals(respType)) {
                    log.info("[濒死事件] 房间已销毁");
                    return;
                }

                // ── 取消/结束 → 放弃濒死 ──
                String action = (String) response.get("action");
                if ("cancel".equals(action) || "end_turn".equals(action)) {
                    log.info("[濒死事件] 玩家 {} 放弃使用桃", player.getPlayerId());
                    break;
                }

                // ── 解析玩家点击的卡牌 ──
                CardInstance selectedCard = extractSelectedTao(response, player);
                if (selectedCard == null) {
                    log.debug("[濒死事件] 未选中桃，重新等待");
                    continue;
                }

                // ── 重新计算剩余超时（选牌阶段也消耗了时间） ──
                int elapsed2 = (int) ((System.currentTimeMillis() - startTime) / 1000);
                int confirmRemaining = totalTimeout - elapsed2;
                if (confirmRemaining <= 0) {
                    log.warn("[濒死事件] 玩家 {} 确认阶段超时", player.getPlayerId());
                    break;
                }
                int confirmRemainingBackend = confirmRemaining + 5;

                // ════════════════════════════════════════════
                // Phase 2: 确认 — 选中牌上浮，询问是否使用
                // ════════════════════════════════════════════
                // 同步广播 thinking（确认阶段）
                broadcastThinking(allPlayerIds, player.getPlayerId(),
                        confirmRemaining, totalTimeout, "玩家【桃】思考中");
                pushConfirmStatus(player, match, selectedCard.getInstanceId());

                Map<String, Object> confirmMsg = new LinkedHashMap<>();
                confirmMsg.put("type", "ACTION_DECISION");
                confirmMsg.put("timeout", confirmRemaining);
                confirmMsg.put("totalTimeout", totalTimeout);
                confirmMsg.put("description", "确定使用这张桃吗？");
                confirmMsg.put("actions", List.of(
                        Map.of("text", "确定", "value", "confirm", "type", "primary"),
                        Map.of("text", "取消", "value", "cancel", "type", "default")
                ));
                confirmMsg.put("handSelectable", false);
                confirmMsg.put("targetSelectable", false);
                confirmMsg.put("selectedCardIds", List.of(String.valueOf(selectedCard.getInstanceId())));

                Map<String, Object> confirmExtra = new LinkedHashMap<>();
                confirmExtra.put("remainingHp", player.getCurrentHp());
                confirmExtra.put("maxHp", player.getMaxHp());
                confirmExtra.put("peachesNeeded", peachesNeeded);
                confirmMsg.put("extra", confirmExtra);

                Map<String, Object> confirmResp = interactionStack.pushAndAwait(
                        match.getRoomId(), player.getPlayerId(),
                        confirmMsg, sessionManager, confirmRemainingBackend);

                // ── 超时 → 死亡 ──
                if (confirmResp == null) {
                    log.warn("[濒死事件] 玩家 {} 确认使用桃超时", player.getPlayerId());
                    break;
                }

                // ── 房间已销毁 ──
                String confirmRespType = (String) confirmResp.get("type");
                if ("CANCELLED".equals(confirmRespType)) {
                    log.info("[濒死事件] 房间已销毁");
                    return;
                }

                String confirmAction = (String) confirmResp.get("action");

                // ── 取消 → 回到选牌阶段 ──
                if ("cancel".equals(confirmAction)) {
                    log.info("[濒死事件] 玩家 {} 取消使用此桃，重新选择", player.getPlayerId());
                    continue;
                }

                // ── 确定 → 使用这张桃 ──
                if ("confirm".equals(confirmAction)) {
                    useSingleTao(player, match, selectedCard);
                    log.info("[濒死事件] 玩家 {} 使用了桃 (体力: {}/{})",
                            player.getPlayerId(), player.getCurrentHp(), player.getMaxHp());

                    if (player.getCurrentHp() > 0) {
                        player.setStatus(PlayerStatus.ALIVE);
                        log.info("[濒死事件] 玩家 {} 脱离濒死！", player.getPlayerId());
                        // 通知前端脱离濒死
                        broadcastRevived(match, player);
                        return; // ✅ 正常结束
                    }
                    // 体力仍 ≤ 0，回到 Phase 1 继续选桃
                    continue;
                }

                // 其他 action → 回到选牌
                log.debug("[濒死事件] 未识别的确认响应: action={}", confirmAction);
            }

            // ── 循环结束仍濒死 → 请其他玩家拯救 → 仍失败则死亡 ──
            if (player.getCurrentHp() <= 0) {
                boolean saved = askOtherPlayersToSave(match, player, peachesNeeded);
                if (saved) {
                    player.setStatus(PlayerStatus.ALIVE);
                    broadcastRevived(match, player);
                    return;
                }
                handleDyingFailure(match, player);
            }

        } catch (Exception e) {
            log.warn("[濒死事件] 发送濒死通知失败", e);
        }
    }

    // ================================================================
    //  辅助方法
    // ================================================================

    /**
     * 推送手牌状态 — 桃=可选，非桃=不可选（选牌阶段）
     */
    private void pushTaoSelectionStatus(GamePlayer player, GameMatch match) {
        try {
            List<Map<String, Object>> cardStatusList = new ArrayList<>();
            for (CardInstance card : player.getHandCards()) {
                Map<String, Object> cardMap = new LinkedHashMap<>();
                cardMap.put("instanceId", card.getInstanceId());
                if ("tao".equals(card.getDefId())) {
                    cardMap.put("status", CardActionStatus.PLAYABLE.getCode());
                    cardMap.put("statusName", "可选");
                    cardMap.put("reason", "");
                } else {
                    cardMap.put("status", CardActionStatus.NOT_SELECTABLE.getCode());
                    cardMap.put("statusName", "不可选");
                    cardMap.put("reason", "不可使用");
                }
                cardStatusList.add(cardMap);
            }

            Map<String, Object> handStatus = new LinkedHashMap<>();
            handStatus.put("type", "HAND_STATUS");
            handStatus.put("cards", cardStatusList);
            handStatus.put("phase", "DYING");

            GamePlayer curPlayer = match.currentPlayer();
            boolean isMyTurn = curPlayer != null
                    && curPlayer.getPlayerId().equals(player.getPlayerId());
            handStatus.put("isMyTurn", isMyTurn);

            String json = objectMapper.writeValueAsString(handStatus);
            sessionManager.sendMessage(player.getPlayerId(), json);
            log.debug("[濒死事件] 推送 HAND_STATUS (桃可选)");
        } catch (Exception e) {
            log.warn("[濒死事件] 推送 HAND_STATUS 失败", e);
        }
    }

    /**
     * 推送手牌状态 — 选中的桃上浮(SELECTED)，其他桃置灰（确认阶段）
     */
    private void pushConfirmStatus(GamePlayer player, GameMatch match, long selectedInstanceId) {
        try {
            List<Map<String, Object>> cardStatusList = new ArrayList<>();
            for (CardInstance card : player.getHandCards()) {
                Map<String, Object> cardMap = new LinkedHashMap<>();
                cardMap.put("instanceId", card.getInstanceId());
                if (card.getInstanceId() == selectedInstanceId) {
                    cardMap.put("status", CardActionStatus.SELECTED.getCode());
                    cardMap.put("statusName", "已选");
                    cardMap.put("reason", "");
                } else {
                    cardMap.put("status", CardActionStatus.NOT_SELECTABLE.getCode());
                    cardMap.put("statusName", "不可选");
                    cardMap.put("reason", "已选择其他牌");
                }
                cardStatusList.add(cardMap);
            }

            Map<String, Object> handStatus = new LinkedHashMap<>();
            handStatus.put("type", "HAND_STATUS");
            handStatus.put("cards", cardStatusList);
            handStatus.put("phase", "DYING");

            GamePlayer curPlayer = match.currentPlayer();
            boolean isMyTurn = curPlayer != null
                    && curPlayer.getPlayerId().equals(player.getPlayerId());
            handStatus.put("isMyTurn", isMyTurn);

            String json = objectMapper.writeValueAsString(handStatus);
            sessionManager.sendMessage(player.getPlayerId(), json);
            log.debug("[濒死事件] 推送 HAND_STATUS → 桃[{}]上浮", selectedInstanceId);
        } catch (Exception e) {
            log.warn("[濒死事件] 推送 HAND_STATUS 失败", e);
        }
    }

    /**
     * 依次询问其他玩家是否使用桃拯救濒死角色
     *
     * <p>从濒死玩家的下一家开始，按座次顺序询问：</p>
     * <ol>
     *   <li>跳过已死亡玩家</li>
     *   <li>跳过濒死玩家自己</li>
     *   <li>两步交互：选桃 → 确认 → 对濒死目标使用</li>
     *   <li>只要有一张桃成功使用且体力 > 0，立即返回 true</li>
     * </ol>
     *
     * @return true=已被其他玩家救活，false=无人能救
     */
    private boolean askOtherPlayersToSave(GameMatch match, GamePlayer dyingPlayer, int peachesNeeded) {
        List<GamePlayer> allPlayers = match.getPlayers();
        int size = allPlayers.size();

        // ── 找到濒死玩家的座次下标 ──
        int dyingIndex = -1;
        for (int i = 0; i < size; i++) {
            if (allPlayers.get(i).getPlayerId().equals(dyingPlayer.getPlayerId())) {
                dyingIndex = i;
                break;
            }
        }
        if (dyingIndex < 0) return false;

        String dyingHeroName = getHeroDisplayName(dyingPlayer);
        String description = String.format("玩家[%s]濒死，需要%d个桃，是否使用桃拯救？",
                dyingHeroName, peachesNeeded);

        // ── 从濒死玩家的下一家开始，按座次依次询问 ──
        for (int offset = 1; offset < size; offset++) {
            int idx = (dyingIndex + offset) % size;
            GamePlayer helper = allPlayers.get(idx);

            // 跳过已死亡和濒死玩家自己
            if (!helper.isAlive() || helper.getPlayerId().equals(dyingPlayer.getPlayerId())) {
                continue;
            }

            // 跳过没有桃的玩家
            boolean hasTao = helper.getHandCards().stream()
                    .anyMatch(c -> "tao".equals(c.getDefId()));
            if (!hasTao) continue;

            log.info("[濒死事件] 询问玩家 {} 是否使用桃拯救 {}", helper.getPlayerId(), dyingPlayer.getPlayerId());

            // ── 询问该玩家（两步交互：选桃 → 确认） ──
            Integer savedCount = askSinglePlayerForTao(match, helper, dyingPlayer, peachesNeeded, description);
            if (savedCount != null && savedCount > 0) {
                // 使用了桃 → 检查濒死玩家体力
                if (dyingPlayer.getCurrentHp() > 0) {
                    log.info("[濒死事件] 玩家 {} 被 {} 拯救脱离濒死！", dyingPlayer.getPlayerId(), helper.getPlayerId());
                    return true;
                }
                // 使用了桃但体力仍 ≤ 0，继续询问其他玩家（从当前 helper 的下一家开始）
                peachesNeeded = calculatePeachesNeeded(dyingPlayer);
                description = String.format("玩家[%s]仍濒死，还需%d个桃，是否使用桃拯救？",
                        dyingHeroName, peachesNeeded);
                continue;
            }
            // 该玩家放弃 → 继续问下一家
        }

        return false; // 无人能救
    }

    /**
     * 询问单名玩家是否使用桃拯救濒死角色
     *
     * @return 使用的桃数量（0=未使用），null=超时/取消
     */
    private Integer askSinglePlayerForTao(GameMatch match, GamePlayer helper,
                                           GamePlayer dyingPlayer, int peachesNeeded,
                                           String description) {
        try {
            List<String> allPlayerIds = match.getPlayers().stream()
                    .map(GamePlayer::getPlayerId)
                    .collect(Collectors.toList());

            int totalTimeout = BACKEND_TIMEOUT - 5;
            long startTime = System.currentTimeMillis();

            // ── 循环：选牌 → 确认 → 取消重选（倒计时不重置） ──
            while (true) {
                // ── 计算剩余超时 ──
                int elapsed = (int) ((System.currentTimeMillis() - startTime) / 1000);
                int remaining = totalTimeout - elapsed;
                if (remaining <= 0) {
                    log.warn("[濒死事件] 玩家 {} 拯救交互超时", helper.getPlayerId());
                    return null;
                }
                int remainingBackend = remaining + 5;

                // ════════════════════════════════════════════
                // Phase 1: 选牌
                // ════════════════════════════════════════════
                broadcastThinking(allPlayerIds, helper.getPlayerId(),
                        remaining, totalTimeout, "玩家【桃】思考中");
                pushTaoSelectionStatus(helper, match);

                Map<String, Object> selectMsg = new LinkedHashMap<>();
                selectMsg.put("type", "ACTION_DECISION");
                selectMsg.put("timeout", remaining);
                selectMsg.put("totalTimeout", totalTimeout);
                selectMsg.put("description", description);
                selectMsg.put("actions", List.of(
                        Map.of("text", "取消", "value", "cancel", "type", "default")
                ));
                selectMsg.put("handSelectable", true);
                selectMsg.put("handSelectMode", "single");
                selectMsg.put("targetSelectable", false);
                selectMsg.put("selectedCardIds", List.of());

                Map<String, Object> response = interactionStack.pushAndAwait(
                        match.getRoomId(), helper.getPlayerId(),
                        selectMsg, sessionManager, remainingBackend);

                if (response == null) return null;
                String respType = (String) response.get("type");
                if ("CANCELLED".equals(respType)) return null;

                String action = (String) response.get("action");
                if ("cancel".equals(action) || "end_turn".equals(action)) return null;

                // ── 解析选中的桃 ──
                CardInstance selectedCard = extractSelectedTao(response, helper);
                if (selectedCard == null) {
                    log.debug("[濒死事件] 未选中桃，重新等待");
                    continue;
                }

                // ── 重新计算剩余超时 ──
                int elapsed2 = (int) ((System.currentTimeMillis() - startTime) / 1000);
                int confirmRemaining = totalTimeout - elapsed2;
                if (confirmRemaining <= 0) {
                    log.warn("[濒死事件] 玩家 {} 确认阶段超时", helper.getPlayerId());
                    return null;
                }
                int confirmRemainingBackend = confirmRemaining + 5;

                // ════════════════════════════════════════════
                // Phase 2: 确认
                // ════════════════════════════════════════════
                broadcastThinking(allPlayerIds, helper.getPlayerId(),
                        confirmRemaining, totalTimeout, "玩家【桃】思考中");
                pushConfirmStatus(helper, match, selectedCard.getInstanceId());

                Map<String, Object> confirmMsg = new LinkedHashMap<>();
                confirmMsg.put("type", "ACTION_DECISION");
                confirmMsg.put("timeout", confirmRemaining);
                confirmMsg.put("totalTimeout", totalTimeout);
                confirmMsg.put("description", "确定使用这张桃拯救【" + getHeroDisplayName(dyingPlayer) + "】吗？");
                confirmMsg.put("actions", List.of(
                        Map.of("text", "确定", "value", "confirm", "type", "primary"),
                        Map.of("text", "取消", "value", "cancel", "type", "default")
                ));
                confirmMsg.put("handSelectable", false);
                confirmMsg.put("targetSelectable", false);
                confirmMsg.put("selectedCardIds", List.of(String.valueOf(selectedCard.getInstanceId())));

                Map<String, Object> confirmResp = interactionStack.pushAndAwait(
                        match.getRoomId(), helper.getPlayerId(),
                        confirmMsg, sessionManager, confirmRemainingBackend);

                if (confirmResp == null) return null;
                String confirmRespType = (String) confirmResp.get("type");
                if ("CANCELLED".equals(confirmRespType)) return null;

                String confirmAction = (String) confirmResp.get("action");

                // ── 取消 → 回到 Phase 1 重新选牌 ──
                if ("cancel".equals(confirmAction)) {
                    log.info("[濒死事件] 玩家 {} 取消使用此桃拯救，重新选择", helper.getPlayerId());
                    continue;
                }

                if ("confirm".equals(confirmAction)) {
                    // ── 对濒死目标使用桃 ──
                    useSingleTaoOnTarget(helper, dyingPlayer, match, selectedCard);
                    log.info("[濒死事件] 玩家 {} 使用桃拯救 {} (体力: {}/{})",
                            helper.getPlayerId(), dyingPlayer.getPlayerId(),
                            dyingPlayer.getCurrentHp(), dyingPlayer.getMaxHp());
                    return 1;
                }

                // 未识别的 action → 回到选牌
                log.debug("[濒死事件] 未识别的确认响应: action={}", confirmAction);
            }
        } catch (Exception e) {
            log.warn("[濒死事件] 询问玩家 {} 失败", helper.getPlayerId(), e);
            return null;
        }
    }

    /**
     * 从前端响应中提取被选中的桃牌实例
     */
    private CardInstance extractSelectedTao(Map<String, Object> response, GamePlayer player) {
        Object selectedIdObj = response.get("selectedCardIds");
        if (!(selectedIdObj instanceof List)) return null;
        @SuppressWarnings("unchecked")
        List<String> selectedIds = (List<String>) selectedIdObj;
        if (selectedIds.isEmpty()) return null;
        try {
            long cardId = Long.parseLong(selectedIds.get(0));
            return player.getHandCards().stream()
                    .filter(c -> c.getInstanceId() == cardId
                            && "tao".equals(c.getDefId()))
                    .findFirst().orElse(null);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 获取玩家的武将显示名称
     *
     * <p>优先返回武将名（如"伊泽瑞尔"），若武将不存在则回退到 playerName，最后回退到 playerId。</p>
     */
    private String getHeroDisplayName(GamePlayer player) {
        if (player.getHeroId() != null) {
            var hero = heroManager.getHero(player.getHeroId());
            if (hero != null && hero.getHeroName() != null && !hero.getHeroName().isBlank()) {
                return hero.getHeroName();
            }
        }
        // 回退到 playerName
        if (player.getPlayerName() != null && !player.getPlayerName().isBlank()) {
            return player.getPlayerName();
        }
        return player.getPlayerId();
    }

    /**
     * 使用单张桃（自己对自己）— 走完整 CARD.USE 生命周期
     */
    private void useSingleTao(GamePlayer player, GameMatch match, CardInstance card) {
        useSingleTaoOnTarget(player, player, match, card);
    }

    /**
     * 对指定目标使用单张桃 — 走完整 CARD.USE 生命周期
     *
     * <p>发布 {@code CARD.USE} 事件，由 {@link CardUseEffectEvent} 处理：</p>
     * <ol>
     *   <li>广播 {@code TARGET_LINE}（使用玩家→目标玩家）</li>
     *   <li>发送 {@code CARD.MOVE} 手牌→牌桌中央（前端：卡牌飞入中央）</li>
     *   <li>执行 {@code CARD.USE.EFFECT} → {@link TaoCard} 监听此钩子回复 1 血</li>
     *   <li>发送 {@code CARD.MOVE} 牌桌中央→弃牌堆（前端：卡牌飞入弃牌堆）</li>
     * </ol>
     */
    private void useSingleTaoOnTarget(GamePlayer usePlayer, GamePlayer targetPlayer,
                                       GameMatch match, CardInstance card) {
        GameEvent useEvent = GameEvent.builder()
                .type(GameEventType.CARD_USE)
                .sourceId(usePlayer.getPlayerId())
                .build()
                .putData("useplayer", usePlayer)
                .putData("targetplayer", targetPlayer)
                .putData("card", card);
        eventBus.publish(useEvent, match);
    }

    /**
     * 处理濒死失败 — 玩家未能使用足够的桃
     *
     * <ol>
     *   <li>抛出 {@code PLAYER.DEAD} 事件钩子（由 {@code DeathEvent} 监听并处理死亡逻辑：弃牌、奖励等）</li>
     * </ol>
     */
    private void handleDyingFailure(GameMatch match, GamePlayer player) {
        log.info("[濒死事件] 玩家 {} 未能脱离濒死，进入死亡状态", player.getPlayerId());

        // ── 抛出玩家死亡事件钩子（由 DeathEvent 监听并处理死亡逻辑） ──
        GameEvent deadEvent = GameEvent.builder()
                .type(GameEventType.PLAYER_DEAD)
                .sourceId(null)
                .targetId(player.getPlayerId())
                .build()
                .putData("player", player);
        eventBus.publish(deadEvent, match);
    }

    // ================================================================
    //  前端通信
    // ================================================================

    /**
     * 广播思考状态（PLAYER_THINKING）给除目标玩家外的所有人
     *
     * @param allPlayerIds 房间内所有玩家 ID 列表
     * @param playerId     正在决策的玩家 ID
     * @param timeout      剩余超时秒数
     * @param totalTimeout 超时总长（固定值，用于前端进度条比例计算）
     * @param description  描述文字
     */
    private void broadcastThinking(List<String> allPlayerIds,
                                    String playerId,
                                    int timeout,
                                    int totalTimeout,
                                    String description) {
        try {
            Map<String, Object> thinkingMsg = new LinkedHashMap<>();
            thinkingMsg.put("type", "PLAYER_THINKING");
            thinkingMsg.put("playerId", playerId);
            thinkingMsg.put("timeout", timeout);
            thinkingMsg.put("totalTimeout", totalTimeout);
            thinkingMsg.put("description", description);

            String json = objectMapper.writeValueAsString(thinkingMsg);
            sessionManager.broadcastToRoom(allPlayerIds, json, playerId);
        } catch (Exception e) {
            log.warn("[濒死事件] 广播 PLAYER_THINKING 失败", e);
        }
    }

    /**
     * 广播脱离濒死消息到前端
     */
    private void broadcastRevived(GameMatch match, GamePlayer player) {
        try {
            Map<String, Object> revivedMsg = new LinkedHashMap<>();
            revivedMsg.put("type", "REVIVED");
            revivedMsg.put("playerId", player.getPlayerId());
            revivedMsg.put("currentHp", player.getCurrentHp());
            revivedMsg.put("maxHp", player.getMaxHp());

            String json = objectMapper.writeValueAsString(revivedMsg);
            sessionManager.broadcastToRoom(
                    match.getPlayers().stream().map(GamePlayer::getPlayerId).collect(Collectors.toList()),
                    json, null);
            log.info("[濒死事件] 广播 REVIVED → 玩家 {} 脱离濒死", player.getPlayerId());
        } catch (Exception e) {
            log.warn("[濒死事件] 广播 REVIVED 失败", e);
        }
    }

    /**
     * 广播濒死消息到前端
     */
    private void broadcastDying(GameMatch match, GamePlayer player,
                                 GamePlayer source, Object sourceCard) {
        try {
            List<String> allPlayerIds = match.getPlayers().stream()
                    .map(GamePlayer::getPlayerId)
                    .collect(Collectors.toList());

            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("type", "DYING");
            msg.put("playerId", player.getPlayerId());
            msg.put("playerName", player.getPlayerName());
            msg.put("remainingHp", player.getCurrentHp());
            msg.put("maxHp", player.getMaxHp());
            msg.put("sourceId", source != null ? source.getPlayerId() : null);
            msg.put("sourceName", source != null ? source.getPlayerName() : null);
            msg.put("sourceCard", sourceCard instanceof CardInstance c
                    ? c.getDefId() : sourceCard != null ? sourceCard.toString() : null);

            String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(msg);
            sessionManager.broadcastToRoom(allPlayerIds, json, null);
            log.debug("[濒死事件] 广播 DYING → {} 进入濒死", player.getPlayerId());
        } catch (Exception e) {
            log.warn("[濒死事件] 广播 DYING 失败", e);
        }
    }
}