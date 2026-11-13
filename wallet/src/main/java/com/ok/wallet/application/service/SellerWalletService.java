package com.ok.wallet.application.service;

import com.ok.wallet.application.port.in.SellerWalletUseCase;
import com.ok.wallet.application.port.out.SellerWalletLedgerRepository;
import com.ok.wallet.application.port.out.SellerWalletRepository;
import com.ok.wallet.domain.SellerWallet;
import com.ok.wallet.domain.SellerWalletLedger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class SellerWalletService implements SellerWalletUseCase {
    private static final String PAYOUT_REFERENCE_PREFIX = "PAYOUT:";

    private final SellerWalletRepository sellerWalletRepository;
    private final SellerWalletLedgerRepository sellerWalletLedgerRepository;
    // 현재 시각 기준 (전역 ClockConfig, JVM 시간대 = Asia/Seoul)
    // 지갑 생성 시각을 LocalDateTime.now()로 직접 만들면 실행 환경의 시간대를 따라가고, 테스트에서 값을 고정할 수 없다 (#93)
    private final Clock clock;

    @Override
    @Transactional
    public void createIfAbsent(Long memberId) {
        insertIfMissing(memberId);
    }

    /**
     * 판매자 지갑을 잠금 조회로 반환한다. 지갑이 없다면 생성한 뒤 반환한다.
     * 존재 확인은 일반 조회로 하고, 없으면 저장한 뒤 잠금 조회로 반환한다.
     * 반환된 지갑은 호출한 트랜잭션이 끝날 때까지 잠겨 있으므로 잔액 조회처럼 읽기만 하는 곳에는 쓰지 않는다.
     * 엔티티를 돌려주므로 입력 포트에 두지 않고, 정산금 입금처럼 잔액을 바꾸는 application 안쪽에서만 쓴다.
     * 판매자 승인 여부는 확인하지 않으므로 승인된 판매자임이 이미 보장된 경로에서만 호출한다.
     */
    @Transactional
    public SellerWallet getOrCreate(Long memberId) {
        insertIfMissing(memberId);
        // 바로 앞에서 저장했거나 존재를 확인했으므로 여기서 없을 수 없다.
        // 없다면 사용자 입력이 아닌 코드·DB의 이상이므로 500으로 드러나도록 IllegalStateException을 던진다.
        return sellerWalletRepository.findForUpdateByMemberId(memberId)
                .orElseThrow(() -> new IllegalStateException("판매자 지갑 생성 후 조회에 실패했습니다. memberId=" + memberId));
    }

    // 지갑을 만드는 경로를 하나로 둔다. 같은 클래스의 @Transactional 메서드를 부르면 프록시를 거치지 않으므로 private 메서드로 나눈다
    private void insertIfMissing(Long memberId) {
        // 그대로 두면 DB의 NOT NULL 제약 오류로 터져, 호출하는 쪽의 잘못인지 DB 문제인지 바로 알기 어렵다
        if (memberId == null) {
            throw new IllegalArgumentException("회원 ID는 필수입니다.");
        }
        if (!sellerWalletRepository.existsByMemberId(memberId)) {
            sellerWalletRepository.insertIfAbsent(memberId, LocalDateTime.now(clock));
        }
    }

    @Override
    @Transactional
    public void depositPayout(Long sellerId, Long payoutId, long amount) {
        // payoutId가 null이면 "PAYOUT:null"이라는 정상처럼 보이는 키가 만들어져 지갑 검증을 통과하므로 여기서 막는다.
        if (payoutId == null) {
            throw new IllegalArgumentException("payoutId는 필수입니다.");
        }
        String referenceKey = PAYOUT_REFERENCE_PREFIX + payoutId;

        SellerWallet wallet = getOrCreate(sellerId);
        // 이 지갑에 이미 입금한 정산이면 넘어간다. 지갑을 잠근 뒤 확인하므로 같은 지갑의 요청은 순서대로 이 확인을 거친다.
        // 다른 지갑에 같은 키가 있는 경우는 일부러 넘기지 않는다. 조용히 무시하지 않고 저장 시 유니크 제약으로 실패하게 해 오류를 드러낸다.
        if (sellerWalletLedgerRepository.existsBySellerWalletIdAndReferenceKey(wallet.getId(), referenceKey)) {
            log.info("이미 입금된 정산이라 건너뜁니다. sellerWalletId={}, referenceKey={}", wallet.getId(), referenceKey);
            return;
        }
        SellerWalletLedger ledger = wallet.deposit(amount, referenceKey);
        sellerWalletLedgerRepository.save(ledger);
    }

    @Override
    @Transactional(readOnly = true)
    public long getBalance(Long memberId) {
        return sellerWalletRepository.findByMemberId(memberId)
                .map(SellerWallet::getBalance)
                .orElse(0L);
    }
}
