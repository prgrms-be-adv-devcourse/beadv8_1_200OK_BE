import { existsSync, readFileSync } from "node:fs";
import { readCsvRecords } from "./csv.ts";
import { callWithRetry } from "./http.ts";
import { validateRow } from "./labels.ts";
import { choiceOrder, orderSeed } from "./order.ts";
import { costUsd, InvalidResponseError, type Provider } from "./providers.ts";
import { keyOf, openStore, pendingKeys } from "./store.ts";
import type { Criteria, ProviderId, RunKey, RunRecord } from "./types.ts";

export type Review = {
  id: string;
  set: "tuning" | "eval" | "extra";
  synthetic: boolean;
  product: string;
  text: string;
  repeatRun: boolean;
  // 생성할 때 노린 어려운 사례 유형. 보고서가 어려운 사례와 일반 리뷰를 나눠 보는 데 쓴다.
  hardCase: string | null;
};

export function loadReviews(path: URL | string): Review[] {
  return readFileSync(path, "utf8")
    .split("\n")
    .filter((l) => l.trim())
    .map((l) => {
      const r = JSON.parse(l) as Review;
      return { id: r.id, set: r.set, synthetic: r.synthetic, product: r.product, text: r.text, repeatRun: r.repeatRun, hardCase: r.hardCase ?? null };
    });
}

// 평가용 리뷰는 사람 정답을 다 매기기 전에는 어떤 모델에도 보내지 않는다. 모델 답을 본 뒤 매긴 정답은 정답이 아니다.
export function evalGoldErrors(goldPath: string, evalIds: string[]): string[] {
  if (!existsSync(goldPath)) return [`${goldPath}가 없다`];
  const rows = readCsvRecords(readFileSync(goldPath, "utf8"));
  const errors = rows.flatMap(validateRow);
  const labeled = new Set(rows.map((r) => r.id));
  const missing = evalIds.filter((id) => !labeled.has(id));
  if (missing.length > 0) errors.push(`정답이 없는 평가용 리뷰 ${missing.length}개: ${missing.slice(0, 10).join(", ")}`);
  return errors;
}

export type RunOptions = {
  runId: string;
  criteria: Criteria;
  providers: Provider[];
  reviews: Review[];
  repeats: number;
  permuteOrderPerRepeat: boolean;
  seed: number;
  concurrency: number;
  apiKeys: Partial<Record<ProviderId, string>>;
  storePath: string;
  goldEvalPath: string;
  fetch: typeof fetch;
  sleep: (ms: number) => Promise<void>;
  now: () => Date;
  baseDelayMs?: number;
};

export type ProviderSummary = { ok: number; failed: number; costUsd: number };
export type RunSummary = {
  planned: number;
  sent: number;
  skippedLines: number;
  byProvider: Partial<Record<ProviderId, ProviderSummary>>;
  wallMs: number;
};

// 어느 지점에서 죽었든 같은 명령을 다시 돌리면 마지막 기록이 ok가 아닌 키만 보낸다.
export async function runBench(opts: RunOptions): Promise<RunSummary> {
  const evalIds = opts.reviews.filter((r) => r.set === "eval").map((r) => r.id);
  if (evalIds.length > 0) {
    const errors = evalGoldErrors(opts.goldEvalPath, evalIds);
    if (errors.length > 0) {
      throw new Error(`평가용 세트는 사람 정답을 다 매기기 전에 모델에 보내지 않는다.\n${errors.slice(0, 20).join("\n")}`);
    }
  }
  for (const p of opts.providers) {
    if (!opts.apiKeys[p.id]) throw new Error(`${p.apiKeyEnv} 환경 변수가 없다. ${p.id}를 돌리려면 설정한다.`);
  }

  type Job = { key: RunKey; review: Review; provider: Provider };
  const jobs: Job[] = [];
  for (const review of opts.reviews) {
    for (let repeat = 1; repeat <= opts.repeats; repeat++) {
      for (const provider of opts.providers) {
        jobs.push({ key: { runId: opts.runId, provider: provider.id, reviewId: review.id, repeat }, review, provider });
      }
    }
  }

  const store = openStore(opts.storePath);
  const pending = pendingKeys(
    jobs.map((j) => ({ ...j.key, job: j })),
    store.latest,
  ).map((k) => k.job);

  // 요청을 모두 먼저 만들어 둔다. 형식이 검증되지 않은 제공자는 네트워크 호출 전에 멈춘다.
  const prepared = pending.map((job) => {
    const seed = orderSeed(opts.seed, job.key.reviewId, job.key.repeat, opts.permuteOrderPerRepeat);
    const order = choiceOrder(opts.criteria, seed);
    const request = job.provider.buildRequest(opts.criteria, order, job.review, opts.apiKeys[job.provider.id]!);
    return { job, seed, request };
  });

  const started = opts.now();
  let next = 0;
  const worker = async () => {
    while (next < prepared.length) {
      const { job, seed, request } = prepared[next++];
      const startedAt = opts.now().toISOString();
      const outcome = await callWithRetry(() => opts.fetch(request.url, request.init), {
        maxRetries: 3,
        baseDelayMs: opts.baseDelayMs ?? 1000,
        sleep: opts.sleep,
      });
      const base = {
        ...job.key,
        set: job.review.set,
        synthetic: job.review.synthetic,
        model: job.provider.model,
        criteriaVersion: opts.criteria.version,
        orderSeed: seed,
        concurrency: opts.concurrency,
        startedAt,
        attempts: outcome.attempts,
      };
      store.append(toRecord(base, outcome, job.provider, opts));
    }
  };
  await Promise.all(Array.from({ length: Math.min(opts.concurrency, prepared.length) }, worker));
  const wallMs = opts.now().getTime() - started.getTime();

  const byProvider: RunSummary["byProvider"] = {};
  for (const job of jobs) {
    const record = store.latest.get(keyOf(job.key));
    const s = (byProvider[job.provider.id] ??= { ok: 0, failed: 0, costUsd: 0 });
    if (record?.status === "ok") {
      s.ok++;
      s.costUsd += record.costUsd;
    } else if (record) s.failed++;
  }
  return { planned: jobs.length, sent: prepared.length, skippedLines: store.skippedLines, byProvider, wallMs };
}

type Base = Omit<Extract<RunRecord, { status: "ok" }>, "status" | "answers" | "usage" | "priceDate" | "costUsd" | "extras" | "endedAt">;

function toRecord(
  base: Base,
  outcome: Awaited<ReturnType<typeof callWithRetry>>,
  provider: Provider,
  opts: RunOptions,
): RunRecord {
  const endedAt = opts.now().toISOString();
  if (outcome.kind !== "ok") {
    const { kind, httpStatus, message } = outcome;
    return { ...base, endedAt, status: "failed", error: { kind, httpStatus, message } };
  }
  try {
    const parsed = provider.parseResponse(JSON.parse(outcome.body), opts.criteria);
    return {
      ...base,
      endedAt,
      status: "ok",
      ...parsed,
      priceDate: provider.priceDate,
      costUsd: costUsd(parsed.usage, provider.price),
    };
  } catch (e) {
    if (!(e instanceof InvalidResponseError) && !(e instanceof SyntaxError)) throw e;
    return { ...base, endedAt, status: "failed", error: { kind: "invalid_response", message: e.message } };
  }
}
