package com.ok.delivery.service.dto;

public record PostalRegionRegisterCommand(
        String postalCode,
        String regionGroup,
        int extraDeliveryDays
) {
}
