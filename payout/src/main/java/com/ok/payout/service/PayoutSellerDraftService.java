package com.ok.payout.service;

import com.ok.payout.domain.PayoutCandidateItem;
import com.ok.payout.domain.PayoutFailure;
import com.ok.payout.domain.PayoutFailureReason;
import com.ok.payout.domain.PayoutSellerDraft;
import com.ok.payout.domain.PayoutSystemDraftItem;
import com.ok.payout.repository.PayoutFailureRepository;
import com.ok.payout.repository.PayoutSellerDraftRepository;
import com.ok.payout.repository.PayoutSystemDraftItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

// draftStep에서 판매자 1명 단위로 저장하는 일만 맡음 (#90)
// PayoutService.draft가 판매자마다 이 빈을 호출해, 판매자마다 트랜잭션을 따로 둔다
// PayoutService 안에 메서드로 두지 않고 빈을 나눈 이유: 같은 클래스 안에서 부르면 프록시를 거치지 않아 @Transactional(REQUIRES_NEW)이 무시됨
//
// REQUIRES_NEW: 호출한 쪽(draftStep tasklet) 트랜잭션과 별개로 시작·커밋·롤백함
//   → 한 판매자 저장이 실패해도 그 판매자 것만 롤백되고, 이미 끝난 다른 판매자 저장과 바깥 트랜잭션은 영향을 받지 않음
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PayoutSellerDraftService {

    // UNEXPECTED_ERROR 상세 형식: 예외 종류와 메시지 (원인 후보를 특정할 수 없어 무엇이 터졌는지라도 남김)
    private static final String UNEXPECTED_ERROR_DETAIL = "draft 처리 중 예외: %s: %s";
    private static final String NO_MESSAGE = "메시지 없음";

    private final PayoutSellerDraftRepository payoutSellerDraftRepository;
    private final PayoutSystemDraftItemRepository payoutSystemDraftItemRepository;
    private final PayoutFailureRepository payoutFailureRepository;

    // 판매자 1명의 임시 정산을 저장: 판매 대금·배송비는 판매자 draft로, 수수료는 시스템 draft 항목으로
    // 판매자 몫과 수수료를 한 트랜잭션에 둬서, 실패하면 둘 다 롤백됨 → 그 판매자의 수수료도 시스템 몫 정산에 들어가지 않음
    // 정산후보 하나는 판매자 몫·시스템 몫 중 한 곳에만 들어감 (isSystemShare 기준)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveDraft(Long snapshotId, Long sellerId, List<PayoutCandidateItem> candidates) {
        List<PayoutCandidateItem> sellerShare = new ArrayList<>();
        List<PayoutSystemDraftItem> systemItems = new ArrayList<>();
        for (PayoutCandidateItem candidate : candidates) {
            if (candidate.isSystemShare()) {
                systemItems.add(PayoutSystemDraftItem.create(snapshotId, candidate));
            } else {
                sellerShare.add(candidate);
            }
        }

        // 수수료 항목을 먼저 저장: 판매자 draft 저장에서 실패해도 "이미 저장한 수수료가 함께 롤백된다"가 실제로 일어나고,
        // 통합 테스트(판매자 draft UK 위반으로 실패 재현)가 그걸 확인할 수 있게 하려고 (판매자 draft를 먼저 두면 수수료는 저장 전에 멈춤)
        payoutSystemDraftItemRepository.saveAll(systemItems);
        // 수수료 후보만 있는 판매자는 판매자 draft를 만들지 않음 (0원짜리 판매자 정산이 생기지 않게, 기존 동작과 같음)
        if (!sellerShare.isEmpty()) {
            payoutSellerDraftRepository.saveAll(List.of(PayoutSellerDraft.create(snapshotId, sellerId, sellerShare)));
        }
    }

    // saveDraft가 실패한 판매자의 후보 전체를 UNEXPECTED_ERROR로 기록
    // saveDraft와 다른 트랜잭션이어야 함: 실패한 saveDraft 트랜잭션은 이미 롤백돼 그 안에서는 저장할 수 없음
    // 기록된 후보는 이번 스냅샷의 draft 대상에서 빠져(PayoutQueryRepository 조건 ④) 같은 판매자를 다시 잡지 않음
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordUnexpectedError(Long snapshotId, List<PayoutCandidateItem> candidates, RuntimeException cause) {
        // 예외 메시지는 길이를 예측할 수 없어(SQL 문장이 들어가기도 함) 255자로 잘라 둠
        // detail에는 예외 메시지(SQL 문장, 제약 조건명, ID·금액 값 등)가 그대로 들어감
        //   지금은 payout_failure를 조회하는 API·화면이 없어 밖으로 나가지 않음. 실패 기록 조회(관리자 화면 등, #291)를 만들 때 그대로 응답에 내보내지 말 것
        // 메시지가 없는 예외(NullPointerException 등)는 "null" 대신 대체 문구를 남김
        String message = Objects.toString(cause.getMessage(), NO_MESSAGE);
        String detail = PayoutFailure.fitDetail(
                String.format(UNEXPECTED_ERROR_DETAIL, cause.getClass().getSimpleName(), message));
        List<PayoutFailure> failures = new ArrayList<>();
        for (PayoutCandidateItem candidate : candidates) {
            failures.add(PayoutFailure.of(snapshotId, candidate, PayoutFailureReason.UNEXPECTED_ERROR, detail));
        }
        payoutFailureRepository.saveAll(failures);
    }
}
