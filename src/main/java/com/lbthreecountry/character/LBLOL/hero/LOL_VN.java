package com.lbthreecountry.character.LBLOL.hero;

import com.lbthreecountry.model.enums.impl.Gender;
import com.lbthreecountry.model.enums.impl.KingdomType;
import com.lbthreecountry.model.hero.BaseHero;

import java.util.List;

public class LOL_VN extends BaseHero {
    public LOL_VN() {
        super(
                "lol_VN",           // heroId
                "薇恩",
                "暗夜猎手", // heroTitle
                KingdomType.DMA,     // kingdom
                4,                   // maxHp
                Gender.FEMALE,                // gender
                "",  // description
                List.of(
                )
        );
    }
}
