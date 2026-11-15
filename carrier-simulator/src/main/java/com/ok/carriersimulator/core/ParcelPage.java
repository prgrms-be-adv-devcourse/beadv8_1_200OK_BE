package com.ok.carriersimulator.core;

import java.util.List;

/**
 * @param total 조건에 맞는 전체 개수
 */
public record ParcelPage(List<ParcelView> items, long total) {
}
