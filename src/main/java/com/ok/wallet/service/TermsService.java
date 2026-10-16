package com.ok.wallet.service;

import com.ok.common.exception.RestApiException;
import com.ok.wallet.domain.Terms;
import com.ok.wallet.domain.TermsAgreement;
import com.ok.wallet.domain.TermsType;
import com.ok.wallet.exception.WalletErrorCode;
import com.ok.wallet.repository.TermsAgreementRepository;
import com.ok.wallet.repository.TermsRepository;
import com.ok.wallet.service.dto.TermsAgreeCommand;
import com.ok.wallet.service.dto.TermsResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 지갑 충전 약관의 조회, 동의, 충전 전 동의 확인을 담당한다.
 * 약관은 유형별 가장 높은 버전이 현재 버전이며, 동의는 현재 버전 기준으로 판단한다.
 */
@Service
@RequiredArgsConstructor
public class TermsService {
    private final TermsRepository termsRepository;
    private final TermsAgreementRepository termsAgreementRepository;
    // 현재 시각 기준 (전역 ClockConfig). 동의 시각을 테스트에서 고정할 수 있게 주입받는다.
    private final Clock clock;

    /** 현재 버전 약관과 회원의 약관별 동의 여부를 반환한다. */
    @Transactional(readOnly = true)
    public List<TermsResult> getTerms(Long memberId) {
        List<Terms> currentTerms = currentTerms();
        Set<Long> agreedTermsIds = agreedTermsIds(memberId, currentTerms);
        return currentTerms.stream()
                .map(terms -> TermsResult.of(terms, agreedTermsIds.contains(terms.getId())))
                .toList();
    }

    /**
     * 요청한 약관에 동의한다.
     * 요청한 버전이 모두 현재 버전이고 필수 약관이 모두 포함된 경우에만 저장하며,
     * 이미 동의한 약관은 다시 저장하지 않아 재요청해도 오류 없이 성공한다.
     */
    @Transactional
    public void agree(Long memberId, List<TermsAgreeCommand> commands) {
        // 사용자가 보낸 유형 문자열을 먼저 확인한다. 모르는 유형이면 버전 비교 전에 알려 준다.
        Set<TermsType> requestedTypes = commands.stream()
                .map(command -> toTermsType(command.type()))
                .collect(Collectors.toSet());

        List<Terms> currentTerms = currentTerms();
        validateCurrentVersions(commands, currentTerms);
        validateRequiredIncluded(requestedTypes);

        Set<Long> agreedTermsIds = agreedTermsIds(memberId, currentTerms);
        // 한 번의 요청에서 동의한 약관들은 같은 시각으로 기록한다
        LocalDateTime now = LocalDateTime.now(clock);
        currentTerms.stream()
                .filter(terms -> requestedTypes.contains(terms.getType()))
                .filter(terms -> !agreedTermsIds.contains(terms.getId()))
                .forEach(terms -> termsAgreementRepository.save(
                        TermsAgreement.create(memberId, terms.getId(), now)));
    }

    // 약관 유형은 사용자 요청에서 오므로, 모르는 유형은 규칙 위반으로 응답한다 (값의 출처 기준, #123)
    private TermsType toTermsType(String type) {
        return Arrays.stream(TermsType.values())
                .filter(termsType -> termsType.name().equals(type))
                .findFirst()
                .orElseThrow(() -> new RestApiException(WalletErrorCode.INVALID_TERMS_TYPE));
    }

    /**
     * 충전 전에 회원이 현재 버전의 필수 약관에 모두 동의했는지 확인한다.
     * 하나라도 동의하지 않았다면 충전할 수 없다.
     */
    @Transactional(readOnly = true)
    public void validateRequiredAgreed(Long memberId) {
        List<Terms> requiredTerms = currentTerms().stream()
                .filter(terms -> terms.getType().isRequired())
                .toList();
        Set<Long> agreedTermsIds = agreedTermsIds(memberId, requiredTerms);
        if (agreedTermsIds.size() != requiredTerms.size()) {
            throw new RestApiException(WalletErrorCode.TERMS_AGREEMENT_REQUIRED);
        }
    }

    // 모든 약관 유형의 현재 버전. 약관은 마이그레이션으로 넣는 시스템 데이터라 없으면 코드·데이터의 이상이다 (값의 출처 기준, #123)
    private List<Terms> currentTerms() {
        return Arrays.stream(TermsType.values())
                .map(type -> termsRepository.findFirstByTypeOrderByVersionDesc(type)
                        .orElseThrow(() -> new IllegalStateException("현재 버전 약관이 없습니다. type=" + type)))
                .toList();
    }

    private Set<Long> agreedTermsIds(Long memberId, List<Terms> terms) {
        List<Long> termsIds = terms.stream().map(Terms::getId).toList();
        return termsAgreementRepository.findAllByMemberIdAndTermsIdIn(memberId, termsIds).stream()
                .map(TermsAgreement::getTermsId)
                .collect(Collectors.toSet());
    }

    // 사용자가 보고 동의한 버전이 현재 버전과 다르면, 그 사이 약관이 바뀐 것이므로 최신 약관을 다시 보게 한다
    private void validateCurrentVersions(List<TermsAgreeCommand> commands, List<Terms> currentTerms) {
        for (TermsAgreeCommand command : commands) {
            boolean isCurrent = currentTerms.stream()
                    .anyMatch(terms -> terms.getType().name().equals(command.type())
                            && terms.getVersion() == command.version());
            if (!isCurrent) {
                throw new RestApiException(WalletErrorCode.TERMS_VERSION_OUTDATED);
            }
        }
    }

    private void validateRequiredIncluded(Set<TermsType> requestedTypes) {
        boolean allRequiredIncluded = Arrays.stream(TermsType.values())
                .filter(TermsType::isRequired)
                .allMatch(requestedTypes::contains);
        if (!allRequiredIncluded) {
            throw new RestApiException(WalletErrorCode.REQUIRED_TERMS_NOT_AGREED);
        }
    }
}
