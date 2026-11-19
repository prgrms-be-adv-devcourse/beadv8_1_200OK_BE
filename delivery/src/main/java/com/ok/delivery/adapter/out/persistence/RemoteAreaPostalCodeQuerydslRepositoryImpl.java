package com.ok.delivery.adapter.out.persistence;

import com.ok.delivery.domain.RemoteAreaPostalCode;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static com.ok.delivery.domain.QRemoteAreaPostalCode.remoteAreaPostalCode;

@RequiredArgsConstructor
public class RemoteAreaPostalCodeQuerydslRepositoryImpl implements RemoteAreaPostalCodeQuerydslRepository {

    private final JPAQueryFactory queryFactory;

    @Override
    public Optional<RemoteAreaPostalCode> findActive(String postalCode) {
        return Optional.ofNullable(queryFactory
                .selectFrom(remoteAreaPostalCode)
                .where(
                        remoteAreaPostalCode.postalCode.eq(postalCode),
                        remoteAreaPostalCode.deletedAt.isNull()
                )
                .fetchOne());
    }

    @Override
    public List<RemoteAreaPostalCode> findActiveIn(Collection<String> postalCodes, String regionGroup) {
        return queryFactory
                .selectFrom(remoteAreaPostalCode)
                .where(
                        remoteAreaPostalCode.postalCode.in(postalCodes),
                        remoteAreaPostalCode.regionGroup.eq(regionGroup),
                        remoteAreaPostalCode.deletedAt.isNull()
                )
                .fetch();
    }
}
