package com.ok.member.seller.service;

import com.ok.common.exception.RestApiException;
import com.ok.common.media.MediaUrlResolver;
import com.ok.member.seller.domain.SellerProfile;
import com.ok.member.seller.exception.SellerErrorCode;
import com.ok.member.seller.repository.SellerProfileRepository;
import com.ok.member.seller.service.dto.SellerProfileCommand;
import com.ok.member.seller.service.dto.SellerProfileResult;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 판매자의 상점 프로필 조회·등록·수정을 처리한다.
 * 판매자 여부는 컨트롤러가 토큰의 SELLER 권한으로 확인한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SellerProfileService {

    private final SellerProfileRepository sellerProfileRepository;
    private final SellerProfileDuplicateValidator duplicateValidator;
    private final SellerProfileImageUrlValidator imageUrlValidator;
    private final MediaUrlResolver mediaUrlResolver;

    /**
     * 판매자 본인의 프로필을 반환한다.
     *
     * @throws RestApiException 프로필을 등록하지 않았으면 {@code SELLER_PROFILE_NOT_FOUND}
     */
    public SellerProfileResult getMyProfile(Long memberId) {
        return toResult(findProfile(memberId));
    }

    /**
     * 판매자 프로필을 등록한다. 한 회원은 프로필(상점)을 하나만 가진다.
     *
     * @throws RestApiException 이미 등록했으면 {@code SELLER_PROFILE_ALREADY_EXISTS},
     *                          사진 주소가 판매자 프로필용이 아니면 {@code INVALID_PROFILE_IMAGE_URL}
     */
    @Transactional
    public SellerProfileResult register(Long memberId, SellerProfileCommand command) {
        duplicateValidator.validate(memberId);

        SellerProfile profile = SellerProfile.register(
                memberId,
                command.storeName(),
                command.representativeName(),
                command.customerServicePhone(),
                command.introduction(),
                imageUrlValidator.toObjectKey(command.profileImageUrl())
        );
        return toResult(save(profile));
    }

    /**
     * 판매자 프로필 전체를 새 값으로 바꾼다. 사진 URL이 없으면 사진을 지운다.
     *
     * @throws RestApiException 프로필을 등록하지 않았으면 {@code SELLER_PROFILE_NOT_FOUND},
     *                          사진 주소가 판매자 프로필용이 아니면 {@code INVALID_PROFILE_IMAGE_URL}
     */
    @Transactional
    public SellerProfileResult update(Long memberId, SellerProfileCommand command) {
        SellerProfile profile = findProfile(memberId);

        profile.update(
                command.storeName(),
                command.representativeName(),
                command.customerServicePhone(),
                command.introduction(),
                imageUrlValidator.toObjectKey(command.profileImageUrl())
        );
        return toResult(profile);
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
                profile.getIntroduction(),
                imageKey == null ? null : mediaUrlResolver.toUrl(imageKey)
        );
    }

}
