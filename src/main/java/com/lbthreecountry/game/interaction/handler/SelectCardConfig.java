package com.lbthreecountry.game.interaction.handler;

import java.util.List;
import java.util.Map;

/**
 * 选牌交互配置 — 描述一次"选牌 → 可选确认"交互的全部参数
 *
 * <p>用于 {@link ResponseHandler#selectCardWithConfirm} 和
 * {@link ResponseHandler#selectCard} 方法，配置交互的文案、超时、过滤条件等。</p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * SelectCardConfig config = SelectCardConfig.builder()
 *         .selectDescription("【杀】对你使用，请使用一张【闪】")
 *         .confirmDescription("确定要使用【闪】来抵消【杀】吗？")
 *         .filter(HandCardFilter.allowOnly("shan"))
 *         .requireConfirm(true)
 *         .timeout(15)
 *         .thinkingDescription("玩家【闪】思考中")
 *         .build();
 * }</pre>
 */
public final class SelectCardConfig {

    /** 选牌阶段的提示文案（必填） */
    private final String selectDescription;

    /** 确认阶段的提示文案（仅 requireConfirm=true 时需要） */
    private final String confirmDescription;

    /** 手牌过滤器（必填） */
    private final HandCardFilter filter;

    /** 是否需要确认步骤（两步：选牌 → 确认；false=选牌即确认） */
    private final boolean requireConfirm;

    /** 前端超时秒数 */
    private final int timeout;

    /** 超时总长（用于前端进度条比例计算，默认 = timeout） */
    private final int totalTimeout;

    /** 选牌阶段按钮配置，默认仅一个"取消"按钮 */
    private final List<Map<String, Object>> selectActions;

    /** 确认阶段按钮配置，默认"确定"+"取消" */
    private final List<Map<String, Object>> confirmActions;

    /** 广播思考状态时的描述文字 */
    private final String thinkingDescription;

    // ── 构造 ──

    private SelectCardConfig(Builder builder) {
        this.selectDescription = builder.selectDescription;
        this.confirmDescription = builder.confirmDescription;
        this.filter = builder.filter;
        this.requireConfirm = builder.requireConfirm;
        this.timeout = builder.timeout;
        this.totalTimeout = builder.totalTimeout;
        this.selectActions = builder.selectActions;
        this.confirmActions = builder.confirmActions;
        this.thinkingDescription = builder.thinkingDescription;
    }

    public static Builder builder() {
        return new Builder();
    }

    // ── Getter ──

    public String getSelectDescription()        { return selectDescription; }
    public String getConfirmDescription()       { return confirmDescription; }
    public HandCardFilter getFilter()           { return filter; }
    public boolean isRequireConfirm()           { return requireConfirm; }
    public int getTimeout()                     { return timeout; }
    public int getTotalTimeout()                { return totalTimeout; }
    public List<Map<String, Object>> getSelectActions()  { return selectActions; }
    public List<Map<String, Object>> getConfirmActions() { return confirmActions; }
    public String getThinkingDescription()      { return thinkingDescription; }

    // ── Builder ──

    public static class Builder {
        private String selectDescription;
        private String confirmDescription;
        private HandCardFilter filter;
        private boolean requireConfirm = true;
        private int timeout = 15;
        private int totalTimeout = 15;
        private List<Map<String, Object>> selectActions;
        private List<Map<String, Object>> confirmActions;
        private String thinkingDescription = "玩家决策中";

        Builder() {}

        /** 选牌阶段的提示文案（必填） */
        public Builder selectDescription(String val) {
            this.selectDescription = val;
            return this;
        }

        /** 确认阶段的提示文案 */
        public Builder confirmDescription(String val) {
            this.confirmDescription = val;
            return this;
        }

        /** 手牌过滤器（必填） */
        public Builder filter(HandCardFilter val) {
            this.filter = val;
            return this;
        }

        /** 是否需要确认步骤（默认 true） */
        public Builder requireConfirm(boolean val) {
            this.requireConfirm = val;
            return this;
        }

        /** 前端超时秒数（默认 15） */
        public Builder timeout(int val) {
            this.timeout = val;
            return this;
        }

        /** 超时总长（用于前端进度条比例计算，默认 = timeout） */
        public Builder totalTimeout(int val) {
            this.totalTimeout = val;
            return this;
        }

        /** 选牌阶段自定义按钮 */
        public Builder selectActions(List<Map<String, Object>> val) {
            this.selectActions = val;
            return this;
        }

        /** 确认阶段自定义按钮 */
        public Builder confirmActions(List<Map<String, Object>> val) {
            this.confirmActions = val;
            return this;
        }

        /** 广播思考状态描述文字（默认"玩家决策中"） */
        public Builder thinkingDescription(String val) {
            this.thinkingDescription = val;
            return this;
        }

        public SelectCardConfig build() {
            if (selectDescription == null || selectDescription.isEmpty()) {
                throw new IllegalArgumentException("selectDescription must not be empty");
            }
            if (filter == null) {
                throw new IllegalArgumentException("filter must not be null");
            }
            return new SelectCardConfig(this);
        }
    }
}