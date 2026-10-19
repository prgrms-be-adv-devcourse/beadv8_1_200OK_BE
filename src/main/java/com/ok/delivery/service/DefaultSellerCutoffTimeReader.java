package com.ok.delivery.service;

import org.springframework.stereotype.Component;

import java.time.LocalTime;

/**
 * 모든 판매자에게 기본 출고 마감 시간(13:00)을 돌려준다.
 */
@Component
class DefaultSellerCutoffTimeReader implements SellerCutoffTimeReader {

    // TODO(논의 필요): 판매자가 마감 시간을 등록하는 방식(저장 위치, 조회 경로)이 협의되면 판매자 값을 읽는 구현으로 바꾼다. 그전까지 설계 문서의 기본값을 쓴다.
    // DB 조회로 바꾸면 판매자마다 조회하지 않도록 판매자 ID 목록을 한 번에 조회하는 방식으로 바꾼다.
    private static final LocalTime DEFAULT_CUTOFF_TIME = LocalTime.of(13, 0);

    @Override
    public LocalTime cutoffTime(Long sellerId) {
        return DEFAULT_CUTOFF_TIME;
    }
}
