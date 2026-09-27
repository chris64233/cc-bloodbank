package com.chris64233.cc.bloodbank.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 血液成分类型。预留要求成分严格一致。
 */
public enum ComponentType {

    RED_CELLS("RBC", "去白细胞悬浮红细胞"),
    FROZEN_PLASMA("PLASMA", "新鲜冰冻血浆"),
    PLATELETS("PLATELET", "单采血小板");

    private final String code;
    private final String description;

    ComponentType(String code, String description) {
        this.code = code;
        this.description = description;
    }

    @JsonValue
    public String code() {
        return code;
    }

    public String description() {
        return description;
    }

    @JsonCreator
    public static ComponentType fromCode(String code) {
        if (code == null) {
            return null;
        }
        String trimmed = code.trim();
        for (ComponentType type : values()) {
            if (type.code.equalsIgnoreCase(trimmed) || type.name().equalsIgnoreCase(trimmed)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知成分类型: " + code);
    }
}
