package com.ok.carriersimulator.core;

/**
 * 송장 목록을 거르는 조건. null인 항목은 거르지 않는다.
 */
public record ParcelFilter(String numberPrefix, Scenario scenario, StatusCode status) {

    public static ParcelFilter all() {
        return new ParcelFilter(null, null, null);
    }

    boolean isEmpty() {
        return (numberPrefix == null || numberPrefix.isBlank()) && scenario == null && status == null;
    }
}
