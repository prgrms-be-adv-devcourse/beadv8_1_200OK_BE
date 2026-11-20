package com.ok.wallet.adapter.out.toss;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
record TossErrorResponse(String code, String message) {
}
