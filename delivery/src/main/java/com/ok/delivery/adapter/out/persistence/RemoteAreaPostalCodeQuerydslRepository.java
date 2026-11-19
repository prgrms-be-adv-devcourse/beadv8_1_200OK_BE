package com.ok.delivery.adapter.out.persistence;

import com.ok.delivery.domain.RemoteAreaPostalCode;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RemoteAreaPostalCodeQuerydslRepository {

    Optional<RemoteAreaPostalCode> findActive(String postalCode);

    List<RemoteAreaPostalCode> findActiveIn(Collection<String> postalCodes, String regionGroup);
}
