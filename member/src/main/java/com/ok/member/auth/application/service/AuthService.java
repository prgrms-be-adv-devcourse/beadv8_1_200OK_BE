package com.ok.member.auth.application.service;

import com.ok.common.exception.RestApiException;
import com.ok.member.auth.application.dto.AuthAccount;
import com.ok.member.auth.application.dto.LoginCredential;
import com.ok.member.auth.application.dto.TokenReissueCommand;
import com.ok.member.auth.application.dto.TokenResult;
import com.ok.member.auth.application.port.in.TokenReissueUseCase;
import com.ok.member.auth.application.port.out.AccessTokenIssuer;
import com.ok.member.auth.application.port.out.MemberCredentialReader;
import com.ok.member.auth.application.port.out.SellerApprovalReader;
import com.ok.member.auth.domain.AccessToken;
import com.ok.member.auth.domain.IssuedRefreshToken;
import com.ok.member.auth.domain.RotatedRefreshToken;
import com.ok.member.auth.exception.AuthErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 로그인과 재발급에 필요한 회원 조회와 토큰 발급을 트랜잭션 안에서 처리한다.
 * 비밀번호 비교처럼 커넥션을 잡으면 안 되는 로그인 흐름은 트랜잭션 밖의 {@link LoginFacade}가 맡는다.
 */
@Service
@Transactional(readOnly = true)
public class AuthService implements TokenReissueUseCase {

    private final MemberCredentialReader memberCredentialReader;
    private final AccessTokenIssuer accessTokenIssuer;
    private final RefreshTokenService refreshTokenService;
    private final SellerApprovalReader sellerApprovalReader;

    public AuthService(MemberCredentialReader memberCredentialReader,
                       AccessTokenIssuer accessTokenIssuer,
                       RefreshTokenService refreshTokenService,
                       SellerApprovalReader sellerApprovalReader) {
        this.memberCredentialReader = memberCredentialReader;
        this.accessTokenIssuer = accessTokenIssuer;
        this.refreshTokenService = refreshTokenService;
        this.sellerApprovalReader = sellerApprovalReader;
    }

    /**
     * 로그인 아이디로 비밀번호 확인에 필요한 값을 조회한다.
     * 탈퇴 회원도 그대로 돌려준다. 탈퇴 여부는 비밀번호를 확인한 뒤 {@link #issueLoginTokens}에서 판단해,
     * 틀린 비밀번호에는 회원 상태와 관계없이 같은 응답을 준다.
     * {@link #issueLoginTokens}와 이어 부르면 비밀번호 없이 토큰을 받을 수 있어,
     * 같은 패키지의 {@link LoginFacade}만 부를 수 있게 한다.
     */
    Optional<LoginCredential> findLoginCredential(String loginId) {
        return memberCredentialReader.findLoginCredential(loginId);
    }

    /**
     * 비밀번호를 확인한 회원에게 Access Token과 Refresh Token을 발급한다.
     * bcrypt 비교는 트랜잭션 밖에서 해야 해서 이 메서드는 비밀번호를 비교하지 않는다. 비밀번호 확인 없이 부르면
     * 토큰이 나가므로, 확인을 마친 {@link LoginFacade}만 부를 수 있다.
     * 회원을 이 트랜잭션 안에서 다시 읽어 탈퇴·정지를 확인하고, 해시가 확인에 쓴 값과 같은지 본다. 해시 비교는
     * 권한 확인이 아니라 비밀번호 확인 뒤 비밀번호가 바뀐 경우만 잡는다. 판매자 승인 조회와 Refresh Token 저장도
     * 같은 트랜잭션에서 처리한다.
     *
     * @param credential 비밀번호 확인에 쓴 값. 해시가 지금 저장된 값과 다르면 발급하지 않는다
     * @throws RestApiException 회원이 없거나 탈퇴했거나 비밀번호가 그사이 바뀌었으면 {@code INVALID_CREDENTIALS},
     *                          정지된 회원이면 {@code MEMBER_SUSPENDED}
     */
    @Transactional
    TokenResult issueLoginTokens(LoginCredential credential) {
        // 탈퇴 회원은 아이디가 익명화될 예정이라 "없는 회원"과 같게 취급해 가입 이력을 드러내지 않는다.
        // 확인한 해시와 지금 해시가 다르면 bcrypt 비교 중에 비밀번호가 바뀐 것이므로 옛 비밀번호로는 발급하지 않는다.
        // 정지 회원은 비밀번호까지 맞은 본인이므로, 왜 로그인이 안 되는지 알 수 있게 별도 코드로 알린다.
        AuthAccount account = memberCredentialReader.findAuthAccount(credential.memberId())
                .filter(found -> !found.withdrawn())
                .filter(found -> Objects.equals(found.passwordHash(), credential.passwordHash()))
                .orElseThrow(() -> new RestApiException(AuthErrorCode.INVALID_CREDENTIALS));
        if (account.suspended()) {
            throw new RestApiException(AuthErrorCode.MEMBER_SUSPENDED);
        }
        return createTokens(account, refreshTokenService.issue(account.memberId()));
    }

