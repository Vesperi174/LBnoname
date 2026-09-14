package com.lbthreecountry;

import com.lbthreecountry.game.card.CardLibrary;
import com.lbthreecountry.game.card.EffectManager;
import com.lbthreecountry.model.card.def.CardCopy;
import com.lbthreecountry.model.card.def.CardDef;
import com.lbthreecountry.model.card.def.CardRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 卡牌系统集成测试 — 验证 JSON 加载 + Java 注册 + 组件管理
 * <p>
 * 测试内容：
 * <ul>
 *   <li>所有卡牌是否正常加载（JSON + CardRegistry 合并）</li>
 *   <li>卡牌的固有属性是否正确（名称、描述、花色、点数）</li>
 *   <li>卡牌的行为属性是否正确（类型、规则、组件）</li>
 *   <li>效果组件是否正确注册</li>
 *   <li>输出完整的卡牌加载报告</li>
 * </ul>
 * </p>
 */
@SpringBootTest
@DisplayName("卡牌系统测试")
class CardSystemTest {

    @Autowired
    private CardLibrary cardLibrary;

    @Autowired
    private EffectManager effectManager;

    // ================================================================
    //  基础加载验证
    // ================================================================

    @Test
    @DisplayName("卡牌加载数量验证")
    void testCardCount() {
        assertThat(cardLibrary.defCount()).as("卡牌种类数").isEqualTo(26);
        assertThat(cardLibrary.totalCopyCount()).as("实体副本总数").isEqualTo(100);
    }

    @Test
    @DisplayName("效果组件注册验证")
    void testEffectComponents() {
        assertThat(effectManager.componentCount()).as("效果组件数").isEqualTo(2);
        assertThat(effectManager.hasComponent("sha_effect")).isTrue();
        assertThat(effectManager.hasComponent("shan_effect")).isTrue();
    }

    @Test
    @DisplayName("卡牌类型分布验证")
    void testCardTypeDistribution() {
        assertThat(cardLibrary.getByType("BASIC")).as("基本牌").hasSize(3);
        assertThat(cardLibrary.getByType("STRATEGY")).as("锦囊牌").hasSize(13);
        assertThat(cardLibrary.getByType("EQUIPMENT")).as("装备牌").hasSize(10);
    }

    // ================================================================
    //  具体卡牌验证
    // ================================================================

    @Test
    @DisplayName("【杀】基础数据验证")
    void testSha() {
        CardDef sha = cardLibrary.getDef("sha");
        assertThat(sha).isNotNull();
        assertThat(sha.getName()).isEqualTo("杀");
        assertThat(sha.getDescription()).contains("闪");
        assertThat(sha.getType()).isEqualTo("BASIC");
        assertThat(sha.getSubType()).isEqualTo("SHA");
        assertThat(sha.getCopies()).hasSize(22);
        assertThat(sha.getOnUseComponents()).containsExactly("sha_effect");
        assertThat(sha.getRules().getPlayablePhase()).isEqualTo("PLAY");
        assertThat(sha.getRules().getMaxPerTurn()).isEqualTo(1);
    }

    @Test
    @DisplayName("【闪】基础数据验证")
    void testShan() {
        CardDef shan = cardLibrary.getDef("shan");
        assertThat(shan).isNotNull();
        assertThat(shan.getName()).isEqualTo("闪");
        assertThat(shan.getType()).isEqualTo("BASIC");
        assertThat(shan.getSubType()).isEqualTo("SHAN");
        assertThat(shan.getCopies()).hasSize(16);
        assertThat(shan.getOnRespondComponents()).containsExactly("shan_effect");
        assertThat(shan.getRules().getCanRespondTo()).contains("SHA");
    }

    @Test
    @DisplayName("【桃】基础数据验证")
    void testTao() {
        CardDef tao = cardLibrary.getDef("tao");
        assertThat(tao).isNotNull();
        assertThat(tao.getName()).isEqualTo("桃");
        assertThat(tao.getType()).isEqualTo("BASIC");
        assertThat(tao.getCopies()).hasSize(8);
        assertThat(tao.getRules().getTargetType()).isEqualTo("SELF");
    }

