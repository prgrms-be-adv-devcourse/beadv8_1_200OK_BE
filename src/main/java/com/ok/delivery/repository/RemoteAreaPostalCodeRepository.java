package com.ok.delivery.repository;

import com.ok.delivery.domain.RemoteAreaPostalCode;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface RemoteAreaPostalCodeRepository extends JpaRepository<RemoteAreaPostalCode, String> {

    Optional<RemoteAreaPostalCode> findByPostalCodeAndDeletedAtIsNull(String postalCode);

    /**
     * 삭제된 행까지 포함해 우편번호 행을 쓰기 잠금({@code SELECT ... FOR UPDATE})으로 조회한다.
     * 등록·되살림·삭제를 하는 트랜잭션은 이 메서드를 첫 쿼리로 호출해야 한다.
     * 같은 번호를 동시에 바꾸는 요청을 순서대로 처리해, 뒤 요청이 앞 요청이 커밋한 상태를 보고 판단하게 한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from RemoteAreaPostalCode p where p.postalCode = :postalCode")
    Optional<RemoteAreaPostalCode> findByIdForUpdate(@Param("postalCode") String postalCode);

    Page<RemoteAreaPostalCode> findByDeletedAtIsNull(Pageable pageable);
}
