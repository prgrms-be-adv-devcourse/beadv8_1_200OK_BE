package com.ok.delivery.service.dto;

import com.ok.delivery.domain.RemoteAreaPostalCode;

public record PostalRegionResult(
        String postalCode,
        String regionGroup,
        int extraDeliveryDays
) {

    public static PostalRegionResult from(RemoteAreaPostalCode postalCode) {
        return new PostalRegionResult(postalCode.getPostalCode(), postalCode.getRegionGroup(), postalCode.getExtraDeliveryDays());
    }
}
