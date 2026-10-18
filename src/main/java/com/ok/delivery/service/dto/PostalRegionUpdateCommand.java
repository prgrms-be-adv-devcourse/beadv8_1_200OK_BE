package com.ok.delivery.service.dto;

public record PostalRegionUpdateCommand(
        String postalCode,
        String regionGroup,
        int extraDeliveryDays
) {
}
