package com.lbthreecountry.game;

import com.lbthreecountry.model.card.CardInstance;
import com.lbthreecountry.model.enums.impl.PlayerStatus;
import com.lbthreecountry.model.enums.impl.RoleType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 游戏中玩家 — 记录一局对局内玩家的完整状态
 *
 * <p>与 {@link com.lbthreecountry.entity.RoomPlayer 房间玩家} 一一对应，
 * 通过 {@link #playerId} 关联。GamePlayer 仅在对局期间存在，对局结束即销毁。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GamePlayer {

    /** 玩家 ID（对应 RoomPlayer.playerId） */
    private String playerId;

    /** 玩家名称（冗余，方便显示） */
    private String playerName;

    /** 房间座位号（0-based，对应 RoomPlayer.seatNumber，加入房间时的顺序） */
    private int roomSeat;

    /**
     * 游戏内座位号（0-based，游戏开始时随机分配）
     * <p>决定出牌顺序，与房间座位号无关。</p>
     */
    private int gameSeat;

    // ============ 身份 & 武将 ============

    /** 身份（主公/忠臣/反贼/内奸） */
    private RoleType role;

    /** 武将 ID（预留，后续武将系统实现后使用） */
    private String heroId;

    // ============ 体力 ============

    /** 体力上限 */
    private int maxHp;

    /** 当前体力值 */
    private int currentHp;

    // ============ 卡牌区 ============

    /** 手牌 */
    @Builder.Default
    private List<CardInstance> handCards = new ArrayList<>();

    /** 装备区（最多 5 个位置：武器、防具、+1马、-1马、宝物） */
    @Builder.Default
    private List<CardInstance> equipCards = new ArrayList<>();

    /** 判定区（闪电、乐不思蜀、兵粮寸断等） */
    @Builder.Default
    private List<CardInstance> judgeArea = new ArrayList<>();

    // ============ 状态 ============

    /** 玩家状态（存活/濒死/死亡/离开） */
    @Builder.Default
    private PlayerStatus status = PlayerStatus.ALIVE;

    /** 本回合是否已出过杀（用于限制每回合只能出一张杀） */
    @Builder.Default
    private boolean hasPlayedSha = false;

    /** 是否为机器人（玩家离开后自动接管） */
    @Builder.Default
    private boolean bot = false;

    /** 当前攻击距离（受武器、-1马影响，默认 1） */
    @Builder.Default
    private int attackRange = 1;

    /** 手牌上限（默认等于体力值，受技能影响） */
    @Builder.Default
    private int handCardLimit = -1; // -1 表示使用默认值（当前体力值）

    /** 扩展标记位（翻面、连环、铁索等） */
    @Builder.Default
    private Map<String, Object> flags = new HashMap<>();

    // ============ 便捷方法 ============

    /**
     * 获取实际手牌上限
     * @return handCardLimit != -1 返回 handCardLimit，否则返回 currentHp
     */
    public int effectiveHandCardLimit() {
        return handCardLimit == -1 ? currentHp : handCardLimit;
    }

    /** 是否存活 */
    public boolean isAlive() {
        return status == PlayerStatus.ALIVE;
    }

    /** 是否死亡 */
    public boolean isDead() {
        return status == PlayerStatus.DEAD;
    }

    /** 是否濒死 */
    public boolean isDying() {
        return status == PlayerStatus.DANGER;
    }
}