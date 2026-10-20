package com.ok.member.seller;

import com.ok.member.seller.controller.dto.SellerProfileRequest;
import com.ok.member.seller.domain.SellerProfile;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

// 요청 DTO와 엔티티는 같은 검증 규칙을 따로 갖는다. 컨트롤러 계층은 도메인 계층을 참조할 수 없어(ArchitectureTest)
// 상수를 한 곳에 두지 못하므로, 같은 값을 둘 다에 넣어 판정이 같은지로 규칙이 어긋나지 않게 지킨다.
// 한쪽 규칙만 바꾸면 이 테스트가 실패한다.
class SellerProfileValidationRuleTest {

    private static final String PHONE = "054-873-2210";

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @ParameterizedTest
    @ValueSource(strings = {
            "054-873-2210", "02-123-4567", "010-1234-5678", "1588-0000",           // 허용
            "0548732210", "054-873-221", "02-12-4567", "1588-000", "고객센터"       // 거절
    })
    void 고객센터_연락처는_요청과_엔티티가_같은_값을_같게_판정한다(String phone) {
        // when
        boolean requestRejects = requestRejects(new SellerProfileRequest("청송 햇살농원", "김영수", phone, null, null),
                "customerServicePhone");
        boolean entityRejects = entityRejects("청송 햇살농원", "김영수", phone, null);

        // then
        assertThat(requestRejects).isEqualTo(entityRejects);
    }

    @ParameterizedTest
    @MethodSource("길이_경계값")
    void 상호명_대표자명_상점_소개의_길이_제한은_요청과_엔티티가_같다(
            String field, String storeName, String representativeName, String introduction) {
        // when
        boolean requestRejects = requestRejects(
                new SellerProfileRequest(storeName, representativeName, PHONE, introduction, null), field);
        boolean entityRejects = entityRejects(storeName, representativeName, PHONE, introduction);

        // then
        assertThat(requestRejects).isEqualTo(entityRejects);
    }

    static Stream<Arguments> 길이_경계값() {
        return Stream.of(
                Arguments.of("storeName", "가".repeat(50), "김영수", null),
                Arguments.of("storeName", "가".repeat(51), "김영수", null),
                Arguments.of("representativeName", "청송 햇살농원", "가".repeat(50), null),
                Arguments.of("representativeName", "청송 햇살농원", "가".repeat(51), null),
                Arguments.of("introduction", "청송 햇살농원", "김영수", "가".repeat(200)),
                Arguments.of("introduction", "청송 햇살농원", "김영수", "가".repeat(201))
        );
    }

    private boolean requestRejects(SellerProfileRequest request, String field) {
        return validator.validate(request).stream()
                .anyMatch(violation -> violation.getPropertyPath().toString().equals(field));
    }

    private static boolean entityRejects(String storeName, String representativeName, String phone,
                                         String introduction) {
        return catchThrowable(() -> SellerProfile.register(1L, storeName, representativeName, phone,
                introduction, null)) != null;
    }

}
