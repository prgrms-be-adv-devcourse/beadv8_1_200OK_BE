package com.ok.carriersimulator.web.graphql;

import com.ok.carriersimulator.core.Admission;
import com.ok.carriersimulator.core.CarrierSimulator;
import com.ok.carriersimulator.core.Operation;
import com.ok.carriersimulator.core.ParcelKey;
import com.ok.carriersimulator.core.StatusCode;
import com.ok.carriersimulator.core.TrackResult;
import com.ok.carriersimulator.core.TrackingEvent;
import com.ok.carriersimulator.web.graphql.SimulatorGraphQlException.Code;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.ContextValue;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

/**
 * Delivery Tracker의 GraphQL 연산 중 delivery가 쓰는 두 가지(조회, 알림 등록)에 답한다.
 */
@Controller
class TrackGraphQlController {

    private static final String BEARER = "Bearer ";

    private final CarrierSimulator simulator;
    private final Clock clock;

    TrackGraphQlController(CarrierSimulator simulator, Clock clock) {
        this.simulator = simulator;
        this.clock = clock;
    }

    @QueryMapping
    TrackInfoView track(@Argument String carrierId, @Argument String trackingNumber,
                        @ContextValue(name = GraphQlConfig.AUTHORIZATION_CONTEXT_KEY, required = false) String authorization) {
        admit(authorization, Operation.TRACK);
        ParcelKey key = keyOf(carrierId, trackingNumber);
        TrackResult result = simulator.track(key, clock.instant());
        if (!(result instanceof TrackResult.Found found)) {
            throw new SimulatorGraphQlException(Code.NOT_FOUND, "송장을 찾을 수 없습니다.");
        }
        return new TrackInfoView(trackingNumber, found.events().stream().map(TrackEventView::from).toList(), null, null);
    }

    @MutationMapping
    Boolean registerTrackWebhook(@Argument RegisterInput input,
                                 @ContextValue(name = GraphQlConfig.AUTHORIZATION_CONTEXT_KEY, required = false) String authorization) {
        admit(authorization, Operation.REGISTER);
        ParcelKey key = keyOf(input.carrierId(), input.trackingNumber());
        String callbackUrl = input.callbackUrl();
        if (callbackUrl == null || !(callbackUrl.startsWith("http://") || callbackUrl.startsWith("https://"))) {
            throw new SimulatorGraphQlException(Code.BAD_REQUEST, "callbackUrl은 http 또는 https 주소여야 합니다.");
        }
        simulator.registerWebhook(key, callbackUrl, input.expirationTime().toInstant(), clock.instant());
        return true;
    }

    @SchemaMapping(typeName = "TrackInfo", field = "lastEvent")
    TrackEventView lastEvent(TrackInfoView trackInfo) {
        return trackInfo.events().isEmpty() ? null : trackInfo.events().getLast();
    }

    // Relay 방식의 쪽 나눔이다. cursor는 시각 순 이력에서의 순번이다
    @SchemaMapping(typeName = "TrackInfo", field = "events")
    EventConnectionView events(TrackInfoView trackInfo, @Argument Integer first, @Argument String after,
                               @Argument Integer last, @Argument String before) {
        if ((first != null && first < 0) || (last != null && last < 0)) {
            throw new SimulatorGraphQlException(Code.BAD_REQUEST, "first와 last는 0 이상이어야 합니다.");
        }
        List<TrackEventView> all = trackInfo.events();
        int from = after == null ? 0 : Math.min(all.size(), cursorIndex(after) + 1);
        int to = before == null ? all.size() : Math.clamp(cursorIndex(before), from, all.size());
        if (first != null) {
            to = Math.min(to, from + first);
        }
        if (last != null) {
            from = Math.max(from, to - last);
        }
        List<EdgeView> edges = new ArrayList<>();
        for (int index = from; index < to; index++) {
            edges.add(new EdgeView(all.get(index), String.valueOf(index)));
        }
        PageInfoView pageInfo = new PageInfoView(to < all.size(), from > 0,
                edges.isEmpty() ? null : edges.getFirst().cursor(),
                edges.isEmpty() ? null : edges.getLast().cursor());
        return new EventConnectionView(edges, pageInfo);
    }

    // 지연과 거절을 core가 정한다. 거절이어도 지연은 지킨다
    private void admit(String authorization, Operation operation) {
        String token = authorization != null && authorization.startsWith(BEARER)
                ? authorization.substring(BEARER.length()).strip()
                : null;
        Admission admission = simulator.admit(token, operation, clock.instant());
        if (admission.delayMillis() > 0) {
            try {
                Thread.sleep(admission.delayMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        switch (admission.rejection()) {
            case UNAUTHENTICATED -> throw new SimulatorGraphQlException(Code.UNAUTHENTICATED,
                    "토큰이 없거나 만료됐습니다.");
            case INTERNAL -> throw new SimulatorGraphQlException(Code.INTERNAL, "일부러 넣은 서버 오류입니다.");
            case NONE -> {
            }
        }
    }

    private static ParcelKey keyOf(String carrierId, String trackingNumber) {
        if (carrierId == null || carrierId.isBlank() || trackingNumber == null || trackingNumber.isBlank()) {
            throw new SimulatorGraphQlException(Code.BAD_REQUEST, "carrierId와 trackingNumber는 비어 있을 수 없습니다.");
        }
        return new ParcelKey(carrierId, trackingNumber);
    }

    // 이 서버가 내보내는 cursor는 0부터 이어지는 순번이다. 그 밖의 값은 내보낸 적 없는 cursor다.
    // 가장 큰 정수도 막는다. 거기에 1을 더하면 넘쳐서 음수가 되기 때문이다
    private static int cursorIndex(String cursor) {
        try {
            int index = Integer.parseInt(cursor);
            if (index >= 0 && index < Integer.MAX_VALUE) {
                return index;
            }
        } catch (NumberFormatException e) {
            // 아래에서 같은 오류로 답한다
        }
        throw new SimulatorGraphQlException(Code.BAD_REQUEST, "알 수 없는 cursor입니다.");
    }

    record RegisterInput(String carrierId, String trackingNumber, String callbackUrl, OffsetDateTime expirationTime) {
    }

    // sender, recipient는 형식만 맞추고 값은 채우지 않는다
    record TrackInfoView(String trackingNumber, List<TrackEventView> events, ContactView sender, ContactView recipient) {
    }

    record TrackEventView(StatusView status, Instant time, LocationView location, ContactView contact, String description) {

        static TrackEventView from(TrackingEvent event) {
            LocationView location = event.location() == null ? null : new LocationView("KR", null, event.location());
            return new TrackEventView(new StatusView(event.status(), event.status().displayName()), event.time(),
                    location, null, event.description());
        }
    }

    record StatusView(StatusCode code, String name) {
    }

    record LocationView(String countryCode, String postalCode, String name) {
    }

    record ContactView(String name, LocationView location, String phoneNumber) {
    }

    record EventConnectionView(List<EdgeView> edges, PageInfoView pageInfo) {
    }

    record EdgeView(TrackEventView node, String cursor) {
    }

    record PageInfoView(boolean hasNextPage, boolean hasPreviousPage, String startCursor, String endCursor) {
    }
}
