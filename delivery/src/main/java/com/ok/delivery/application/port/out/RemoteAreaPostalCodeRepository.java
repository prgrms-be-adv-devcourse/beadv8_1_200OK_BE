package com.ok.delivery.application.port.out;

import com.ok.delivery.domain.RemoteAreaPostalCode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 도서산간 우편번호 저장소 출력 포트. 구현은 {@code adapter.out.persistence}에 있다.
 */
public interface RemoteAreaPostalCodeRepository {

    /**
     * 삭제되지 않은 우편번호 행을 조회한다.
     */
    Optional<RemoteAreaPostalCode> findActive(String postalCode);

    /**
     * 주어진 우편번호 중 지정한 지역 그룹에 속하고 삭제되지 않은 행을 조회한다.
     */
    List<RemoteAreaPostalCode> findActiveIn(Collection<String> postalCodes, String regionGroup);

    /**
     * 삭제된 행까지 포함해 우편번호 행을 쓰기 잠금({@code SELECT ... FOR UPDATE})으로 조회한다.
     * 등록·되살림·삭제를 하는 트랜잭션은 이 메서드를 첫 쿼리로 호출해야 한다.
     * 같은 번호를 동시에 바꾸는 요청을 순서대로 처리해, 뒤 요청이 앞 요청이 커밋한 상태를 보고 판단하게 한다.
     */
    Optional<RemoteAreaPostalCode> findByIdForUpdate(String postalCode);

    Page<RemoteAreaPostalCode> findByDeletedAtIsNull(Pageable pageable);

    <S extends RemoteAreaPostalCode> S saveAndFlush(S entity);
}
