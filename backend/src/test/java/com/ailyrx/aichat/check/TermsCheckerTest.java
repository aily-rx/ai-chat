package com.ailyrx.aichat.check;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class TermsCheckerTest {

    private final TermsChecker checker = new TermsChecker(new TermsProperties(Map.of()));

    @Test
    void returnsPassedForEmptyInput() {
        TermsCheckResult result = checker.check(null);

        assertThat(result.passed()).isTrue();
        assertThat(result.count()).isZero();
        assertThat(result.hits()).isEmpty();
    }

    @Test
    void findsTermsWithLineColumnAndSuggestion() {
        TermsCheckResult result = checker.check("我们要即然按排工作，\n不能做为旁观者。");

        assertThat(result.passed()).isFalse();
        assertThat(result.count()).isEqualTo(3);
        assertThat(result.hits()).extracting(TermsHit::term)
                .containsExactly("即然", "按排", "做为");
        TermsHit first = result.hits().get(0);
        assertThat(first.suggestion()).isEqualTo("既然");
        assertThat(first.line()).isEqualTo(1);
        assertThat(first.column()).isEqualTo(4);
        assertThat(first.context()).contains("即然");

        TermsHit last = result.hits().get(2);
        assertThat(last.line()).isEqualTo(2);
        assertThat(last.column()).isEqualTo(3);
    }

    @Test
    void supportsConfiguredOverrides() {
        TermsChecker customized = new TermsChecker(
                new TermsProperties(Map.of("测试词", "测试建议")));

        TermsCheckResult result = customized.check("这是测试词。");

        assertThat(result.count()).isEqualTo(1);
        assertThat(result.hits().get(0).suggestion()).isEqualTo("测试建议");
        assertThat(result.hits().get(0).category()).isEqualTo("自定义");
    }

    @Test
    void returnsNoHitsForNormalText() {
        TermsCheckResult result = checker.check("各部门要立即开展安全生产检查，确保责任落实。");

        assertThat(result.passed()).isTrue();
        assertThat(result.count()).isZero();
    }
}
