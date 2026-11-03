export type CallOutcome =
  | { kind: "ok"; body: string; attempts: number }
  | { kind: "fatal" | "retryable_exhausted"; httpStatus?: number; message: string; attempts: number };

export type RetryOptions = { maxRetries: number; baseDelayMs: number; sleep: (ms: number) => Promise<void> };

// 429·5xx·네트워크 오류만 다시 시도한다. 나머지 4xx는 요청이 틀린 것이라 다시 보내도 같은 답이 온다.
export async function callWithRetry(send: () => Promise<Response>, opts: RetryOptions): Promise<CallOutcome> {
  let last: { httpStatus?: number; message: string } = { message: "" };
  for (let attempt = 1; attempt <= opts.maxRetries + 1; attempt++) {
    if (attempt > 1) await opts.sleep(opts.baseDelayMs * 2 ** (attempt - 2));
    let res: Response;
    try {
      res = await send();
    } catch (e) {
      last = { message: `네트워크 오류: ${e instanceof Error ? e.message : String(e)}` };
      continue;
    }
    const body = await res.text();
    if (res.ok) return { kind: "ok", body, attempts: attempt };
    last = { httpStatus: res.status, message: `HTTP ${res.status}: ${body.slice(0, 500)}` };
    if (res.status !== 429 && res.status < 500) return { kind: "fatal", ...last, attempts: attempt };
  }
  return { kind: "retryable_exhausted", ...last, attempts: opts.maxRetries + 1 };
}
