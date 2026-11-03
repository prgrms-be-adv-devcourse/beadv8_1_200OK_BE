package com.ok.product.product.service;

import com.ok.common.exception.RestApiException;
import com.ok.product.product.exception.ProductErrorCode;
import com.ok.product.product.service.dto.StockPartialReleaseCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * 재고 일부 해제를 트랜잭션 밖에서 감싸, 같은 환불 ID 요청이 동시에 겹쳐도 먼저 처리된 요청과 같은 결과로 응답하게 한다.
 * 겹친 요청은 둘 다 이력이 없다고 본 뒤 배정 행 락에서 줄을 서므로, 뒤에 온 쪽은 수량 초과나 유니크 키 위반으로 롤백된다.
 * 롤백된 트랜잭션은 이어서 쓸 수 없고, 같은 트랜잭션에서 다시 조회해도 REPEATABLE READ 스냅숏이라 먼저 커밋된 이력이 보이지 않는다.
 * 그래서 서비스 트랜잭션이 끝난 뒤 새 트랜잭션으로 이력을 다시 확인한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductStockPartialReleaseFacade {

	private final ProductStockService productStockService;

	/**
	 * 환불 한 건만큼 주문의 옵션 배정 재고를 일부 되돌린다.
	 * 실패했더라도 같은 환불 ID로 같은 내용이 이미 반영됐으면 성공으로 끝낸다.
	 *
	 * @throws RestApiException {@link ProductStockService#partialRelease}의 에러 코드와 같다
	 */
	public void partialRelease(StockPartialReleaseCommand command) {
		try {
			productStockService.partialRelease(command);
		} catch (DataIntegrityViolationException e) {
			// 어느 제약인지는 DB 오류 메시지로만 알 수 있어 보지 않고 이력으로 판단한다. 환불 ID 유니크 위반은 상대가 이력을
			// 커밋한 뒤에만 나고(상대가 롤백하면 이쪽 INSERT가 성공한다) 이력은 지우지 않으니, 이력이 없으면 다른 제약 위반(버그)이라 그대로 던져 500으로 드러낸다
			if (!productStockService.isPartiallyReleased(command)) {
				throw e;
			}
			log.info("같은 환불의 동시 요청, 먼저 반영된 일부 해제와 같은 내용. refundId={}", command.refundId());
		} catch (RestApiException e) {
			// 먼저 처리된 같은 환불이 수량을 이미 차감해 남은 수량이 모자란 경우일 수 있다
			if (e.getErrorCode() != ProductErrorCode.RELEASE_QUANTITY_EXCEEDED
				|| !productStockService.isPartiallyReleased(command)) {
				throw e;
			}
			log.info("같은 환불의 동시 요청, 먼저 반영된 일부 해제와 같은 내용. refundId={}", command.refundId());
		}
	}
}
