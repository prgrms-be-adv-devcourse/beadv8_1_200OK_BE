package com.ok.wallet.client.toss;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
record TossErrorResponse(String code, String message) {
}
