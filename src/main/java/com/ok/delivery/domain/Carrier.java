package com.ok.delivery.domain;

/**
 * 송장을 발급하는 택배사. 저장 값은 이름 그대로({@code shipment.carrier})다.
 */
public enum Carrier {
    CJ_LOGISTICS,
    POST_OFFICE,
    HANJIN,
    LOTTE,
    LOGEN
}
