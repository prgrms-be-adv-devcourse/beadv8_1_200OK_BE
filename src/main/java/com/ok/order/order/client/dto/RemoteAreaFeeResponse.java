package com.ok.order.order.client.dto;

public record RemoteAreaFeeResponse(String zipCode, boolean remoteArea, long additionalFee) {
}
