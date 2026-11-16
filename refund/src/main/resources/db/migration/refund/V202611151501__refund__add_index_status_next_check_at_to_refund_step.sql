-- 다시 진행 알람(#576)이 "다시 볼 시각이 지난 줄"(할 차례·시도 중이면서 next_check_at <= 지금)을 찾는 조회용 인덱스.
-- 상태를 앞에 두는 이유: 끝난 줄(성공·거절됨·멈춤, next_check_at = NULL)이 대부분이라 상태로 먼저 좁히면 볼 행이 적다
CREATE INDEX idx_refund_step_status_next_check_at ON refund_step (status, next_check_at);
