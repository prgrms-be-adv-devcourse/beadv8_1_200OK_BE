package com.ok.wallet.application.service;

import com.ok.wallet.application.dto.MessageFailureCommand;
import com.ok.wallet.application.port.in.MessageFailureUseCase;
import com.ok.wallet.application.port.out.WalletMessageFailureRepository;
import com.ok.wallet.domain.WalletMessageFailure;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;

/**
 * 지갑이 끝내 처리하지 못한 메시지를 실패 기록 표에 남긴다.
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class MessageFailureService implements MessageFailureUseCase {

    private final WalletMessageFailureRepository walletMessageFailureRepository;

    @Override
    @Transactional
    public void record(MessageFailureCommand command) {
        // 기록은 했는데 오프셋을 옮기기 전에 멈추면 같은 메시지가 다시 실패해 여기로 온다.
        // 한 파티션의 메시지는 한 번에 한 곳에서만 처리하므로 확인과 넣기 사이에 같은 메시지가 끼어들지 않는다
        Optional<WalletMessageFailure> recorded =
                walletMessageFailureRepository.find(command.topic(), command.partition(), command.offset());
        if (recorded.isPresent()) {
            warnIfDifferentMessage(recorded.get(), command);
            return;
        }
        walletMessageFailureRepository.save(WalletMessageFailure.record(command.topic(), command.partition(),
                command.offset(), command.key(), command.payload(), command.failureReason()));
        // 메시지를 건너뛰는 대신 기록으로 넘긴 지점이라, 사람이 확인하도록 표시를 붙인 ERROR로 한 번만 남긴다.
        // 흐름 로깅(#375)과 알림은 이 표시로 지갑 실패를 찾는다
        log.error("[WALLET_MESSAGE_FAILED] 처리하지 못한 메시지를 실패 기록에 남겼습니다. topic={}, partition={}, offset={}, key={}, reason={}",
                command.topic(), command.partition(), command.offset(), command.key(), command.failureReason());
    }

    // 원문이 같으면 같은 메시지를 다시 받은 것이다.
    // 원문이 다르면 토픽을 새로 만들었거나 오프셋이 처음부터 다시 매겨져(Kafka 볼륨만 지운 경우 등) 다른 메시지가 같은 위치에 온 것이다.
    // 유니크 제약 때문에 새로 남길 수 없으므로, 묻히지 않게 같은 표시의 ERROR 로그에 원문까지 남긴다
    private static void warnIfDifferentMessage(WalletMessageFailure recorded, MessageFailureCommand command) {
        if (Objects.equals(recorded.getPayload(), command.payload())) {
            log.info("이미 기록된 실패 메시지라 다시 기록하지 않습니다. topic={}, partition={}, offset={}",
                    command.topic(), command.partition(), command.offset());
            return;
        }
        log.error("[WALLET_MESSAGE_FAILED] 같은 위치에 다른 메시지의 실패 기록이 있어 새로 남기지 못했습니다. "
                        + "topic={}, partition={}, offset={}, key={}, reason={}, payload={}",
                command.topic(), command.partition(), command.offset(), command.key(), command.failureReason(),
                command.payload());
    }
}
