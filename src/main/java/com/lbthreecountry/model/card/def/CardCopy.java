package com.lbthreecountry.model.card.def;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 卡牌副本 — 表示一张卡牌在牌堆中的一个具体实体（花色+点数）
 * <p>
 * 例如"杀"有 30 张副本，每张副本的花色点数不同。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CardCopy {
    /** 花色：HEARTS / DIAMONDS / CLUBS / SPADES */
    private String suit;

    /** 点数：1(A) ~ 13(K) */
    private int point;
}