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

    //TO 정산 시작 : 오늘날짜 - SETTLEMENT_WAITING_DAYS.안
    public LocalDateTime settlementTo(LocalDate runDate) {
        return runDate.minusDays(PayoutPolicy.SETTLEMENT_WAITING_DAYS).atStartOfDay();
    }

    //FROM 정산조건범위여기까지 : 최근 정산일 − (15일 + 1일) 00:00  처음이면 NULL    이전 정산했던거 범위 재외
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

    public int settle(LocalDateTime from, LocalDateTime to, int limit) {
        // 이번 회차에 정산할 판매자 ID 조회
        List<Long> sellerIds = payoutQueryRepository.findUnsettledSellerIds(from, to, limit);
        if (sellerIds.isEmpty()) {
            return 0;//금일 정산 종료
        }

        // 오늘n명 정산할 후보들 모음
        List<PayoutCandidateItem> candidates =payoutQueryRepository.findUnsettledCandidates(sellerIds, from, to);

        //수취인별로 묶어서 Payout 생성  수수료 → 시스템    대금·배송비 → 판매자
        Map<Long, List<PayoutCandidateItem>> candidatesByPayee = new LinkedHashMap<>();
        for (PayoutCandidateItem candidate : candidates) {
            Long payeeId = candidate.payeeId();
            if (!candidatesByPayee.containsKey(payeeId)) {
                candidatesByPayee.put(payeeId, new ArrayList<>());
            }
            candidatesByPayee.get(payeeId).add(candidate); //{판매자 : 정산후보들}
        }

        List<Payout> payouts = new ArrayList<>();
        for (Map.Entry<Long, List<PayoutCandidateItem>> entry : candidatesByPayee.entrySet()) {//entrySet()으로 Set변경후 for문
            Long payeeId = entry.getKey();                             // 수취인 ID
            List<PayoutCandidateItem> payeeCandidates = entry.getValue(); // 그 수취인의 후보들
            payouts.add(Payout.create(payeeId, payeeCandidates)); // 여기서 payout생성. 아직 저장 전
        }

        payoutRepository.saveAll(payouts);

        return sellerIds.size();
    }
}
