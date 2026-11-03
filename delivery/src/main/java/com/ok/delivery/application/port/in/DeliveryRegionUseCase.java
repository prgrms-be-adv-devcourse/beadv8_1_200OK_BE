package com.ok.delivery.application.port.in;

import com.ok.delivery.application.dto.PostalRegionRegisterCommand;
import com.ok.delivery.application.dto.PostalRegionResult;
import com.ok.delivery.application.dto.PostalRegionUpdateCommand;
import com.ok.delivery.application.dto.RegionResult;
import org.springframework.data.domain.Page;

/**
 * 우편번호의 지역 판정과 도서산간 우편번호 관리 유스케이스.
 */
public interface DeliveryRegionUseCase {

    /**
     * 등록되지 않았거나 삭제된 우편번호는 일반 지역({@code GENERAL}, 0원, 0일)으로 판정한다.
     * 입력은 {@code Address}나 요청 DTO에서 이미 검증된 우편번호다. 형식은 다시 검사하지 않으며, 형식이 틀린 값은 등록되지 않은 번호로 본다.
     * {@code null}은 호출한 코드의 버그이고, 일반 지역(추가 운임 0원)으로 숨기면 결제 금액이 틀려지므로 예외로 드러낸다.
     *
     * @throws IllegalArgumentException 우편번호가 {@code null}이면
     */
    RegionResult resolve(String postalCode);

    /**
     * 도서산간 우편번호를 등록한다. 삭제된 번호면 새 행을 만들지 않고 그 행을 요청값으로 되살린다.
     * 등록은 {@code REGISTERED}, 되살림은 {@code RESTORED} 이력을 같은 트랜잭션에 남긴다.
     *
     * @param adminId 등록하는 관리자의 회원 ID
     * @throws com.ok.common.exception.RestApiException 이미 등록된 번호면 {@code POSTAL_REGION_DUPLICATED},
     *                          없는 그룹이거나 {@code GENERAL}이면 {@code REGION_GROUP_NOT_FOUND},
     *                          값 검증은 {@link com.ok.delivery.domain.RemoteAreaPostalCode#of}를 따른다
     */
    PostalRegionResult register(PostalRegionRegisterCommand command, Long adminId);

    /**
     * 도서산간 우편번호의 그룹과 추가 소요일을 바꾸고 {@code UPDATED} 이력을 같은 트랜잭션에 남긴다.
     * 삭제 후 재등록과 달리 일반 지역으로 판정되는 순간이 없다. 같은 값이면 바꾸지 않고 이력도 남기지 않는다.
     *
     * @param adminId 수정하는 관리자의 회원 ID
     * @throws com.ok.common.exception.RestApiException 없거나 삭제된 번호면 {@code POSTAL_REGION_NOT_FOUND},
     *                          없는 그룹이거나 {@code GENERAL}이면 {@code REGION_GROUP_NOT_FOUND},
     *                          값 검증은 {@link com.ok.delivery.domain.RemoteAreaPostalCode#update}를 따른다
     */
    PostalRegionResult update(PostalRegionUpdateCommand command, Long adminId);

    /**
     * 도서산간 우편번호를 삭제(soft delete)하고 {@code DELETED} 이력을 같은 트랜잭션에 남긴다. 삭제한 번호는 일반 지역으로 판정된다.
     *
     * @param adminId 삭제하는 관리자의 회원 ID
     * @throws com.ok.common.exception.RestApiException 없거나 이미 삭제된 번호면 {@code POSTAL_REGION_NOT_FOUND}
     */
    void delete(String postalCode, Long adminId);

    /**
     * 삭제되지 않은 도서산간 우편번호를 우편번호 순으로 조회한다.
     *
     * @param page 0부터 시작하는 페이지 번호
     */
    Page<PostalRegionResult> getPostalRegions(int page, int size);
}
