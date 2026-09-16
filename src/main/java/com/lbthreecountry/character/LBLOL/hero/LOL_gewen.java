package com.lbthreecountry.character.LBLOL.hero;

import com.lbthreecountry.model.enums.impl.Gender;
import com.lbthreecountry.model.enums.impl.KingdomType;
import com.lbthreecountry.model.hero.BaseHero;

import java.util.List;

public class LOL_gewen extends BaseHero {
    public LOL_gewen() {
        super(
                "lol_gewen",           // heroId
                "格温",
                "灵罗娃娃", // heroTitle
                KingdomType.DRA,     // kingdom
                3,
                3,                   // startHp
                Gender.FEMALE,                // gender
                "",  // description
                List.of(
                )
        );
    }
}
