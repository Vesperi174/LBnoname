package com.lbthreecountry.character.LBLOL.hero;

import com.lbthreecountry.model.enums.impl.Gender;
import com.lbthreecountry.model.enums.impl.KingdomType;
import com.lbthreecountry.model.hero.BaseHero;

import java.util.List;

public class LOL_glfs extends BaseHero {
    public LOL_glfs() {
        super(
                "lol_glfs",           // heroId
                "格雷福斯",
                "法外狂徒", // heroTitle
                KingdomType.JOR,     // kingdom
                4,
                4,                   // startHp
                Gender.MALE,                // gender
                "",  // description
                List.of(
                )
        );
    }
}
