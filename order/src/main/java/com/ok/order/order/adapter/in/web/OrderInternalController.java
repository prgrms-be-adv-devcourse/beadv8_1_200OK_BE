package com.ok.order.order.adapter.in.web;

import com.ok.order.order.adapter.in.web.dto.OrderItemForRefundResponse;
import com.ok.order.order.adapter.in.web.dto.RecordOrderItemRefundRequest;
import com.ok.order.order.adapter.in.web.dto.ReviewCandidateRequest;
import com.ok.order.order.adapter.in.web.dto.ReviewCandidateResponse;
import com.ok.order.order.application.dto.ReviewCandidateResult;
import com.ok.order.order.application.port.in.GetOrderItemForRefundUseCase;
import com.ok.order.order.application.port.in.GetReviewCandidatesUseCase;
import com.ok.order.order.application.port.in.RecordOrderItemRefundUseCase;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

// 다른 모듈이 HTTP로 호출하는 주문 내부 API
@RestController
@RequestMapping("/internal/v1/orders")
@RequiredArgsConstructor
public class OrderInternalController implements OrderInternalControllerSpec {

    private final GetReviewCandidatesUseCase getReviewCandidates;
    private final GetOrderItemForRefundUseCase getOrderItemForRefund;
    private final RecordOrderItemRefundUseCase recordOrderItemRefund;

    @Override
    @GetMapping("/review-candidates")
    public ResponseEntity<List<ReviewCandidateResponse>> getReviewCandidates(
            @Valid @ModelAttribute ReviewCandidateRequest request
    ) {
        // 서비스에서 리뷰 가능 구매 건 조회
        List<ReviewCandidateResult> results =
                getReviewCandidates.getReviewCandidates(request.memberId(), request.productId());

        // 하나씩 응답 형태로 바꾸기
        List<ReviewCandidateResponse> responses = new ArrayList<>();
        for (ReviewCandidateResult result : results) {
            responses.add(ReviewCandidateResponse.from(result));
        }

        // 응답
        return ResponseEntity.ok(responses);
    }

    @Override
    @GetMapping("/{orderId}/items/{orderItemId}")
    public ResponseEntity<OrderItemForRefundResponse> getOrderItemForRefund(
            @PathVariable Long orderId,
            @PathVariable Long orderItemId
    ) {
        return ResponseEntity.ok(OrderItemForRefundResponse.from(
                getOrderItemForRefund.getOrderItemForRefund(orderId, orderItemId)));
    }

    @Override
    @PostMapping("/items/{orderItemId}/refunds")
    public ResponseEntity<Void> recordRefund(
            @PathVariable Long orderItemId,
            @Valid @RequestBody RecordOrderItemRefundRequest request
    ) {
        recordOrderItemRefund.recordRefund(request.toCommand(orderItemId));
        return ResponseEntity.noContent().build();
    }
}
