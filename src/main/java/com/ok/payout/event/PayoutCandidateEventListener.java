package com.ok.payout.event;

import com.ok.common.event.OrderProductConfirmedEvent;
import com.ok.payout.service.PayoutCandidateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PayoutCandidateEventListener {
    private final PayoutCandidateService payoutCandidateService;

    @ApplicationModuleListener
    void on(OrderProductConfirmedEvent event) {
        payoutCandidateService.createFromPurchaseConfirmed(event);
    }
}
