package com.lbthreecountry.game.card;

import com.lbthreecountry.game.card.component.EffectComponent;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 效果组件管理器 — 负责注册和查找效果组件
 * <p>
 * 所有 {@link EffectComponent} 的实例通过 Spring 自动注入后注册到此管理器。
 * 卡牌使用时通过组件 ID 从此管理器获取对应的组件实例并执行。
 * </p>
 *
 * <p>
 * <b>组件注册方式：</b>
 * <ul>
 *   <li>方式一（推荐）：组件类标注 {@code @Component}，Spring 自动扫描注入</li>
 *   <li>方式二：通过 {@link #register(EffectComponent)} 手动注册</li>
 *   <li>方式三：在配置文件中指定组件类名，动态加载（适合 Mod）</li>
 * </ul>
 * </p>
 */
@Service
public class EffectManager {

    private static final Logger log = LoggerFactory.getLogger(EffectManager.class);

    /** 组件 ID → 组件实例 */
    private final Map<String, EffectComponent> componentMap = new ConcurrentHashMap<>();

    /** Spring 自动注入的所有 EffectComponent */
    private final List<EffectComponent> components;

    public EffectManager(List<EffectComponent> components) {
        this.components = components;
    }

    /**
     * 初始化：注册所有 Spring 容器中的 EffectComponent
     */
    @PostConstruct
    public void init() {
        for (EffectComponent component : components) {
            register(component);
        }
        log.info("[组件管理器] 已注册 {} 个效果组件: {}", componentMap.size(), componentMap.keySet());
    }

    /**
     * 注册一个效果组件
     *
     * @param component 效果组件实例
     */
    public void register(EffectComponent component) {
        String id = component.getId();
        if (id == null || id.isEmpty()) {
            log.warn("[组件管理器] 跳过无 ID 的组件: {}", component.getClass().getSimpleName());
            return;
        }
        if (componentMap.containsKey(id)) {
            log.warn("[组件管理器] 组件 ID 重复: {}，将覆盖", id);
        }
        componentMap.put(id, component);
        log.debug("[组件管理器] 注册组件: {} -> {}", id, component.getClass().getSimpleName());
    }

    /**
     * 根据 ID 获取效果组件
     *
     * @param id 组件 ID
     * @return 效果组件实例，未找到返回 null
     */
    public EffectComponent getComponent(String id) {
        EffectComponent component = componentMap.get(id);
        if (component == null) {
            log.warn("[组件管理器] 未找到组件: {}", id);
        }
        return component;
    }

    /**
     * 批量获取效果组件
     *
     * @param ids 组件 ID 列表
     * @return 组件实例列表（跳过未找到的）
     */
    public List<EffectComponent> getComponents(List<String> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return ids.stream()
                .map(this::getComponent)
                .filter(c -> c != null)
                .toList();
    }

    /**
     * 检查组件是否存在
     */
    public boolean hasComponent(String id) {
        return componentMap.containsKey(id);
    }

    /**
     * 获取所有已注册的组件 ID
     */
    public Map<String, EffectComponent> getAllComponents() {
        return Map.copyOf(componentMap);
    }

    /**
     * 获取已注册的组件数量
     */
    public int componentCount() {
        return componentMap.size();
    }
}