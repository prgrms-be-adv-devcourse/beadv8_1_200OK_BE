package com.ok.payout.service;

import com.ok.payout.domain.Payout;
import com.ok.payout.domain.PayoutCandidateItem;
import com.ok.payout.domain.PayoutPolicy;
import com.ok.payout.repository.PayoutQueryRepository;
import com.ok.payout.repository.PayoutRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


@Service
@RequiredArgsConstructor
public class PayoutService {

    private final PayoutQueryRepository payoutQueryRepository;
    private final PayoutRepository payoutRepository;

    // 조회 상한: 실행일 − 대기 일수 00:00 (생성 후 대기 일수가 지난 후보만 정산)
    public LocalDateTime settlementTo(LocalDate runDate) {
        return runDate.minusDays(PayoutPolicy.SETTLEMENT_WAITING_DAYS).atStartOfDay();
    }

    // 조회 하한: 마지막 정산일 − (대기 일수 + 여유 일수) 00:00, 정산이 없으면 null(하한 없음)
    // 마지막 정산일 기준이라 배치가 밀리면 하한도 그만큼 과거로 내려가 밀린 후보까지 조회됨
    public LocalDateTime settlementFrom() {
        LocalDateTime last = payoutRepository.findFirstByOrderByIdDesc()
                .map(Payout::getCreatedAt)
                .orElse(null);
        if (last == null) {
            return null;
        }
        return last.toLocalDate()
                .minusDays(PayoutPolicy.SETTLEMENT_WAITING_DAYS + PayoutPolicy.SETTLEMENT_LOOKBACK_MARGIN_DAYS)
                .atStartOfDay();
    }

    // [from, to) 기간 미정산 후보를 판매자 limit명 단위로 정산하고, 정산한 판매자 수를 반환 (0이면 더 없음 → tasklet 종료)
    // 성공 전제로 구현함. 실패 처리는 #52
    public int settle(LocalDateTime from, LocalDateTime to, int limit) {
        List<Long> sellerIds = payoutQueryRepository.findUnsettledSellerIds(from, to, limit);
        if (sellerIds.isEmpty()) {
            return 0;
        }

        List<PayoutCandidateItem> candidates = payoutQueryRepository.findUnsettledCandidates(sellerIds, from, to);

        // 수취인별로 묶어서 Payout 생성 (payeeId(): 수수료 → 시스템, 대금·배송비 → 판매자)
        Map<Long, List<PayoutCandidateItem>> candidatesByPayee = new LinkedHashMap<>();
        for (PayoutCandidateItem candidate : candidates) {
            Long payeeId = candidate.payeeId();
            if (!candidatesByPayee.containsKey(payeeId)) {
                candidatesByPayee.put(payeeId, new ArrayList<>());
            }
            candidatesByPayee.get(payeeId).add(candidate);
        }

        List<Payout> payouts = new ArrayList<>();
        for (Map.Entry<Long, List<PayoutCandidateItem>> entry : candidatesByPayee.entrySet()) {
            Long payeeId = entry.getKey();
            List<PayoutCandidateItem> payeeCandidates = entry.getValue();
            payouts.add(Payout.create(payeeId, payeeCandidates));
        }

        payoutRepository.saveAll(payouts);

        return sellerIds.size();
    }
}
