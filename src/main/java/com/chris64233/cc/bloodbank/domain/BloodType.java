package com.chris64233.cc.bloodbank.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.EnumSet;
import java.util.Set;

/**
 * ABO 与 Rh 联合血型（8 种）。
 *
 * <p>相容性按红细胞输注规则：供者红细胞携带的每种抗原（A、B、D），受者都必须具备，
 * 即“供者抗原 ⊆ 受者抗原”。例如 O- 不含任何抗原可输给所有血型，AB+ 含全部抗原只能接受全部。
 */
public enum BloodType {

    O_NEG("O-", false, false, false),
    O_POS("O+", false, false, true),
    A_NEG("A-", true, false, false),
    A_POS("A+", true, false, true),
    B_NEG("B-", false, true, false),
    B_POS("B+", false, true, true),
    AB_NEG("AB-", true, true, false),
    AB_POS("AB+", true, true, true);

    private final String label;
    private final boolean aAntigen;
    private final boolean bAntigen;
    private final boolean dAntigen;

    BloodType(String label, boolean aAntigen, boolean bAntigen, boolean dAntigen) {
        this.label = label;
        this.aAntigen = aAntigen;
        this.bAntigen = bAntigen;
        this.dAntigen = dAntigen;
    }

    @JsonValue
    public String label() {
        return label;
    }

    /**
     * 供者单位（this）能否输给指定受者。
     */
    public boolean canDonateTo(BloodType recipient) {
        return (!aAntigen || recipient.aAntigen)
                && (!bAntigen || recipient.bAntigen)
                && (!dAntigen || recipient.dAntigen);
    }

    /**
     * 返回可输给指定受者的全部供者血型。
     */
    public static Set<BloodType> compatibleDonors(BloodType recipient) {
        EnumSet<BloodType> donors = EnumSet.noneOf(BloodType.class);
        for (BloodType donor : values()) {
            if (donor.canDonateTo(recipient)) {
                donors.add(donor);
            }
        }
        return donors;
    }

    @JsonCreator
    public static BloodType fromCode(String code) {
        if (code == null) {
            return null;
        }
        String trimmed = code.trim();
        for (BloodType type : values()) {
            if (type.label.equalsIgnoreCase(trimmed) || type.name().equalsIgnoreCase(trimmed)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知血型: " + code);
    }
}
