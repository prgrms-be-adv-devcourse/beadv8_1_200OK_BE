package com.ok.delivery.application.dto;

public record PostalRegionRegisterCommand(
        String postalCode,
        String regionGroup,
        int extraDeliveryDays
) {
}
