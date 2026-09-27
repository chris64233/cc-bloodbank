package com.chris64233.cc.bloodbank.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 血液成分类型。ABO 相容性按成分类别区分：
 * 红细胞类看"受者血浆中的抗体不能攻击供者红细胞抗原"，
 * 血浆类看"供者血浆中的抗体不能攻击受者红细胞抗原"。
 */
public enum ComponentType {

    RED_CELLS("红细胞", redCellRules()),
    PLASMA("血浆", plasmaRules()),
    PLATELETS("血小板", redCellRules()),
    CRYOPRECIPITATE("冷沉淀", plasmaRules());

    private final String label;
    private final Map<AboGroup, Set<AboGroup>> aboCompatibleDonors;

    ComponentType(String label, Map<AboGroup, Set<AboGroup>> aboCompatibleDonors) {
        this.label = label;
        this.aboCompatibleDonors = aboCompatibleDonors;
    }

    public String getLabel() {
        return label;
    }

    /** 患者 ABO 血型为 patientAbo 时，可接受的供者 ABO 血型集合 */
    public Set<AboGroup> aboCompatibleDonors(AboGroup patientAbo) {
        return aboCompatibleDonors.get(patientAbo);
    }

    private static Map<AboGroup, Set<AboGroup>> redCellRules() {
        // 红细胞：O 是万能供者，AB 只能给 AB
        return Map.of(
                AboGroup.O, EnumSet.of(AboGroup.O),
                AboGroup.A, EnumSet.of(AboGroup.O, AboGroup.A),
                AboGroup.B, EnumSet.of(AboGroup.O, AboGroup.B),
                AboGroup.AB, EnumSet.of(AboGroup.O, AboGroup.A, AboGroup.B, AboGroup.AB));
    }

    private static Map<AboGroup, Set<AboGroup>> plasmaRules() {
        // 血浆：AB 是万能供者，O 只能给 O
        return Map.of(
                AboGroup.O, EnumSet.of(AboGroup.O, AboGroup.A, AboGroup.B, AboGroup.AB),
                AboGroup.A, EnumSet.of(AboGroup.A, AboGroup.AB),
                AboGroup.B, EnumSet.of(AboGroup.B, AboGroup.AB),
                AboGroup.AB, EnumSet.of(AboGroup.AB));
    }
}
