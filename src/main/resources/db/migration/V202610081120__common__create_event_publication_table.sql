-- Spring Modulith 이벤트 발행 기록 (spring-modulith-starter-jpa)
CREATE TABLE event_publication (
    id                     BINARY(16)    NOT NULL,
    listener_id            VARCHAR(512)  NOT NULL,
    event_type             VARCHAR(512)  NOT NULL,
    serialized_event       VARCHAR(4000) NOT NULL,
    publication_date       DATETIME(6)   NOT NULL,
    completion_date        DATETIME(6),
    completion_attempts    INT           NOT NULL,
    last_resubmission_date DATETIME(6),
    status                 ENUM ('COMPLETED', 'FAILED', 'PROCESSING', 'PUBLISHED', 'RESUBMITTED'),
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
