package com.lbthreecountry.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 本地名称配置 — 从 application.yml 读取
 *
 * <p>玩家打开项目时：</p>
 * <ol>
 *   <li>读取本地配置（yml / 环境变量）</li>
 *   <li>有名称 → 自动使用</li>
 *   <li>无名称 → 使用默认名称"无名客"</li>
 * </ol>
 *
 * <p>前端也可以将名称保存在浏览器 localStorage 中，
 * 但服务端侧仍保留此配置作为兜底。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "player")
public class LocalNameConfig {

    /**
     * 默认玩家名称（未配置时使用）
     */
    private String defaultName = "无名客";

    /**
     * 是否允许游戏中改名
     */
    private boolean allowRename = true;

    @PostConstruct
    public void init() {
        System.out.println("========================================");
        System.out.println("玩家配置已加载");
        System.out.println("  默认名称: " + defaultName);
        System.out.println("  允许改名: " + allowRename);
        System.out.println("========================================");
    }
}