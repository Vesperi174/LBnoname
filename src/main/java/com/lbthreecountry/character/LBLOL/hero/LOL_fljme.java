package com.lbthreecountry.character.LBLOL.hero;

import com.lbthreecountry.model.enums.impl.Gender;
import com.lbthreecountry.model.enums.impl.KingdomType;
import com.lbthreecountry.model.hero.BaseHero;

import java.util.List;

public class LOL_fljme extends BaseHero {
    public LOL_fljme() {
        super(
                "lol_fljme",           // heroId
                "弗拉基米尔",
                "猩红收割者", // heroTitle
                KingdomType.NOK,     // kingdom
                3,
                3,                   // startHp
                Gender.MALE,                // gender
                "",  // description
                List.of(
                )
        );
    }
}
