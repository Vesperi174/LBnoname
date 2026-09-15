package com.lbthreecountry.character.LBLOL.hero;

import com.lbthreecountry.model.enums.impl.Gender;
import com.lbthreecountry.model.enums.impl.KingdomType;
import com.lbthreecountry.model.hero.BaseHero;

import java.util.List;

public class LOL_bulande extends BaseHero {
    public LOL_bulande() {
        super(
                "lol_bulande",           // heroId
                "布兰德",
                "复仇焰魂", // heroTitle
                KingdomType.RUN,     // kingdom
                3,
                3,                   // startHp
                Gender.MALE,                // gender
                "",  // description
                List.of(
                )
        );
    }
}
