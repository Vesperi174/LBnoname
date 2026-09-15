package com.lbthreecountry.character.LBLOL.hero;

import com.lbthreecountry.model.enums.impl.Gender;
import com.lbthreecountry.model.enums.impl.KingdomType;
import com.lbthreecountry.model.hero.BaseHero;

import java.util.List;

public class LOL_sailasi extends BaseHero {
    public LOL_sailasi() {
        super(
                "lol_sailasi",           // heroId
                "塞拉斯",
                "解脱者", // heroTitle
                KingdomType.DMA,     // kingdom
                4,
                4,                   // startHp
                Gender.MALE,                // gender
                "",  // description
                List.of(
                )
        );
    }
}
