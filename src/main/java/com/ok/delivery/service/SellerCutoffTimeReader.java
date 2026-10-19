package com.ok.delivery.service;

import java.time.LocalTime;

/**
 * 판매자의 출고 마감 시간을 조회한다.
 * 마감 시간은 판매자가 등록하는 값이 될 예정이라, 저장 위치가 정해지면 구현체만 바꾸도록 인터페이스로 둔다.
 */
public interface SellerCutoffTimeReader {

    LocalTime cutoffTime(Long sellerId);
}
