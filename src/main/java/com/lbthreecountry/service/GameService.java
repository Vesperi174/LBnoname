package com.lbthreecountry.service;

import com.lbthreecountry.game.GameMatch;
import com.lbthreecountry.model.enums.impl.RoleType;

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
     * 结束游戏
     *
     * @param roomId     房间 ID
     * @param winnerRole 获胜方身份
     * @return 结束后的对局
     */
    GameMatch endGame(String roomId, RoleType winnerRole);

    /**
     * 获取对局
     *
     * @param roomId 房间 ID
     * @return 对局，不存在时返回 null
     */
    GameMatch getMatch(String roomId);

    /**
     * 销毁对局（游戏结束后清理）
     *
     * @param roomId 房间 ID
     */
    void removeMatch(String roomId);
}