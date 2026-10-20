package com.ok.payout.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// 정산 권한 판단에 쓰는 역할 해석 규칙을 확인한다
// 모르는 값은 모두 권한 없는 OTHER로 해석되어야 함 (잘못된 값이 권한을 얻지 않도록)
class PayoutRequesterRoleTest {

    // ===== fromTokenRoles(목록): 토큰의 권한 목록을 해석 =====
    // 토큰에는 역할이 여러 개 들어 있을 수 있어, 권한이 큰 순서(ADMIN → SELLER)로 하나를 고름

    @Test
    void 토큰_권한에_ADMIN이_있으면_ADMIN으로_해석한다() {
        // given
        List<String> roles = List.of("ADMIN");

        // when
        PayoutRequesterRole sut = PayoutRequesterRole.fromTokenRoles(roles);

        // then
        assertThat(sut).isEqualTo(PayoutRequesterRole.ADMIN);
    }

    @Test
    void 판매자_토큰_권한_BUYER와_SELLER는_SELLER로_해석한다() {
        // given
        List<String> roles = List.of("BUYER", "SELLER");

        // when
        PayoutRequesterRole sut = PayoutRequesterRole.fromTokenRoles(roles);

        // then
        assertThat(sut).isEqualTo(PayoutRequesterRole.SELLER);
    }

    @Test
    void 토큰_권한에_SELLER와_ADMIN이_함께_있으면_권한이_큰_ADMIN으로_해석한다() {
        // given
        // 실제 토큰에는 없는 조합. 우선순위(ADMIN → SELLER)를 확인하려고 일부러 SELLER를 앞에 둠
        List<String> roles = List.of("SELLER", "ADMIN");

        // when
        PayoutRequesterRole sut = PayoutRequesterRole.fromTokenRoles(roles);

        // then
        assertThat(sut).isEqualTo(PayoutRequesterRole.ADMIN);
    }

    @Test
    void 토큰_권한이_BUYER뿐이면_정산_권한이_없어_OTHER로_해석한다() {
        // given
        List<String> roles = List.of("BUYER");

        // when
        PayoutRequesterRole sut = PayoutRequesterRole.fromTokenRoles(roles);

        // then
        assertThat(sut).isEqualTo(PayoutRequesterRole.OTHER);
    }

    @Test
    void 토큰_권한은_대소문자를_구분해_소문자_admin은_OTHER로_해석한다() {
        // given
        List<String> roles = List.of("admin");

        // when
        PayoutRequesterRole sut = PayoutRequesterRole.fromTokenRoles(roles);

        // then
        assertThat(sut).isEqualTo(PayoutRequesterRole.OTHER);
    }

    @Test
    void 토큰_권한_목록이_비어_있으면_OTHER로_해석한다() {
        // given
        List<String> roles = List.of();

        // when
        PayoutRequesterRole sut = PayoutRequesterRole.fromTokenRoles(roles);

        // then
        assertThat(sut).isEqualTo(PayoutRequesterRole.OTHER);
    }

    @Test
    void 토큰_권한_목록이_null이면_OTHER로_해석한다() {
        // given
        List<String> roles = null;

        // when
        PayoutRequesterRole sut = PayoutRequesterRole.fromTokenRoles(roles);

        // then
        assertThat(sut).isEqualTo(PayoutRequesterRole.OTHER);
    }
}
