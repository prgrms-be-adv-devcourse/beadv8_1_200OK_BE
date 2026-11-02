package com.ok.delivery.application.dto;

public record PostalRegionUpdateCommand(
        String postalCode,
        String regionGroup,
        int extraDeliveryDays
) {
}
