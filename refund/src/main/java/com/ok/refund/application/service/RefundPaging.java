package com.ok.refund.application.service;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * 환불 목록 조회(구매자·판매자)가 함께 쓰는 페이지 크기와 정렬을 정한다.
 * 두 목록 서비스에 따로 두면 한쪽만 바뀌어 구매자와 판매자 화면의 페이지 크기·순서가 어긋날 수 있어 한곳에 둔다.
 * 빈이 아니라 정적 도우미라 이 패키지 안에서만 쓴다.
 */
final class RefundPaging {

    // 배송 목록과 같은 페이지 크기. 클라이언트가 바꿀 수 없다
    private static final int PAGE_SIZE = 25;
    // 최신순. 같은 시각이면 나중에 만든 요청이 먼저 오도록 ID를 함께 쓴다(페이지 사이에 순서가 흔들리지 않게)
    private static final Sort LATEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private RefundPaging() {
    }

    static Pageable latestFirst(int page) {
        return PageRequest.of(page, PAGE_SIZE, LATEST_FIRST);
    }
}
