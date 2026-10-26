package com.ok.payout.service;

import com.ok.payout.domain.PayoutFailure;
import com.ok.payout.repository.PayoutFailureRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

// verifyStep의 결과 검증 실패 기록 저장만 맡음 (#290)
// PayoutService 안에 두지 않고 빈을 나눈 이유: 같은 클래스 안에서 부르면 프록시를 거치지 않아 @Transactional(REQUIRES_NEW)이 무시됨 (PayoutSellerDraftService와 같음)
//
// REQUIRES_NEW로 따로 커밋하는 이유: verifyStep은 정산할 것이 0건 남으면 Step을 실패시켜 Job을 FAILED로 끝냄
//   Step 트랜잭션 안에서 저장하면 그 실패로 함께 롤백되어, 걸린 주문의 실패 기록과 잠금이 남지 않음
//   → 버그를 고치고 재실행해도 같은 주문이 확인 없이 다시 정산 대상이 됨
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PayoutVerifyFailureService {

    private final PayoutFailureRepository payoutFailureRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveAll(List<PayoutFailure> failures) {
        payoutFailureRepository.saveAll(failures);
    }
}
