package com.ok.member.seller.domain;

import com.ok.common.exception.RestApiException;
import com.ok.common.jpa.entity.BaseTime;
import com.ok.member.seller.exception.SellerErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.regex.Pattern;

import static jakarta.persistence.GenerationType.IDENTITY;

/**
 * 승인된 판매자의 상점 프로필. 회원당 하나(한 명당 하나의 상점)
 * 상점·상품 상세 화면에 보이는 정보라 신청 정보와 따로 두고, 판매자가 직접 고친다.
 */
@Entity
@Table(name = "seller_profile")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SellerProfile extends BaseTime {

    // 컬럼 길이와 생성·수정 시 검증이 같은 값을 쓰도록 상수로 둔다. 바꾸면 마이그레이션도 함께 바꾼다
    private static final int NAME_MAX_LENGTH = 50;
    private static final int INTRODUCTION_MAX_LENGTH = 200;
    private static final int PROFILE_IMAGE_KEY_MAX_LENGTH = 255;

    // 지역번호·휴대폰(054-873-2210, 010-1234-5678)과 대표번호(1588-0000). 요청 DTO와 같은 규칙이다
    private static final Pattern CUSTOMER_SERVICE_PHONE =
            Pattern.compile("0\\d{1,2}-\\d{3,4}-\\d{4}|1\\d{3}-\\d{4}");

    @Id
    @GeneratedValue(strategy = IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false, updatable = false)
    private Long memberId;

    @Column(name = "store_name", nullable = false, length = NAME_MAX_LENGTH)
    private String storeName;

    @Column(name = "representative_name", nullable = false, length = NAME_MAX_LENGTH)
    private String representativeName;

    @Column(name = "customer_service_phone", nullable = false, length = 20)
    private String customerServicePhone;

    @Column(name = "introduction", length = INTRODUCTION_MAX_LENGTH)
    private String introduction;

    // URL이 아니라 object key를 저장해, 버킷 주소나 CDN이 바뀌어도 데이터를 고치지 않게 한다 (상품 이미지와 같은 방식)
    @Column(name = "profile_image_key", length = PROFILE_IMAGE_KEY_MAX_LENGTH)
    private String profileImageKey;

    private SellerProfile(Long memberId, String storeName, String representativeName,
                          String customerServicePhone, String introduction, String profileImageKey) {
        if (memberId == null) {
            throw new IllegalArgumentException("memberId는 필수입니다.");
        }
        this.memberId = memberId;
        change(storeName, representativeName, customerServicePhone, introduction, profileImageKey);
    }

    /**
     * 판매자 프로필을 만든다. 판매자 승인 여부는 호출하는 쪽(서비스)이 확인한다.
     *
     * <p>요청 DTO가 먼저 같은 규칙으로 422를 낸다. 이 검증은 DTO를 거치지 않는 경로를 위한 마지막 방어선이다.
     *
     * @param introduction    상점 소개. 없으면 {@code null}
     * @param profileImageKey 프로필 사진 object key. 없으면 {@code null}
     * @throws RestApiException 상호명·대표자명이 비었거나 너무 길거나 상점 소개가 너무 길면 {@code INVALID_SELLER_PROFILE_FIELD},
     *                          고객센터 연락처 형식이 틀리면 {@code INVALID_CUSTOMER_SERVICE_PHONE}
     */
    public static SellerProfile register(Long memberId, String storeName, String representativeName,
                                         String customerServicePhone, String introduction, String profileImageKey) {
        return new SellerProfile(memberId, storeName, representativeName,
                customerServicePhone, introduction, profileImageKey);
    }

    /**
     * 프로필 전체를 새 값으로 바꾼다. 화면이 한 번에 저장하므로 부분 수정 없이 모든 값을 받는다.
     * 검증 규칙은 {@link #register}와 같다.
     *
     * @param profileImageKey 프로필 사진 object key. {@code null}이면 사진을 지운다
     */
    public void update(String storeName, String representativeName,
                       String customerServicePhone, String introduction, String profileImageKey) {
        change(storeName, representativeName, customerServicePhone, introduction, profileImageKey);
    }

    // 등록과 수정이 같은 검증을 거치도록 값 변경을 한곳에 모은다.
    // 모든 값을 먼저 검증한 뒤 대입한다.
    private void change(String storeName, String representativeName,
                        String customerServicePhone, String introduction, String profileImageKey) {
        String validStoreName = requireText(storeName, NAME_MAX_LENGTH,
                "상호명은 필수입니다.", "상호명은 50자 이하여야 합니다.");
        String validRepresentativeName = requireText(representativeName, NAME_MAX_LENGTH,
                "대표자명은 필수입니다.", "대표자명은 50자 이하여야 합니다.");
        String validPhone = requireCustomerServicePhone(customerServicePhone);
        String validIntroduction = requireIntroductionLength(introduction);
        String validImageKey = requireImageKeyLength(profileImageKey);

        this.storeName = validStoreName;
        this.representativeName = validRepresentativeName;
        this.customerServicePhone = validPhone;
        this.introduction = validIntroduction;
        this.profileImageKey = validImageKey;
    }

    /**
     * 앞뒤 공백을 지운 값으로 검증하고 그 값을 저장한다. 요청 DTO를 거치지 않는 경로에서도 DTO와 같은 값이 저장되게 한다
     * 필수 항목마다 코드를 나누지 않고, 어느 항목이 왜 틀렸는지는 {@code detail}로 알려 준다
     */
    private static String requireText(String value, int maxLength, String missingMessage, String tooLongMessage) {
        String stripped = value == null ? null : value.strip();
        if (stripped == null || stripped.isEmpty()) {
            throw new RestApiException(SellerErrorCode.INVALID_SELLER_PROFILE_FIELD, missingMessage);
        }
        if (stripped.length() > maxLength) {
            throw new RestApiException(SellerErrorCode.INVALID_SELLER_PROFILE_FIELD, tooLongMessage);
        }
        return stripped;
    }

    private static String requireCustomerServicePhone(String phone) {
        String stripped = phone == null ? null : phone.strip();
        if (stripped == null || !CUSTOMER_SERVICE_PHONE.matcher(stripped).matches()) {
            throw new RestApiException(SellerErrorCode.INVALID_CUSTOMER_SERVICE_PHONE);
        }
        return stripped;
    }

    // 상점 소개는 선택 값이라 없어도 되고, 길이만 확인. 공백만 있으면 없는 값으로 둔다
    private static String requireIntroductionLength(String introduction) {
        String stripped = introduction == null ? null : introduction.strip();
        if (stripped == null || stripped.isEmpty()) {
            return null;
        }
        if (stripped.length() > INTRODUCTION_MAX_LENGTH) {
            throw new RestApiException(SellerErrorCode.INVALID_SELLER_PROFILE_FIELD, "상점 소개는 200자 이하여야 합니다.");
        }
        return stripped;
    }

    // 사진 key는 서비스가 업로드 URL에서 뽑아 넘기는 값이라, 길이가 넘치면 사용자 입력 오류가 아니라 시스템 오류
    private static String requireImageKeyLength(String profileImageKey) {
        if (profileImageKey != null && profileImageKey.length() > PROFILE_IMAGE_KEY_MAX_LENGTH) {
            throw new IllegalArgumentException("profileImageKey는 255자 이하여야 합니다.");
        }
        return profileImageKey;
    }

}
