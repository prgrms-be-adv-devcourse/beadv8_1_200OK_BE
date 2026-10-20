package com.ok.member.seller.domain;

import com.ok.common.exception.RestApiException;
import com.ok.member.seller.exception.SellerErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class SellerProfileTest {

    private static final String IMAGE_KEY = "seller_profile/3f2a9c1e-7b4d-4e8a-9f0c-1d2e3f4a5b6c.jpg";

    @ParameterizedTest
    @MethodSource("잘못된_프로필_항목")
    void 상호명_대표자명이_비었거나_너무_길거나_상점_소개가_너무_길면_프로필을_만들지_않는다(
            String storeName, String representativeName, String introduction, String expectedDetail) {
        // when & then
        assertThatThrownBy(() -> SellerProfile.register(
                1L, storeName, representativeName, "054-873-2210", introduction, null))
                .isInstanceOfSatisfying(RestApiException.class, e -> assertSoftly(softly -> {
                    softly.assertThat(e.getErrorCode()).isEqualTo(SellerErrorCode.INVALID_SELLER_PROFILE_FIELD);
                    softly.assertThat(e.getMessage()).isEqualTo(expectedDetail);
                }));
    }

    static Stream<Arguments> 잘못된_프로필_항목() {
        String tooLongName = "가".repeat(51);
        return Stream.of(
                Arguments.of(" ", "김영수", null, "상호명은 필수입니다."),
                Arguments.of(tooLongName, "김영수", null, "상호명은 50자 이하여야 합니다."),
                Arguments.of("청송 햇살농원", null, null, "대표자명은 필수입니다."),
                Arguments.of("청송 햇살농원", tooLongName, null, "대표자명은 50자 이하여야 합니다."),
                Arguments.of("청송 햇살농원", "김영수", "가".repeat(201), "상점 소개는 200자 이하여야 합니다.")
        );
    }

    @ParameterizedTest
    @MethodSource("잘못된_고객센터_연락처")
    void 고객센터_연락처_형식이_틀리면_프로필을_만들지_않는다(String phone) {
        // when & then
        assertThatThrownBy(() -> SellerProfile.register(1L, "청송 햇살농원", "김영수", phone, null, null))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(SellerErrorCode.INVALID_CUSTOMER_SERVICE_PHONE));
    }

    static Stream<String> 잘못된_고객센터_연락처() {
        return Stream.of("0548732210", "054-873-221", "고객센터", null);
    }

    @Test
    void 상점_소개와_사진은_없어도_프로필을_만든다() {
        // when
        SellerProfile sut = SellerProfile.register(1L, "청송 햇살농원", "김영수", "1588-0000", null, null);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getIntroduction()).isNull();
            softly.assertThat(sut.getProfileImageKey()).isNull();
        });
    }

    @Test
    void 입력값의_앞뒤_공백을_지우고_공백뿐인_상점_소개는_없는_값으로_둔다() {
        // when: 요청 DTO를 거치지 않아 공백이 그대로 들어왔다
        SellerProfile sut = SellerProfile.register(1L, " 청송 햇살농원 ", " 김영수 ", " 054-873-2210 ", "   ", null);

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut.getStoreName()).isEqualTo("청송 햇살농원");
            softly.assertThat(sut.getRepresentativeName()).isEqualTo("김영수");
            softly.assertThat(sut.getCustomerServicePhone()).isEqualTo("054-873-2210");
            softly.assertThat(sut.getIntroduction()).isNull();
        });
    }

    @Test
    void 수정할_값_중_하나라도_틀리면_어떤_값도_바꾸지_않는다() {
        // given
        SellerProfile profile = SellerProfile.register(
                1L, "청송 햇살농원", "김영수", "054-873-2210", "소개", IMAGE_KEY);

        // when: 앞쪽 값(상호명)은 맞고 뒤쪽 값(연락처)이 틀렸다
        assertThatThrownBy(() -> profile.update("청송 사과농원", "이영희", "고객센터", "새 소개", null))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(SellerErrorCode.INVALID_CUSTOMER_SERVICE_PHONE));

        // then: 앞쪽 값도 그대로다
        assertSoftly(softly -> {
            softly.assertThat(profile.getStoreName()).isEqualTo("청송 햇살농원");
            softly.assertThat(profile.getRepresentativeName()).isEqualTo("김영수");
            softly.assertThat(profile.getIntroduction()).isEqualTo("소개");
            softly.assertThat(profile.getProfileImageKey()).isEqualTo(IMAGE_KEY);
        });
    }

}
