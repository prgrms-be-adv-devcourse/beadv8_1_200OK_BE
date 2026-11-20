package com.ok.delivery.adapter.out.persistence;

import com.ok.delivery.application.port.out.RemoteAreaPostalCodeHistoryRepository;
import com.ok.delivery.domain.RemoteAreaPostalCodeHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RemoteAreaPostalCodeHistoryJpaRepository
        extends JpaRepository<RemoteAreaPostalCodeHistory, Long>, RemoteAreaPostalCodeHistoryRepository {
}
