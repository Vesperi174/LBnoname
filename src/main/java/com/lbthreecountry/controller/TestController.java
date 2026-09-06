package com.lbthreecountry.controller;


import com.lbthreecountry.model.enums.impl.CardType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// 测试控制器
@RestController
public class TestController {

    @GetMapping("/test")
    public CardType test() {
        return CardType.BASIC;
    }
}
