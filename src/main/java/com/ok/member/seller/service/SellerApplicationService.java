package com.ok.member.seller.service;

import com.ok.common.exception.RestApiException;
import com.ok.member.seller.domain.SellerApplication;
import com.ok.member.seller.exception.SellerErrorCode;
import com.ok.member.seller.repository.SellerApplicationRepository;
import com.ok.member.seller.service.dto.SellerApplicationResult;
import com.ok.member.seller.service.dto.SellerApplyCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 회원의 판매자 신청과 신청 조회를 처리한다. 승인·거절은 관리자 API가 맡는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SellerApplicationService {

    private final SellerApplicationRepository sellerApplicationRepository;
    private final SellerApplicationDuplicateValidator duplicateValidator;
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
                LocalDateTime.now(clock)
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
