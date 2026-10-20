package com.ok.order.order.controller;

import com.ok.order.order.controller.dto.ReviewCandidateRequest;
import com.ok.order.order.controller.dto.ReviewCandidateResponse;
import com.ok.order.order.service.OrderService;
import com.ok.order.order.service.dto.ReviewCandidateResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

// 다른 모듈이 HTTP로 호출하는 주문 내부 API
@RestController
@RequestMapping("/internal/v1/orders")
@RequiredArgsConstructor
public class OrderInternalController implements OrderInternalControllerSpec {

    private final OrderService orderService;

    @Override
    @GetMapping("/review-candidates")
    public ResponseEntity<List<ReviewCandidateResponse>> getReviewCandidates(
            @Valid @ModelAttribute ReviewCandidateRequest request
    ) {
        // 서비스에서 리뷰 가능 구매 건 조회
        List<ReviewCandidateResult> results =
                orderService.getReviewCandidates(request.memberId(), request.productId());

        // 하나씩 응답 형태로 바꾸기
        List<ReviewCandidateResponse> responses = new ArrayList<>();
        for (ReviewCandidateResult result : results) {
            responses.add(ReviewCandidateResponse.from(result));
        }

        // 응답
        return ResponseEntity.ok(responses);
    }
}
