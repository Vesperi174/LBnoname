package com.lbthreecountry.character.LBLOL.hero;

import com.lbthreecountry.model.enums.impl.Gender;
import com.lbthreecountry.model.enums.impl.KingdomType;
import com.lbthreecountry.model.hero.BaseHero;

import java.util.List;

public class LOL_alan extends BaseHero {
    public LOL_alan() {
        super(
                "lol_alan",           // heroId
                "奥莉安娜",
                "发条魔灵", // heroTitle
                KingdomType.PET,     // kingdom
                3,
                3,                   // startHp
                Gender.FEMALE,                // gender
                "",  // description
                List.of(
                )
        );
    }
}
