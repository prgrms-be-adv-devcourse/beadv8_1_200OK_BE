package com.ok.member.seller.application.service;

import com.ok.common.exception.RestApiException;
import com.ok.member.seller.application.dto.SellerApplicationAdminResult;
import com.ok.member.seller.application.dto.SellerApplicationRejectCommand;
import com.ok.member.seller.application.dto.SellerApplicationResult;
import com.ok.member.seller.application.dto.SellerApplicationSearchQuery;
import com.ok.member.seller.application.dto.SellerApplyCommand;
import com.ok.member.seller.application.port.in.SellerApplicationUseCase;
import com.ok.member.seller.application.port.in.SellerApprovalCheckUseCase;
import com.ok.member.seller.application.port.out.SellerApplicationRepository;
import com.ok.member.seller.application.port.out.SellerEventPublisher;
import com.ok.member.seller.domain.SellerApplication;
import com.ok.member.seller.domain.SellerApplicationStatus;
import com.ok.member.seller.exception.SellerErrorCode;
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
public class SellerApplicationService implements SellerApplicationUseCase, SellerApprovalCheckUseCase {

    // 먼저 신청한 순서대로 심사하도록 오래된 신청부터 보여 준다. 같은 일시면 id로 순서를 고정해 페이지 사이에서 흔들리지 않게 한다
    private static final Sort OLDEST_FIRST = Sort.by(Sort.Order.asc("appliedAt"), Sort.Order.asc("id"));

    private final SellerApplicationRepository sellerApplicationRepository;
    private final SellerApplicationDuplicateValidator duplicateValidator;
    private final SellerEventPublisher sellerEventPublisher;
    private final Clock clock;

    @Override
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

    @Override
    public List<SellerApplicationResult> getMyApplications(Long memberId) {
        return sellerApplicationRepository.findAllByMemberIdOrderByAppliedAtDescIdDesc(memberId).stream()
                .map(SellerApplicationResult::from)
                .toList();
    }

    @Override
    public SellerApplicationResult getMyApplication(Long memberId, Long applicationId) {
        return sellerApplicationRepository.findByIdAndMemberId(applicationId, memberId)
                .map(SellerApplicationResult::from)
                .orElseThrow(() -> new RestApiException(SellerErrorCode.SELLER_APPLICATION_NOT_FOUND));
    }

    @Override
    public boolean isApprovedSeller(Long memberId) {
        return sellerApplicationRepository.existsByMemberIdAndStatus(memberId, SellerApplicationStatus.APPROVED);
    }

    @Override
    public Page<SellerApplicationAdminResult> getApplications(SellerApplicationSearchQuery query) {
        Pageable pageable = PageRequest.of(query.page(), query.size(), OLDEST_FIRST);
        Page<SellerApplication> applications = query.status() == null
                ? sellerApplicationRepository.findAll(pageable)
                : sellerApplicationRepository.findAllByStatus(SellerApplicationStatus.valueOf(query.status()), pageable);
        return applications.map(SellerApplicationAdminResult::from);
    }

    @Override
    public SellerApplicationAdminResult getApplication(Long applicationId) {
        return SellerApplicationAdminResult.from(findApplication(applicationId));
    }

    @Override
    @Transactional
    public SellerApplicationAdminResult startReview(Long applicationId) {
        SellerApplication application = findApplication(applicationId);
        application.startReview(now());
        flushDetectingConflict();
        return SellerApplicationAdminResult.from(application);
    }

    @Override
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

    @Override
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
