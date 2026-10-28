package com.ok.member.seller.service;

import com.ok.common.exception.RestApiException;
import com.ok.member.seller.domain.SellerApplication;
import com.ok.member.seller.domain.SellerApplicationStatus;
import com.ok.member.seller.exception.SellerErrorCode;
import com.ok.member.seller.repository.SellerApplicationRepository;
import com.ok.member.seller.service.dto.SellerApplicationAdminResult;
import com.ok.member.seller.service.dto.SellerApplicationRejectCommand;
import com.ok.member.seller.service.dto.SellerApplicationResult;
import com.ok.member.seller.service.dto.SellerApplicationSearchQuery;
import com.ok.member.seller.service.dto.SellerApplyCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 판매자 신청을 처리한다. 회원의 신청·조회와 관리자의 조회·심사를 함께 맡는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SellerApplicationService {

    // 먼저 신청한 순서대로 심사하도록 오래된 신청부터 보여 준다. 같은 일시면 id로 순서를 고정해 페이지 사이에서 흔들리지 않게 한다
    private static final Sort OLDEST_FIRST = Sort.by(Sort.Order.asc("appliedAt"), Sort.Order.asc("id"));

    private final SellerApplicationRepository sellerApplicationRepository;
    private final SellerApplicationDuplicateValidator duplicateValidator;
    private final SellerEventPublisher sellerEventPublisher;
    private final Clock clock;

    /**
     * 판매자 등록을 신청한다. 신청은 심사 대기 상태로 저장된다.
     * 거절된 신청만 있으면 대기 기간 없이 다시 신청할 수 있다.
     *
     * @throws RestApiException 진행 중·승인 신청이 있거나 사업자등록번호가 겹치면 중복 에러
     *                          (자세한 코드는 {@link SellerApplicationDuplicateValidator#validate})
     */
    @Transactional
    public Long apply(Long memberId, SellerApplyCommand command) {
        duplicateValidator.validate(memberId, command.businessNumber());

        SellerApplication application = SellerApplication.apply(
                memberId,
                command.storeName(),
                command.representativeName(),
                command.businessNumber(),
                command.mailOrderNumber(),
                now()
        );

        return save(application).getId();
    }

    /**
     * 회원의 신청 내역을 최근 신청부터 반환한다. 거절된 신청도 이력으로 함께 반환한다.
     */
    public List<SellerApplicationResult> getMyApplications(Long memberId) {
        return sellerApplicationRepository.findAllByMemberIdOrderByAppliedAtDescIdDesc(memberId).stream()
                .map(SellerApplicationResult::from)
                .toList();
    }

    /**
     * 회원 본인의 신청 한 건을 반환한다.
     * 다른 회원의 신청은 존재 여부를 드러내지 않도록 없는 신청과 같게 처리한다.
     *
     * @throws RestApiException 본인의 신청이 아니거나 없으면 {@code SELLER_APPLICATION_NOT_FOUND}
     */
    public SellerApplicationResult getMyApplication(Long memberId, Long applicationId) {
        return sellerApplicationRepository.findByIdAndMemberId(applicationId, memberId)
                .map(SellerApplicationResult::from)
                .orElseThrow(() -> new RestApiException(SellerErrorCode.SELLER_APPLICATION_NOT_FOUND));
    }

    /**
     * 승인된 판매자인지 확인한다. 토큰을 발급할 때 SELLER 권한을 줄지 정하는 데 쓴다.
     * 판매자 여부를 판단하는 규칙을 판매자 쪽에 두어, 다른 하위 도메인(인증)이 신청 데이터를 직접 읽지 않게 한다.
     */
    public boolean isApprovedSeller(Long memberId) {
        return sellerApplicationRepository.existsByMemberIdAndStatus(memberId, SellerApplicationStatus.APPROVED);
    }

    /**
     * 관리자용 신청 목록을 오래된 신청부터 한 페이지씩 반환한다.
     *
     * @param query 상태가 {@code null}이면 모든 상태를 조회한다
     */
    public Page<SellerApplicationAdminResult> getApplications(SellerApplicationSearchQuery query) {
        Pageable pageable = PageRequest.of(query.page(), query.size(), OLDEST_FIRST);
        Page<SellerApplication> applications = query.status() == null
                ? sellerApplicationRepository.findAll(pageable)
                : sellerApplicationRepository.findAllByStatus(SellerApplicationStatus.valueOf(query.status()), pageable);
        return applications.map(SellerApplicationAdminResult::from);
    }

    /**
     * 관리자용 신청 상세를 반환한다.
     *
     * @throws RestApiException 신청이 없으면 {@code SELLER_APPLICATION_NOT_FOUND}
     */
    public SellerApplicationAdminResult getApplication(Long applicationId) {
        return SellerApplicationAdminResult.from(findApplication(applicationId));
    }

    /**
     * 심사를 시작한다.
     *
     * @throws RestApiException 신청이 없으면 {@code SELLER_APPLICATION_NOT_FOUND},
     *                          심사 대기가 아니면 {@code INVALID_SELLER_APPLICATION_STATUS},
     *                          다른 관리자가 먼저 처리했으면 {@code SELLER_APPLICATION_CONCURRENT_MODIFICATION}
     */
    @Transactional
    public SellerApplicationAdminResult startReview(Long applicationId) {
        SellerApplication application = findApplication(applicationId);
        application.startReview(now());
        flushDetectingConflict();
        return SellerApplicationAdminResult.from(application);
    }

    /**
     * 신청을 승인하고 판매자 승인 이벤트를 발행한다. 이벤트를 받은 wallet 모듈이 판매자 지갑을 만든다.
     *
     * @param adminId 승인을 결정한 관리자. 신청에 심사자로 기록한다
     * @throws RestApiException 신청이 없으면 {@code SELLER_APPLICATION_NOT_FOUND},
     *                          심사 중이 아니면 {@code INVALID_SELLER_APPLICATION_STATUS},
     *                          다른 관리자가 먼저 처리했으면 {@code SELLER_APPLICATION_CONCURRENT_MODIFICATION}
     */
    @Transactional
    public SellerApplicationAdminResult approve(Long adminId, Long applicationId) {
        SellerApplication application = findApplication(applicationId);
        LocalDateTime approvedAt = now();
        application.approve(adminId, approvedAt);
        flushDetectingConflict();
        // 버전 검사를 통과한 승인만 이벤트를 낸다. 이벤트는 커밋 후에 전달되므로, 롤백되면 함께 사라진다
        sellerEventPublisher.publishSellerApproved(application.getMemberId(), approvedAt);
        return SellerApplicationAdminResult.from(application);
    }

    /**
     * 신청을 거절한다. 거절된 회원은 바로 다시 신청할 수 있다.
     *
     * @param adminId 거절을 결정한 관리자. 신청에 심사자로 기록한다
     * @throws RestApiException 신청이 없으면 {@code SELLER_APPLICATION_NOT_FOUND},
     *                          사유가 비었거나 너무 길면 {@code INVALID_REJECT_REASON},
     *                          심사 대기·심사 중이 아니면 {@code INVALID_SELLER_APPLICATION_STATUS},
     *                          다른 관리자가 먼저 처리했으면 {@code SELLER_APPLICATION_CONCURRENT_MODIFICATION}
     */
    @Transactional
    public SellerApplicationAdminResult reject(Long adminId, Long applicationId, SellerApplicationRejectCommand command) {
        SellerApplication application = findApplication(applicationId);
        application.reject(adminId, command.reason(), now());
        flushDetectingConflict();
        return SellerApplicationAdminResult.from(application);
    }

    /**
     * 현재 시각을 DB 정밀도(DATETIME(6), 마이크로초)로 잘라 반환한다.
     * 시계가 나노초까지 주면 응답과 이벤트에 담은 값이 나중에 DB에서 읽은 값과 달라지므로, 처음부터 저장될 값으로 맞춘다.
     */
    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    private SellerApplication findApplication(Long applicationId) {
        return sellerApplicationRepository.findById(applicationId)
                .orElseThrow(() -> new RestApiException(SellerErrorCode.SELLER_APPLICATION_NOT_FOUND));
    }

    /**
     * 변경을 바로 DB에 반영해 낙관적 락(@Version) 검사를 이 메서드 안에서 하게 한다.
     * 두 관리자가 같은 신청을 동시에 읽고 처리하면 둘 다 상태 검사를 통과하므로, 버전으로 나중 쪽을 막는다.
     * flush하지 않으면 검사가 서비스 메서드가 끝난 뒤 커밋 시점에 일어나 여기서 잡을 수 없다.
     */
    private void flushDetectingConflict() {
        try {
            sellerApplicationRepository.flush();
        } catch (OptimisticLockingFailureException e) {
            throw new RestApiException(SellerErrorCode.SELLER_APPLICATION_CONCURRENT_MODIFICATION);
        }
    }

    /**
     * 사전 확인 후 동시 신청이 겹치면 유니크 제약이 막는다. 그 위반을 사전 확인과 같은 에러코드로 바꾼다.
     * flush해야 제약 위반이 이 메서드 안에서 터져 변환할 수 있다.
     */
    private SellerApplication save(SellerApplication application) {
        try {
            return sellerApplicationRepository.saveAndFlush(application);
        } catch (DataIntegrityViolationException e) {
            throw duplicateValidator.toDuplicateException(e);
        }
    }

}
