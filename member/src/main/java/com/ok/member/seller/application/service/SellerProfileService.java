package com.ok.member.seller.application.service;

import com.ok.common.exception.RestApiException;
import com.ok.common.media.MediaUrlResolver;
import com.ok.member.seller.application.dto.SellerProfileCommand;
import com.ok.member.seller.application.dto.SellerProfileResult;
import com.ok.member.seller.application.port.in.SellerProfileUseCase;
import com.ok.member.seller.application.port.out.SellerEventPublisher;
import com.ok.member.seller.application.port.out.SellerProfileRepository;
import com.ok.member.seller.domain.SellerProfile;
import com.ok.member.seller.exception.SellerErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 판매자의 상점 프로필 조회·등록·수정을 처리한다.
 * 판매자 여부는 컨트롤러가 토큰의 SELLER 권한으로 확인한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SellerProfileService implements SellerProfileUseCase {

    private final SellerProfileRepository sellerProfileRepository;
    private final SellerProfileDuplicateValidator duplicateValidator;
    private final SellerProfileImageUrlValidator imageUrlValidator;
    private final MediaUrlResolver mediaUrlResolver;
    private final SellerEventPublisher sellerEventPublisher;
    // 출고지 변경 시각 기준 (전역 ClockConfig)
    private final Clock clock;

    @Override
    public SellerProfileResult getMyProfile(Long memberId) {
        return toResult(findProfile(memberId));
    }

    @Override
    @Transactional
    public SellerProfileResult register(Long memberId, SellerProfileCommand command) {
        duplicateValidator.validate(memberId);

        SellerProfile profile = SellerProfile.register(
                memberId,
                command.storeName(),
                command.representativeName(),
                command.customerServicePhone(),
                command.originPostalCode(),
                command.introduction(),
                imageUrlValidator.toObjectKey(command.profileImageUrl())
        );
        SellerProfile saved = save(profile);
        // 등록은 출고지가 처음 생기는 때라 항상 알린다
        publishOriginPostalCodeChanged(saved);
        return toResult(saved);
    }

    @Override
    @Transactional
    public SellerProfileResult update(Long memberId, SellerProfileCommand command) {
        SellerProfile profile = findProfile(memberId);

        boolean originPostalCodeChanged = profile.update(
                command.storeName(),
                command.representativeName(),
                command.customerServicePhone(),
                command.originPostalCode(),
                command.introduction(),
                imageUrlValidator.toObjectKey(command.profileImageUrl())
        );
        // 상호명·소개만 고친 수정은 delivery가 알 필요가 없어 보내지 않는다. 메시지 수를 줄이려는 것이고,
        // delivery는 발생 시각 기준으로 덮어써서 같은 값을 다시 받아도 결과는 같다
        if (originPostalCodeChanged) {
            publishOriginPostalCodeChanged(profile);
        }
        return toResult(profile);
    }

    // 시각을 마이크로초로 자른다. DB(DATETIME(6))에 남는 값과 메시지의 값이 같아야, delivery가 저장한 시각과 비교할 때
    // 나노초 차이로 같은 변경을 "더 새로운 것"으로 잘못 보지 않는다
    private void publishOriginPostalCodeChanged(SellerProfile profile) {
        sellerEventPublisher.publishOriginPostalCodeChanged(profile.getMemberId(), profile.getOriginPostalCode(),
                LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS));
    }

    private SellerProfile findProfile(Long memberId) {
        return sellerProfileRepository.findByMemberId(memberId)
                .orElseThrow(() -> new RestApiException(SellerErrorCode.SELLER_PROFILE_NOT_FOUND));
    }

    /**
     * 등록 버튼 중복 클릭처럼 사전 확인을 함께 통과한 요청은 유니크 제약이 막는다. 그 위반을 중복 등록 오류로 바꾼다.
     */
    private SellerProfile save(SellerProfile profile) {
        try {
            return sellerProfileRepository.saveAndFlush(profile);
        } catch (DataIntegrityViolationException e) {
            throw duplicateValidator.toDuplicateException(e);
        }
    }

    private SellerProfileResult toResult(SellerProfile profile) {
        String imageKey = profile.getProfileImageKey();
        return new SellerProfileResult(
                profile.getStoreName(),
                profile.getRepresentativeName(),
                profile.getCustomerServicePhone(),
                profile.getOriginPostalCode(),
                profile.getIntroduction(),
                imageKey == null ? null : mediaUrlResolver.toUrl(imageKey)
        );
    }

}
