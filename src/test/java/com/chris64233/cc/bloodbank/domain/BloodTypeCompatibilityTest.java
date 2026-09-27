package com.chris64233.cc.bloodbank.domain;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BloodTypeCompatibilityTest {

    @Test
    void oNegativeIsUniversalDonor() {
        for (BloodType recipient : BloodType.values()) {
            assertThat(BloodType.O_NEG.canDonateTo(recipient))
                    .as("O- 应可输给 %s", recipient)
                    .isTrue();
        }
    }

    @Test
    void abPositiveIsUniversalRecipient() {
        assertThat(BloodType.compatibleDonors(BloodType.AB_POS))
                .containsExactlyInAnyOrder(BloodType.values());
    }

    @Test
    void abPositiveOnlyDonatesToItself() {
        for (BloodType recipient : BloodType.values()) {
            assertThat(BloodType.AB_POS.canDonateTo(recipient))
                    .isEqualTo(recipient == BloodType.AB_POS);
        }
    }

    @Test
    void oNegativeOnlyReceivesItself() {
        assertThat(BloodType.compatibleDonors(BloodType.O_NEG))
                .containsExactly(BloodType.O_NEG);
    }

    @Test
    void aPositiveReceivesExpectedFourTypes() {
        // A+ 受者：O-、O+、A-、A+
        assertThat(BloodType.compatibleDonors(BloodType.A_POS))
                .containsExactlyInAnyOrder(
                        BloodType.O_NEG, BloodType.O_POS, BloodType.A_NEG, BloodType.A_POS);
    }

    @Test
    void rhNegativeRecipientNeverReceivesRhPositiveDonor() {
        Set<BloodType> donors = BloodType.compatibleDonors(BloodType.B_NEG);
        assertThat(donors).containsExactlyInAnyOrder(BloodType.O_NEG, BloodType.B_NEG);
    }

    @Test
    void parsesLabelsAndNames() {
        assertThat(BloodType.fromCode("AB+")).isEqualTo(BloodType.AB_POS);
        assertThat(BloodType.fromCode("o-")).isEqualTo(BloodType.O_NEG);
        assertThat(BloodType.fromCode("A_POS")).isEqualTo(BloodType.A_POS);
        assertThatThrownBy(() -> BloodType.fromCode("X+"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
