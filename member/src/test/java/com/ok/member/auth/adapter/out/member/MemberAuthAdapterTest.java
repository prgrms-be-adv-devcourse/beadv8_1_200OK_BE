package com.ok.member.auth.adapter.out.member;

import com.ok.member.auth.application.dto.AuthAccount;
import com.ok.member.auth.application.dto.LoginCredential;
import com.ok.member.member.application.dto.MemberAuthAccount;
import com.ok.member.member.application.dto.MemberLoginCredential;
import com.ok.member.member.application.port.in.MemberAuthAccountUseCase;
import com.ok.member.seller.application.port.in.SellerApprovalCheckUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class MemberAuthAdapterTest {

    private static final Long MEMBER_ID = 1L;

    @Mock
    MemberAuthAccountUseCase memberAuthAccountUseCase;

    @Mock
    SellerApprovalCheckUseCase sellerApprovalCheckUseCase;

    private MemberAuthAdapter memberAuthAdapter;

    @BeforeEach
    void setUp() {
        memberAuthAdapter = new MemberAuthAdapter(memberAuthAccountUseCase, sellerApprovalCheckUseCase);
    }

    @Test
    void 로그인_조회_결과를_인증의_자격_증명으로_옮긴다() {
        // given
        given(memberAuthAccountUseCase.findLoginCredential("buyer01"))
                .willReturn(Optional.of(new MemberLoginCredential(MEMBER_ID, "{bcrypt}hash")));

        // when
        Optional<LoginCredential> sut = memberAuthAdapter.findLoginCredential("buyer01");

        // then
        assertThat(sut).contains(new LoginCredential(MEMBER_ID, "{bcrypt}hash"));
    }

    // boolean 세 개를 위치로 옮기므로, 하나만 참인 경우를 각각 넣어 순서가 뒤바뀌면 실패하게 한다
    @ParameterizedTest
    @MethodSource("하나만_참인_계정_상태")
    void 계정_상태를_필드마다_그대로_옮긴다(boolean admin, boolean withdrawn, boolean suspended) {
        // given
        given(memberAuthAccountUseCase.findAuthAccount(MEMBER_ID))
                .willReturn(Optional.of(new MemberAuthAccount(MEMBER_ID, admin, withdrawn, suspended, "{bcrypt}hash")));

        // when
        AuthAccount sut = memberAuthAdapter.findAuthAccount(MEMBER_ID).orElseThrow();

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.memberId()).isEqualTo(MEMBER_ID);
            softly.assertThat(sut.admin()).isEqualTo(admin);
            softly.assertThat(sut.withdrawn()).isEqualTo(withdrawn);
            softly.assertThat(sut.suspended()).isEqualTo(suspended);
            softly.assertThat(sut.passwordHash()).isEqualTo("{bcrypt}hash");
        });
    }

    static Stream<Arguments> 하나만_참인_계정_상태() {
        return Stream.of(
                Arguments.of(true, false, false),
                Arguments.of(false, true, false),
                Arguments.of(false, false, true));
    }

    @Test
    void 회원이_없으면_빈_값을_돌려준다() {
        // given
        given(memberAuthAccountUseCase.findAuthAccount(MEMBER_ID)).willReturn(Optional.empty());

        // when
        Optional<AuthAccount> sut = memberAuthAdapter.findAuthAccount(MEMBER_ID);

        // then
        assertThat(sut).isEmpty();
    }

}
