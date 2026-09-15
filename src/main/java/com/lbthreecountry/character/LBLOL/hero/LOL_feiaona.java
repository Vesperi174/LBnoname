package com.lbthreecountry.character.LBLOL.hero;

import com.lbthreecountry.model.enums.impl.Gender;
import com.lbthreecountry.model.enums.impl.KingdomType;
import com.lbthreecountry.model.hero.BaseHero;

import java.util.List;

public class LOL_feiaona extends BaseHero {
    public LOL_feiaona() {
        super(
                "lol_feiaona",           // heroId
                "菲奥娜",
                "无双剑姬", // heroTitle
                KingdomType.DMA,     // kingdom
                3,                   // maxHp
                3,                   // startHp
                Gender.FEMALE,                // gender
                "",  // description
                List.of(
                )
        );
    }
}
