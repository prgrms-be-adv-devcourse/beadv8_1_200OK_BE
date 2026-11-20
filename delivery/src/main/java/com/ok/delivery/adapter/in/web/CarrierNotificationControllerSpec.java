package com.ok.delivery.adapter.in.web;

import com.ok.delivery.adapter.in.web.dto.CarrierNotificationRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "Carrier Notification", description = "택배사가 호출하는 배송 상태 변경 알림 API (로그인 없음)")
interface CarrierNotificationControllerSpec {

    @Operation(summary = "택배사 배송 상태 변경 알림 수신", description = """
            택배사가 송장의 배송 상태가 바뀔 때마다 호출합니다. 인증 헤더가 없어, 알림 등록 때 택배사에 알려 준 주소의 끝 경로(비밀값)로 확인합니다.
            - 알림에는 상태가 없어 택배사에 다시 조회하고, 배송 완료가 확인된 경우에만 배송 완료로 반영합니다.
            - 택배사는 2XX가 아니면 알림을 다시 보내므로, 모르는 송장, 이미 배송 완료인 송장, 아직 완료가 아닌 상태, 택배사 조회 오류도 204로 답합니다.
            - 같은 알림이 여러 번 와도 결과가 같습니다.
            - 비밀값이 틀리면 403으로 거절합니다.
            """)
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "알림을 받음 (반영 여부와 무관)"),
            @ApiResponse(responseCode = "403", description = "경로의 비밀값이 틀림")
    })
    ResponseEntity<Void> receive(String secret, CarrierNotificationRequest request);
}
