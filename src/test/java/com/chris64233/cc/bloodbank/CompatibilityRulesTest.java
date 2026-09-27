package com.chris64233.cc.bloodbank;

import com.chris64233.cc.bloodbank.domain.AboGroup;
import com.chris64233.cc.bloodbank.domain.ComponentType;
import com.chris64233.cc.bloodbank.domain.RhFactor;
import com.chris64233.cc.bloodbank.service.RhCompatibility;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 血型相容规则：红细胞类"受者抗体不攻击供者抗原"，血浆类反向；Rh 阴性患者只接受 Rh 阴性。
 */
class CompatibilityRulesTest {

    @Test
    void redCellAboCompatibility() {
        assertThat(ComponentType.RED_CELLS.aboCompatibleDonors(AboGroup.O)).containsExactly(AboGroup.O);
        assertThat(ComponentType.RED_CELLS.aboCompatibleDonors(AboGroup.A))
                .containsExactlyInAnyOrder(AboGroup.O, AboGroup.A);
        assertThat(ComponentType.RED_CELLS.aboCompatibleDonors(AboGroup.B))
                .containsExactlyInAnyOrder(AboGroup.O, AboGroup.B);
        assertThat(ComponentType.RED_CELLS.aboCompatibleDonors(AboGroup.AB))
                .containsExactlyInAnyOrder(AboGroup.O, AboGroup.A, AboGroup.B, AboGroup.AB);
    }

    @Test
    void plasmaAboCompatibilityIsReversed() {
        assertThat(ComponentType.PLASMA.aboCompatibleDonors(AboGroup.AB)).containsExactly(AboGroup.AB);
        assertThat(ComponentType.PLASMA.aboCompatibleDonors(AboGroup.A))
                .containsExactlyInAnyOrder(AboGroup.A, AboGroup.AB);
        assertThat(ComponentType.PLASMA.aboCompatibleDonors(AboGroup.B))
                .containsExactlyInAnyOrder(AboGroup.B, AboGroup.AB);
        assertThat(ComponentType.PLASMA.aboCompatibleDonors(AboGroup.O))
                .containsExactlyInAnyOrder(AboGroup.O, AboGroup.A, AboGroup.B, AboGroup.AB);
    }

    @Test
    void plateletsFollowRedCellRulesAndCryoFollowsPlasmaRules() {
        assertThat(ComponentType.PLATELETS.aboCompatibleDonors(AboGroup.A))
                .isEqualTo(ComponentType.RED_CELLS.aboCompatibleDonors(AboGroup.A));
        assertThat(ComponentType.CRYOPRECIPITATE.aboCompatibleDonors(AboGroup.A))
                .isEqualTo(ComponentType.PLASMA.aboCompatibleDonors(AboGroup.A));
    }

    @Test
    void rhNegativePatientOnlyAcceptsRhNegative() {
        assertThat(RhCompatibility.acceptableDonorRh(RhFactor.NEGATIVE))
                .containsExactly(RhFactor.NEGATIVE);
        assertThat(RhCompatibility.acceptableDonorRh(RhFactor.POSITIVE))
                .containsExactlyInAnyOrder(RhFactor.POSITIVE, RhFactor.NEGATIVE);
    }

    @Test
    void componentTypeIsNotCompatibleWithItselfAcrossCategories() {
        // 血浆对 AB 患者只有 AB 可用，而红细胞对 AB 患者全部可用，二者方向相反
        Set<AboGroup> redCellForAb = ComponentType.RED_CELLS.aboCompatibleDonors(AboGroup.AB);
        Set<AboGroup> plasmaForAb = ComponentType.PLASMA.aboCompatibleDonors(AboGroup.AB);
        assertThat(redCellForAb).hasSize(4);
        assertThat(plasmaForAb).hasSize(1);
    }
}
