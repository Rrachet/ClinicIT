package com.clinicit.noshow;

import com.clinicit.noshow.domain.NoShowRisk.Level;
import com.clinicit.noshow.domain.NoShowRiskRule;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NoShowRiskRuleTest {

    @Test
    void tooLittleHistoryIsNeverJudged() {
        assertThat(NoShowRiskRule.assess(0, 0, 0.1).level()).isEqualTo(Level.UNKNOWN);
        assertThat(NoShowRiskRule.assess(2, 2, 0.1).level()).isEqualTo(Level.UNKNOWN);
    }

    @Test
    void elevatedNeedsTwoMissesAtThirtyPercentAndTwiceTheClinicRate() {
        assertThat(NoShowRiskRule.assess(5, 2, 0.08).level()).isEqualTo(Level.ELEVATED);   // 40% >= max(30%, 16%)
        assertThat(NoShowRiskRule.assess(10, 2, 0.08).level()).isEqualTo(Level.TYPICAL);   // 20% < 30%
        assertThat(NoShowRiskRule.assess(3, 1, 0.08).level()).isEqualTo(Level.TYPICAL);    // one miss is not a pattern
        assertThat(NoShowRiskRule.assess(5, 2, 0.25).level()).isEqualTo(Level.TYPICAL);    // 40% < twice a 25% clinic
        assertThat(NoShowRiskRule.assess(4, 3, null).level()).isEqualTo(Level.ELEVATED);   // no clinic rate: 30% floor
    }

    @Test
    void itReportsTheCountsItWasBasedOn() {
        var risk = NoShowRiskRule.assess(6, 3, 0.1);
        assertThat(risk.priorAppointments()).isEqualTo(6);
        assertThat(risk.priorMissed()).isEqualTo(3);
    }
}