    /**
     * Refresh Token으로 새 Access Token과 Refresh Token을 발급한다.
     *
     * <p>쓴 Refresh Token은 같은 세션 행에서 새 토큰으로 바꾼다(rotation). 한 토큰은 한 번만 쓰이므로, 같은 토큰을 가진
     * 정상 사용자와 탈취자 중 먼저 쓴 쪽만 재발급에 성공한다.
     * 쓴 토큰은 행에 남지 않아 처음부터 없던 토큰과 같은 응답으로 거절만 하고
     * 그 세션을 폐기하지는 않는다. 그래서 탈취자가 먼저 재발급하면 탈취자 쪽 토큰이 계속 유효하다. 재사용 탐지와 폐기는 후속 과제다.
     *
     * <p>회원 정보를 다시 읽어 최신 상태와 권한으로 발급한다. 판매자 승인처럼 권한이 바뀌어도 재로그인 없이 반영된다.
     * 토큰 회전과 회원 확인은 한 트랜잭션에서 처리해, 중간에 실패하면 회전이 취소되어 기존 토큰이 그대로 남는다.
     *
     * <p>이 트랜잭션은 사용한 토큰의 행 잠금을 끝까지 쥐고 있다. 같은 토큰의 다른 요청이 그동안 기다리므로,
     * 외부 호출이나 비용이 큰 작업(bcrypt 등)을 이 안에 넣지 않는다.
     *
     * @throws RestApiException 없거나 만료되었거나 이미 사용한 토큰이면, 또는 회원이 없거나 탈퇴했으면 {@code INVALID_REFRESH_TOKEN},
     *                          정지된 회원이면 {@code MEMBER_SUSPENDED}
     */
    @Override
    @Transactional
    public TokenResult reissue(TokenReissueCommand command) {
        RotatedRefreshToken rotated = refreshTokenService.rotate(command.refreshToken());

        // 탈퇴 회원은 로그인과 같이 "없는 회원"으로 취급해, 토큰이 실패한 이유를 구분해 드러내지 않는다
        AuthAccount account = memberCredentialReader.findAuthAccount(rotated.memberId())
                .filter(found -> !found.withdrawn())
                .orElseThrow(() -> new RestApiException(AuthErrorCode.INVALID_REFRESH_TOKEN));
        if (account.suspended()) {
            throw new RestApiException(AuthErrorCode.MEMBER_SUSPENDED);
        }
        return createTokens(account, rotated.issued());
    }

    // 로그인과 재발급이 같은 규칙으로 토큰을 만든다. 권한은 발급할 때마다 회원 정보와 판매자 승인 상태로 계산한다.
    // 승인 여부는 회원 유형과 관계없이 조회한다. 관리자는 신청할 수 없어 항상 false지만, 유형에 따라 조회를 건너뛰면 규칙이 하나 더 생긴다.
    // Refresh Token은 로그인이면 새 세션으로 발급한 것을, 재발급이면 같은 세션에서 회전한 것을 받는다
    private TokenResult createTokens(AuthAccount account, IssuedRefreshToken refreshToken) {
        boolean approvedSeller = sellerApprovalReader.isApprovedSeller(account.memberId());
        List<String> roles = TokenRoleMapper.toTokenRoles(account.admin(), approvedSeller);
        AccessToken accessToken = accessTokenIssuer.issue(account.memberId(), roles);

        return new TokenResult(accessToken.value(), accessToken.expiresAt(),
                refreshToken.value(), refreshToken.expiresAt());
    }

}
