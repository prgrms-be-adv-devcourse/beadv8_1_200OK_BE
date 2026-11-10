package com.ok.member.seller.application.port.in;

import com.ok.common.exception.RestApiException;
import com.ok.member.seller.application.dto.SellerApplicationAdminResult;
import com.ok.member.seller.application.dto.SellerApplicationRejectCommand;
import com.ok.member.seller.application.dto.SellerApplicationResult;
import com.ok.member.seller.application.dto.SellerApplicationSearchQuery;
import com.ok.member.seller.application.dto.SellerApplyCommand;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * 판매자 신청 유스케이스. 회원의 신청·조회와 관리자의 조회·심사를 함께 맡는다.
 */
public interface SellerApplicationUseCase {

    /**
     * 판매자 등록을 신청한다. 신청은 심사 대기 상태로 저장된다.
     * 거절된 신청만 있으면 대기 기간 없이 다시 신청할 수 있다.
     *
     * @throws RestApiException 심사 대기·심사 중인 신청이 있으면 {@code SELLER_APPLICATION_IN_PROGRESS},
     *                          이미 승인됐으면 {@code ALREADY_SELLER},
     *                          진행 중·승인 신청이 같은 사업자등록번호를 쓰면 {@code DUPLICATE_BUSINESS_NUMBER}
     */
    Long apply(Long memberId, SellerApplyCommand command);

    /**
     * 회원의 신청 내역을 최근 신청부터 반환한다. 거절된 신청도 이력으로 함께 반환한다.
     */
    List<SellerApplicationResult> getMyApplications(Long memberId);

    /**
     * 회원 본인의 신청 한 건을 반환한다.
     * 다른 회원의 신청은 존재 여부를 드러내지 않도록 없는 신청과 같게 처리한다.
     *
     * @throws RestApiException 본인의 신청이 아니거나 없으면 {@code SELLER_APPLICATION_NOT_FOUND}
     */
    SellerApplicationResult getMyApplication(Long memberId, Long applicationId);

    /**
     * 관리자용 신청 목록을 오래된 신청부터 한 페이지씩 반환한다.
     *
     * @param query 상태가 {@code null}이면 모든 상태를 조회한다
     */
    Page<SellerApplicationAdminResult> getApplications(SellerApplicationSearchQuery query);

    /**
     * 관리자용 신청 상세를 반환한다.
     *
     * @throws RestApiException 신청이 없으면 {@code SELLER_APPLICATION_NOT_FOUND}
     */
    SellerApplicationAdminResult getApplication(Long applicationId);

    /**
     * 심사를 시작한다.
     *
     * @throws RestApiException 신청이 없으면 {@code SELLER_APPLICATION_NOT_FOUND},
     *                          심사 대기가 아니면 {@code INVALID_SELLER_APPLICATION_STATUS},
     *                          다른 관리자가 먼저 처리했으면 {@code SELLER_APPLICATION_CONCURRENT_MODIFICATION}
     */
    SellerApplicationAdminResult startReview(Long applicationId);

    /**
     * 신청을 승인하고 판매자 승인 이벤트를 발행한다. 이벤트를 받은 wallet 모듈이 판매자 지갑을 만든다.
     *
     * @param adminId 승인을 결정한 관리자. 신청에 심사자로 기록한다
     * @throws RestApiException 신청이 없으면 {@code SELLER_APPLICATION_NOT_FOUND},
     *                          심사 중이 아니면 {@code INVALID_SELLER_APPLICATION_STATUS},
     *                          다른 관리자가 먼저 처리했으면 {@code SELLER_APPLICATION_CONCURRENT_MODIFICATION}
     */
    SellerApplicationAdminResult approve(Long adminId, Long applicationId);

    /**
     * 신청을 거절한다. 거절된 회원은 바로 다시 신청할 수 있다.
     *
     * @param adminId 거절을 결정한 관리자. 신청에 심사자로 기록한다
     * @throws RestApiException 신청이 없으면 {@code SELLER_APPLICATION_NOT_FOUND},
     *                          사유가 비었거나 너무 길면 {@code INVALID_REJECT_REASON},
     *                          심사 대기·심사 중이 아니면 {@code INVALID_SELLER_APPLICATION_STATUS},
     *                          다른 관리자가 먼저 처리했으면 {@code SELLER_APPLICATION_CONCURRENT_MODIFICATION}
     */
    SellerApplicationAdminResult reject(Long adminId, Long applicationId, SellerApplicationRejectCommand command);

}