    @Test
    @DisplayName("【无中生有】锦囊牌验证")
    void testWuzhong() {
        CardDef wuzhong = cardLibrary.getDef("wuzhong");
        assertThat(wuzhong).isNotNull();
        assertThat(wuzhong.getName()).isEqualTo("无中生有");
        assertThat(wuzhong.getType()).isEqualTo("STRATEGY");
        assertThat(wuzhong.getSubType()).isEqualTo("WUZHONG");
        assertThat(wuzhong.getCopies()).hasSize(4);
        assertThat(wuzhong.getRules().getTargetCount()).isZero();
    }

    @Test
    @DisplayName("【南蛮入侵】AOE锦囊验证")
    void testNanman() {
        CardDef nanman = cardLibrary.getDef("nanman");
        assertThat(nanman).isNotNull();
        assertThat(nanman.getName()).isEqualTo("南蛮入侵");
        assertThat(nanman.getType()).isEqualTo("STRATEGY");
        assertThat(nanman.getCopies()).hasSize(3);
        assertThat(nanman.getRules().getTargetCount()).isZero(); // AOE无目标选择
    }

    @Test
    @DisplayName("【青龙偃月刀】装备牌验证")
    void testWeaponEquipment() {
        CardDef blade = cardLibrary.getDef("qinglong_blade");
        assertThat(blade).isNotNull();
        assertThat(blade.getType()).isEqualTo("EQUIPMENT");
        assertThat(blade.getRules().getEquipSlot()).isEqualTo("WEAPON");
        assertThat(blade.getRules().getAttackRange()).isEqualTo(3);
        assertThat(blade.getCopies()).hasSize(2);
    }

    @Test
    @DisplayName("【仁王盾】防具牌验证")
    void testArmorEquipment() {
        CardDef armor = cardLibrary.getDef("renwang");
        assertThat(armor).isNotNull();
        assertThat(armor.getType()).isEqualTo("EQUIPMENT");
        assertThat(armor.getRules().getEquipSlot()).isEqualTo("ARMOR");
        assertThat(armor.getCopies()).hasSize(1);
    }

    @Test
    @DisplayName("【赤兔】-1马验证")
    void testMountMinus() {
        CardDef chitu = cardLibrary.getDef("chitu");
        assertThat(chitu).isNotNull();
        assertThat(chitu.getType()).isEqualTo("EQUIPMENT");
        assertThat(chitu.getRules().getEquipSlot()).isEqualTo("MOUNT_MINUS");
    }

    @Test
    @DisplayName("【的卢】+1马验证")
    void testMountPlus() {
        CardDef dilu = cardLibrary.getDef("di_lu");
        assertThat(dilu).isNotNull();
        assertThat(dilu.getType()).isEqualTo("EQUIPMENT");
        assertThat(dilu.getRules().getEquipSlot()).isEqualTo("MOUNT_PLUS");
    }

    @Test
    @DisplayName("【闪电】延时锦囊验证")
    void testLightning() {
        CardDef lightning = cardLibrary.getDef("lightning");
        assertThat(lightning).isNotNull();
        assertThat(lightning.getName()).isEqualTo("闪电");
        assertThat(lightning.getType()).isEqualTo("STRATEGY");
        assertThat(lightning.getCopies()).hasSize(2);
        assertThat(lightning.getOnUseComponents()).isNull(); // 未配置效果组件
    }

    // ================================================================
    //  副本数据验证
    // ================================================================

    @Test
    @DisplayName("所有卡牌副本花色点数不为空")
    void testCopiesNotEmpty() {
        for (CardDef def : cardLibrary.getAllDefs()) {
            assertThat(def.getCopies())
                    .as("卡牌 %s 的副本", def.getId())
                    .isNotEmpty();
            // 验证每张副本都有花色和点数
            for (CardCopy copy : def.getCopies()) {
                assertThat(copy.getSuit()).as("%s 副本花色", def.getId()).isNotNull();
                assertThat(copy.getPoint()).as("%s 副本点数", def.getId()).isBetween(1, 13);
            }
        }
    }

    @Test
    @DisplayName("所有卡牌归属类型验证")
    void testAllCardsHaveType() {
        for (CardDef def : cardLibrary.getAllDefs()) {
            assertThat(def.getType())
                    .as("卡牌 %s 的类型", def.getId())
                    .isIn("BASIC", "STRATEGY", "EQUIPMENT");
        }
    }

