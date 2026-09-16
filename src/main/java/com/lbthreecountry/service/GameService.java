package com.lbthreecountry.service;

import com.lbthreecountry.game.GameMatch;
import com.lbthreecountry.model.enums.impl.RoleType;

import java.util.List;

/**
 * 游戏服务 — 管理一局游戏的生命周期
 *
 * <p>职责范围：</p>
 * <ul>
 *   <li>创建对局（房间 → GameMatch）</li>
 *   <li>回合流转（nextTurn / nextPhase）</li>
 *   <li>结束游戏</li>
 * </ul>
 *
 * <p>所有操作都使用 {@link GameMatch#lock()} 保证线程安全。</p>
 */
public interface GameService {

    /**
     * 开始游戏 — 将房间初始化为对局
     *
     * <p>执行流程：</p>
     * <ol>
     *   <li>从 {@link RoomService} 获取房间，校验状态</li>
     *   <li>创建 {@link com.lbthreecountry.game.GamePlayer}，随机分配游戏座位</li>
     *   <li>分配身份（主公/忠臣/反贼/内奸）</li>
     *   <li>初始化牌堆，发起始手牌</li>
     *   <li>存储对局，发布 {@code GAME.START} 事件</li>
     * </ol>
     *
     * @param roomId 房间 ID
     * @return 创建好的对局
     * @throws IllegalStateException 房间不存在/状态不对/人数不够/未全员准备
     */
    GameMatch startGame(String roomId);

    /**
     * 开始游戏（指定身份配置） — 将房间初始化为对局
     *
     * @param roomId         房间 ID
     * @param identityConfig 身份配置：{@code "standard"} 标准 / {@code "double_intruder"} 双内
     * @return 创建好的对局
     */
    GameMatch startGame(String roomId, String identityConfig);

    /**
     * 下一回合 — 结束当前玩家回合，轮到下一名存活玩家
     *
     * <p>查找下一名存活玩家 → 设为 {@code PREPARE} 阶段 → 发布 {@code TURN.START} 事件。
     * 如果回到 seat 0，同时发布 {@code ROUND.CHANGE} 事件。</p>
     *
     * @param roomId 房间 ID
     * @return 更新后的对局
     */
    GameMatch nextTurn(String roomId);

    /**
     * 下一阶段 — 推进当前玩家到下一个阶段
     *
     * <p>阶段顺序：{@code PREPARE → JUDGE → DRAW → PLAY → DISCARD → END}
     * <br>执行到 {@code END} 时自动调用 {@link #nextTurn(String)}。</p>
     *
     * @param roomId 房间 ID
     * @return 更新后的对局
     */
    GameMatch nextPhase(String roomId);

    /**
     * 摸牌 — 当前玩家从牌堆顶摸指定张数，放入手牌
     *
     * <p>在 match.lock() 保护下调用 {@code CardManager.draw()}。
     * 如果牌堆不足会自动洗弃牌堆。</p>
     *
     * @param roomId 房间 ID
     * @param count  摸牌张数
     * @return 更新后的对局
     */
    GameMatch drawCards(String roomId, int count);

    /**
     * 结束游戏
     *
     * @param roomId     房间 ID
     * @param winnerRole 获胜方身份
     * @return 结束后的对局
     */
    GameMatch endGame(String roomId, RoleType winnerRole);

    /**
     * 使用卡牌 — 玩家打出一张手牌，执行效果
     *
     * <p>执行流程：</p>
     * <ol>
     *   <li>校验：对局存在、正在进行、轮到出牌者、出牌阶段</li>
     *   <li>校验：卡牌在手牌中</li>
     *   <li>移除手牌，放入弃牌堆</li>
     *   <li>通过 {@code EffectEngine} 执行卡牌效果</li>
     *   <li>如果卡牌是【杀】，标记 {@code hasPlayedSha = true}</li>
     *   <li>发布 {@code PLAY_CARD} 事件</li>
     * </ol>
     *
     * @param roomId         房间 ID
     * @param playerId       出牌玩家 ID
     * @param cardInstanceId 卡牌实例 ID
     * @param targetIds      选中的目标玩家 ID 列表
     * @return 更新后的对局
     * @throws IllegalStateException 各种校验失败
     */
    GameMatch playCard(String roomId, String playerId, Long cardInstanceId, List<String> targetIds);

    /**
     * 获取对局
     *
     * @param roomId 房间 ID
     * @return 对局，不存在时返回 null
     */
    GameMatch getMatch(String roomId);

    /**
     * 选择武将 — 主公从候选列表中选择一个武将
     *
     * <p>仅在 {@code HERO_SELECT} 阶段有效，且只有主公可以调用。</p>
     *
     * @param roomId   房间 ID
     * @param playerId 主公玩家 ID
     * @param heroId   选择的武将 ID
     * @return 更新后的对局
     * @throws IllegalStateException 校验失败
     */
    GameMatch selectHero(String roomId, String playerId, String heroId);

    /**
     * 完成武将选择 — 主公选完后，为其余玩家自动分配武将并启动回合
     *
     * <p>执行流程：</p>
     * <ol>
     *   <li>检查主公已选择</li>
     *   <li>为其他玩家从剩余武将池随机分配</li>
     *   <li>设置 {@code GameStatus.PLAYING} 并启动第一回合（发布 TURN_BEFORE / TURN_ACTIVE / PREPARE 钩子）</li>
     * </ol>
     *
     * @param roomId 房间 ID
     * @return 更新后的对局
     * @throws IllegalStateException 主公尚未选择
     */
    GameMatch completeHeroSelection(String roomId);

    /**
     * 销毁对局（游戏结束后清理）
     *
     * @param roomId 房间 ID
     */
    void removeMatch(String roomId);
}