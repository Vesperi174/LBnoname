package com.lbthreecountry.character.LBLOL.hero;

import com.lbthreecountry.model.enums.impl.Gender;
import com.lbthreecountry.model.enums.impl.KingdomType;
import com.lbthreecountry.model.hero.BaseHero;

import java.util.List;

public class LOL_jiawen4 extends BaseHero {
    public LOL_jiawen4() {
        super(
                "lol_jiawen4",           // heroId
                "嘉文四世",
                "德玛西亚皇子", // heroTitle
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