    // ================================================================
    //  统计输出（核心：打印完整的卡牌加载报告）
    // ================================================================

    @Test
    @DisplayName("输出完整卡牌加载报告")
    void printAllCards() {
        System.out.println();
        System.out.println("=".repeat(100));
        System.out.println("  三国杀 · 卡牌系统加载报告");
        System.out.println("=".repeat(100));

        // 按类型分组输出
        printCardsByGroup("🛡️  基本牌 (BASIC)", "BASIC");
        printCardsByGroup("📜  锦囊牌 (STRATEGY)", "STRATEGY");
        printCardsByGroup("⚔️  装备牌 (EQUIPMENT)", "EQUIPMENT");

        System.out.println("=".repeat(100));
        System.out.printf("  📊 总计: %d 种卡牌, %d 张实体副本%n",
                cardLibrary.defCount(), cardLibrary.totalCopyCount());
        System.out.printf("  📦 效果组件: %s%n", effectManager.getAllComponents().keySet());
        System.out.println("=".repeat(100));
    }

    /** 输出一组卡牌的详细信息 */
    private void printCardsByGroup(String title, String type) {
        List<CardDef> cards = cardLibrary.getByType(type);

        System.out.println();
        System.out.println("  " + title);
        System.out.println("  " + "-".repeat(96));

        for (CardDef def : cards) {
            System.out.printf("  %-20s ", def.getName());
            System.out.print(formatCopiesSummary(def.getCopies()));
            System.out.print("  ");
            System.out.print(formatRules(def.getRules()));
            System.out.print("  ");
            System.out.print(formatComponents(def.getComponents()));
            System.out.println();

            if (def.getDescription() != null && !def.getDescription().isEmpty()) {
                System.out.printf("  %-20s 📝 %s%n", "", def.getDescription());
            }
            System.out.println();
        }
    }

    /** 格式化副本摘要：S7 S8 S9 S10 ... */
    private String formatCopiesSummary(List<CardCopy> copies) {
        if (copies == null || copies.isEmpty()) return "无副本";

        // 统计每种花色有几张
        Map<String, Long> suitCount = copies.stream()
                .collect(Collectors.groupingBy(CardCopy::getSuit, Collectors.counting()));

        String suitStr = suitCount.entrySet().stream()
                .map(e -> {
                    String icon = switch (e.getKey()) {
                        case "SPADES" -> "♠";
                        case "HEARTS" -> "♥";
                        case "CLUBS" -> "♣";
                        case "DIAMONDS" -> "♦";
                        default -> "?";
                    };
                    return icon + e.getValue() + "张";
                })
                .collect(Collectors.joining(" "));

        return String.format("%-20s", suitStr);
    }

    /** 格式化规则信息 */
    private String formatRules(CardRules rules) {
        if (rules == null) return "无规则";
        List<String> parts = new ArrayList<>();

        if (rules.getEquipSlot() != null) {
            parts.add("装备位:" + rules.getEquipSlot());
        }
        if (rules.getTargetCount() > 0) {
            parts.add("目标x" + rules.getTargetCount());
        }
        if (rules.getTargetType() != null && !"ANY".equals(rules.getTargetType())) {
            parts.add(rules.getTargetType());
        }
        if (rules.getMaxPerTurn() < 999) {
            parts.add("限" + rules.getMaxPerTurn() + "次/回合");
        }
        if (rules.getPlayablePhase() != null && !"PLAY".equals(rules.getPlayablePhase())) {
            parts.add(rules.getPlayablePhase());
        }
        if (rules.getAttackRange() > 1) {
            parts.add("攻击范围" + rules.getAttackRange());
        }
        if (rules.getCanRespondTo() != null && !rules.getCanRespondTo().isEmpty()) {
            parts.add("响应:" + String.join(",", rules.getCanRespondTo()));
        }

        return String.format("%-30s", parts.isEmpty() ? "" : String.join(" ", parts));
    }

    /** 格式化组件信息 */
    private String formatComponents(Map<String, List<String>> components) {
        if (components == null || components.isEmpty()) return "";
        return components.entrySet().stream()
                .flatMap(e -> e.getValue().stream())
                .collect(Collectors.joining(","));
    }
}