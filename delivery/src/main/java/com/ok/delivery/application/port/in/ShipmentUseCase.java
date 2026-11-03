package com.ok.delivery.application.port.in;

import com.ok.delivery.application.dto.SellerShipmentSummaryResult;
import com.ok.delivery.application.dto.ShipmentCreateCommand;
import com.ok.delivery.application.dto.ShipmentDetailResult;
import com.ok.delivery.application.dto.ShipmentDispatchCommand;
import com.ok.delivery.application.dto.ShipmentDispatchResult;
import com.ok.delivery.application.dto.ShipmentSummaryResult;
import org.springframework.data.domain.Page;

/**
 * 결제 완료된 주문의 배송 생성, 출고, 배송 완료와 구매자·판매자의 배송 조회 유스케이스.
 */
public interface ShipmentUseCase {

    /**
     * 결제 완료된 주문으로 판매자별 shipment를 {@code PREPARING} 상태로 만든다.
     * 배송비 금액은 결제에 쓰인 값을 그대로 저장하고, 지역 그룹과 도착 예정일은 배송지 우편번호로 판정한다.
     * 이미 shipment가 있는 주문이면 만들지 않는다(이벤트 재발행 대비).
     *
     * @throws com.ok.common.exception.RestApiException 배송지 검증 규칙은 {@link com.ok.delivery.domain.Address#of}를 따른다
     * @throws ArithmeticException 금액이 {@code int} 범위를 넘으면
     */
    void create(ShipmentCreateCommand command);

    /**
     * 판매자가 송장을 등록해 출고한다. {@code PREPARING → SHIPPING}
     * shipment 행 잠금 아래에서 처리하므로 환불의 취소 확정 예약과 동시에 실행돼도 한쪽만 성공한다.
     * 대기 중인 배송지 변경 요청은 송장 발행 후 바꿀 수 없으므로 고정 문구로 자동 거절한다.
     * 출고가 확정되면 주문이 배송 중으로 바뀌도록 배송 시작 이벤트를 발행한다.
     *
     * @throws com.ok.common.exception.RestApiException 없거나 다른 판매자의 shipment면 {@code SHIPMENT_NOT_FOUND},
     *                          같은 택배사의 송장번호가 이미 쓰였으면 {@code TRACKING_NUMBER_DUPLICATED},
     *                          상태·송장번호 형식·취소 예약 규칙은 {@link com.ok.delivery.domain.Shipment#dispatch}를 따른다
     */
    ShipmentDispatchResult dispatch(ShipmentDispatchCommand command);

    /**
     * 택배사의 배송 완료 알림을 반영한다. {@code SHIPPING → DELIVERED}
     * 같은 알림이 동시에 두 번 와도 배송 완료 이벤트가 한 번만 나가도록 shipment 행 잠금 아래에서 처리한다.
     * 주문은 이 이벤트의 배송 완료 시각을 자동 구매확정 기준으로 쓴다.
     *
     * @throws com.ok.common.exception.RestApiException 없으면 {@code SHIPMENT_NOT_FOUND}, 상태 규칙은 {@link com.ok.delivery.domain.Shipment#deliver}를 따른다
     */
    void deliver(Long shipmentId);

    /**
     * 구매자의 배송 목록을 최신순으로 25건씩 조회한다.
     * 목록의 대표 상품은 페이지에 담긴 shipment들의 상품 항목을 한 번에 읽어 채운다.
     *
     * @param page 0부터 시작하는 페이지 번호
     */
    Page<ShipmentSummaryResult> getBuyerShipments(Long buyerId, int page);

    /**
     * 판매자의 출고 목록을 최신순으로 25건씩 조회한다.
     * 대표 상품과 대기 중인 배송지 변경 요청 여부는 페이지에 담긴 shipment들에 대해 한 번에 읽어 채운다.
     *
     * @param page 0부터 시작하는 페이지 번호
     */
    Page<SellerShipmentSummaryResult> getSellerShipments(Long sellerId, int page);

    /**
     * 구매자의 배송 상세를 조회한다.
     *
     * @throws com.ok.common.exception.RestApiException 없거나 다른 구매자의 shipment면 {@code SHIPMENT_NOT_FOUND}
     */
    ShipmentDetailResult getBuyerShipment(Long buyerId, Long shipmentId);

    /**
     * 판매자의 출고 상세를 조회한다. 구매자 상세와 같은 형태이고, 대기 중인 배송지 변경 요청은 가장 최근 요청으로 담긴다.
     *
     * @throws com.ok.common.exception.RestApiException 없거나 다른 판매자의 shipment면 {@code SHIPMENT_NOT_FOUND}
     */
    ShipmentDetailResult getSellerShipment(Long sellerId, Long shipmentId);
}
