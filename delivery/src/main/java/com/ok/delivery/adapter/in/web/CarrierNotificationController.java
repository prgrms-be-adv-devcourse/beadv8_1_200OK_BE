package com.ok.delivery.adapter.in.web;

import com.ok.common.exception.RestApiException;
import com.ok.delivery.adapter.in.web.dto.CarrierNotificationRequest;
import com.ok.delivery.application.port.in.CarrierNotificationUseCase;
import com.ok.delivery.exception.DeliveryErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

// 외부 택배사가 호출하므로 /internal 아래에 두지 않는다(운영은 /internal을 loopback으로만 연다).
// 택배사는 인증 헤더를 보내지 못해 등록한 callbackUrl의 경로에 비밀값을 넣어 확인한다. 로그인 없이 열리는 경로는 common의 SecurityConfig에 있다
@RestController
@RequestMapping("/api/v1/carrier-notifications")
public class CarrierNotificationController implements CarrierNotificationControllerSpec {

    private final CarrierNotificationUseCase carrierNotificationUseCase;
    private final byte[] secret;

    public CarrierNotificationController(CarrierNotificationUseCase carrierNotificationUseCase,
                                         @Value("${delivery.carrier.webhook.secret:}") String secret) {
        this.carrierNotificationUseCase = carrierNotificationUseCase;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    @PostMapping("/{secret}")
    public ResponseEntity<Void> receive(@PathVariable String secret, @RequestBody CarrierNotificationRequest request) {
        if (!matches(secret)) {
            throw new RestApiException(DeliveryErrorCode.INVALID_CARRIER_NOTIFICATION_SECRET);
        }
        carrierNotificationUseCase.receive(request.toCommand());
        return ResponseEntity.noContent().build();
    }

    // 값이 달라도 비교 시간이 같도록 상수 시간으로 비교한다. 설정이 비어 있으면 모든 요청을 거절한다
    private boolean matches(String candidate) {
        return secret.length > 0
                && MessageDigest.isEqual(secret, candidate.getBytes(StandardCharsets.UTF_8));
    }
}
