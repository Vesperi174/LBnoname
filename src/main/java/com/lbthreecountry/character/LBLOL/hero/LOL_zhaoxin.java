package com.lbthreecountry.character.LBLOL.hero;

import com.lbthreecountry.model.enums.impl.Gender;
import com.lbthreecountry.model.enums.impl.KingdomType;
import com.lbthreecountry.model.hero.BaseHero;

import java.util.List;

public class LOL_zhaoxin extends BaseHero {
    public LOL_zhaoxin() {
        super(
                "lol_zhaoxin",           // heroId
                "赵信",
                "德邦总管", // heroTitle
                KingdomType.DMA,     // kingdom
                4,                   // maxHp
                4,                   // startHp
                Gender.MALE,                // gender
                "",  // description
                List.of(
                )
        );
    }
}
