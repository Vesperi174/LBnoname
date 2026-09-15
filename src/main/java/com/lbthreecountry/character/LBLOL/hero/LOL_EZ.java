package com.lbthreecountry.character.LBLOL.hero;

import com.lbthreecountry.model.enums.impl.Gender;
import com.lbthreecountry.model.enums.impl.KingdomType;
import com.lbthreecountry.model.hero.BaseHero;

import java.util.List;

public class LOL_EZ extends BaseHero {
    public LOL_EZ() {
        super(
                "lol_EZ",           // heroId
                "伊泽瑞尔",
                "探险家", // heroTitle
                KingdomType.EUD,     // kingdom
                4,
                4,                   // startHp
                Gender.MALE,                // gender
                "",  // description
                List.of(
                )
        );
    }
}
