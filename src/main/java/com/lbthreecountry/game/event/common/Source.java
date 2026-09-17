package com.lbthreecountry.game.event.common;

import com.lbthreecountry.game.GamePlayer;
import com.lbthreecountry.model.card.CardInstance;
import com.lbthreecountry.model.enums.impl.CardType;

/**
 * 来源信息 — 封装伤害、回复、获取目标等事件的来源类型、名称和原始对象
 *
 * <p>替换原有的 {@code sourceType} + {@code sourceName} 两个分散字符串字段，
 * 同时持有来源原始对象（{@link CardInstance} / {@link GamePlayer} 等），
 * 监听器可通过 {@link #getOrigin()} 访问原始对象的所有属性。</p>
 *
 * <h3>构造方式</h3>
 * <pre>{@code
 * // 从卡牌实例构造（自动识别 type = "BASIC_CARD"/"STRATEGY_CARD"/"EQUIPMENT_CARD"）
 * Source source = new Source(cardInstance);
 *
 * // 从玩家构造（type = "PLAYER"）
 * Source source = new Source(player);
 *
 * // 手动指定（适用于技能等无法自动推导的场景）
 * Source source = new Source("SKILL", "luoshen", skillObject);
 *
 * // 旧版兼容（无原始对象）
 * Source source = new Source("BASIC_CARD", "sha");
 * }</pre>
 *
 * <h3>监听器用法</h3>
 * <pre>{@code
 * Source source = event.getData("source");
 * String type = source.getType();              // "BASIC_CARD"
 * String name = source.getName();              // "sha"
 *
 * // 访问原始对象（如果有）
 * CardInstance card = source.getOrigin(CardInstance.class);
 * if (card != null) {
 *     card.getSuit();   // 获取花色
 *     card.getPoint();  // 获取点数
 * }
 * }</pre>
 */
public class Source {

    private final String type;
    private final String name;
    private final Object origin;

    // ================================================================
    //  构造器
    // ================================================================

    /** 从卡牌实例构造，自动识别来源类型 */
    public Source(CardInstance card) {
        this.origin = card;
        this.type = mapCardType(card);
        this.name = card != null ? card.getDefId() : null;
    }

    /** 从玩家构造，来源类型固定为 {@code PLAYER} */
    public Source(GamePlayer player) {
        this.origin = player;
        this.type = "PLAYER";
        this.name = player != null ? player.getPlayerId() : null;
    }

    /** 完整手动构造 — 指定 type、name 和原始对象 */
    public Source(String type, String name, Object origin) {
        this.type = type;
        this.name = name;
        this.origin = origin;
    }

    /** 旧版兼容 — 仅字符串，无原始对象 */
    public Source(String type, String name) {
        this(type, name, null);
    }

    // ================================================================
    //  Getter
    // ================================================================

    /** 来源类型：BASIC_CARD / STRATEGY_CARD / EQUIPMENT_CARD / SKILL / PLAYER 等 */
    public String getType() {
        return type;
    }

    /** 来源具体名称：如 sha / tao / 技能名 / 玩家ID */
    public String getName() {
        return name;
    }

    /** 来源原始对象（GamePlayer / CardInstance 等），可能为 null */
    public Object getOrigin() {
        return origin;
    }

    /** 类型安全的获取原始对象 */
    @SuppressWarnings("unchecked")
    public <T> T getOrigin(Class<T> clazz) {
        return clazz.isInstance(origin) ? (T) origin : null;
    }

    // ================================================================
    //  内部工具
    // ================================================================

    private static String mapCardType(CardInstance card) {
        if (card == null) return "UNKNOWN";
        CardType ct = card.getCardType();
        if (ct == null) return "UNKNOWN";
        return switch (ct) {
            case BASIC -> "BASIC_CARD";
            case STRATEGY -> "STRATEGY_CARD";
            case EQUIPMENT -> "EQUIPMENT_CARD";
        };
    }

    // ================================================================
    //  Object 方法
    // ================================================================

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Source source)) return false;
        return java.util.Objects.equals(type, source.type)
                && java.util.Objects.equals(name, source.name)
                && java.util.Objects.equals(origin, source.origin);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(type, name, origin);
    }

    @Override
    public String toString() {
        return origin != null
                ? type + ":" + name + "(" + origin + ")"
                : type + ":" + name;
    }
}