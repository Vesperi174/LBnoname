package com.lbthreecountry.character.LBLOL.hero;

import com.lbthreecountry.model.enums.impl.Gender;
import com.lbthreecountry.model.enums.impl.KingdomType;
import com.lbthreecountry.model.hero.BaseHero;

import java.util.List;

public class LOL_ytks extends BaseHero {
    public LOL_ytks() {
        super(
                "lol_ytks",           // heroId
                "亚托克斯",
                "暗裔剑魔", // heroTitle
                KingdomType.RUN,     // kingdom
                4,
                4,                   // startHp
                Gender.MALE,                // gender
                "",  // description
                List.of(
                )
        );
    }
}
