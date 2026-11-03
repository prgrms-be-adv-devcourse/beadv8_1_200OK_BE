import { test } from "node:test";
import assert from "node:assert/strict";
import { QUESTION_KEYS } from "./labels.ts";
import { buildReport, parseGold, renderMarkdown, type GoldMap, type ReportReview } from "./report.ts";
import type { Answers, ProviderId, RunRecord } from "./types.ts";

const T0 = Date.parse("2026-11-03T00:00:00.000Z");
const at = (ms: number) => new Date(T0 + ms).toISOString();

// 코드 7자리(overall taste freshness condition packaging delivery price)로 답과 정답을 짧게 쓴다.
const CODES: Record<string, string> = { p: "positive", n: "negative", o: "neutral", m: "mixed", "-": "not_mentioned" };
const answers = (codes: string): Answers =>
  Object.fromEntries(QUESTION_KEYS.map((k, i) => [k, CODES[codes[i]]])) as Answers;
const gold = (rows: Record<string, string>): GoldMap =>
  parseGold(Object.entries(rows).map(([id, codes]) => ({ id, ...Object.fromEntries(QUESTION_KEYS.map((k, i) => [k, codes[i]])) })));

type Over = Partial<{ repeat: number; concurrency: number; start: number; latency: number; attempts: number; costUsd: number; orderSeed: number; synthetic: boolean }>;

function base(provider: ProviderId, reviewId: string, o: Over) {
  const start = o.start ?? 0;
  return {
    runId: "t",
    provider,
    reviewId,
    repeat: o.repeat ?? 1,
    set: "eval" as const,
    synthetic: o.synthetic ?? true,
    model: `${provider}-model`,
    criteriaVersion: "0.2",
    orderSeed: o.orderSeed ?? 7,
    concurrency: o.concurrency ?? 1,
    startedAt: at(start),
    endedAt: at(start + (o.latency ?? 100)),
    attempts: o.attempts ?? 1,
  };
}

const ok = (provider: ProviderId, reviewId: string, codes: string, o: Over = {}): RunRecord => ({
  ...base(provider, reviewId, o),
  status: "ok",
  answers: answers(codes),
  usage: { inputTokens: 1000, outputTokens: 50 },
  priceDate: "2026-11-01",
  costUsd: o.costUsd ?? 0.001,
  extras: { provider, raw: null },
});

const failed = (provider: ProviderId, reviewId: string, o: Over = {}): RunRecord => ({
  ...base(provider, reviewId, o),
  status: "failed",
  error: { kind: "fatal", httpStatus: 400, message: "bad" },
});

const reviewsOf = (ids: string[], hard: string[] = []): ReportReview[] =>
  ids.map((id) => ({ id, synthetic: true, text: `${id} 본문`, hardCase: hard.includes(id) ? "B10" : null }));

function close(actual: number | null | undefined, expected: number): void {
  assert.ok(actual != null && Math.abs(actual - expected) < 1e-12, `${actual} != ${expected}`);
}

test("지연 시간은 재시도한 요청을 빼고 중앙값·p95를 낸다", () => {
  const records = [
    ok("jev", "R1", "ppppppp", { latency: 100 }),
    ok("jev", "R2", "ppppppp", { latency: 400 }),
    ok("jev", "R3", "ppppppp", { latency: 200 }),
    ok("jev", "R4", "ppppppp", { latency: 300 }),
    ok("jev", "R5", "ppppppp", { latency: 5000, attempts: 2, start: 1000 }),
  ];
  const [row] = buildReport({ records, reviews: reviewsOf(["R1", "R2", "R3", "R4", "R5"]), gold: null }).speed;
  // 한 번에 성공한 100·200·300·400ms만 쓴다. 중앙값 (200+300)/2 = 250, p95는 ceil(0.95×4)=4번째 값 400.
  assert.equal(row.medianMs, 250);
  assert.equal(row.p95Ms, 400);
  assert.equal(row.used, 4);
  assert.equal(row.excludedRetried, 1);
  // 처리 시간은 재시도 요청까지 포함한다: 가장 늦은 끝 1000+5000 − 가장 이른 시작 0.
  assert.equal(row.wallMs, 6000);
});

test("1,000건당 비용은 성공 기록 비용을 성공한 리뷰 수로 나눈다", () => {
  const records = [ok("jev", "R1", "ppppppp", { costUsd: 0.002 }), ok("jev", "R2", "ppppppp", { costUsd: 0.004 }), failed("jev", "R3")];
  const [r] = buildReport({ records, reviews: reviewsOf(["R1", "R2", "R3"]), gold: null }).reliability;
  // (0.002 + 0.004) / 2 × 1000 = 3
  close(r.costPer1000Reviews, 3);
  close(r.costUsd, 0.006);
});

test("실패한 기록은 품질에서 빠지고 실패율에는 들어간다", () => {
  const records = [ok("jev", "R1", "ppppppp"), failed("jev", "R2")];
  const data = buildReport({ records, reviews: reviewsOf(["R1", "R2"]), gold: gold({ R1: "ppppppp", R2: "nnnnnnn" }) });
  assert.equal(data.reliability[0].failureRate, 0.5);
  assert.equal(data.reliability[0].failedByKind.fatal, 1);
  const q = data.quality![0].all;
  assert.equal(q.reviews, 1);
  assert.equal(q.overall.accuracy.n, 1);
  assert.equal(q.overall.accuracy.value, 1);
});

test("항목 감성 Macro F1은 여섯 항목의 칸을 이어 붙여 한 번에 잰다", () => {
  const records = [ok("jev", "R1", "pp---n-"), ok("jev", "R2", "pp---p-")];
  const data = buildReport({ records, reviews: reviewsOf(["R1", "R2"]), gold: gold({ R1: "pp---n-", R2: "pp---n-" }) });
  const q = data.quality![0].all;
  // 언급된 칸: 맛 p→p, p→p, 배송 n→n, n→p.
  // 긍정 정밀도 2/3 재현율 1 → F1 0.8, 부정 정밀도 1 재현율 1/2 → F1 2/3. 평균 11/15.
  // 항목별 평균이었다면 맛 1, 배송 (2/3+0)/2 = 1/3 → 2/3이라 값이 다르다.
  close(q.pooledAspectSentiment.value, 11 / 15);
  assert.equal(q.pooledAspectSentiment.n, 4);
});

test("사람 정답의 ?는 판단 불가가 되고 개수만 센다", () => {
  const g = gold({ R1: "p?---n-" });
  assert.equal(g.get("R1")!.taste, "undecidable");
  const data = buildReport({ records: [ok("jev", "R1", "pp---n-")], reviews: reviewsOf(["R1"]), gold: g });
  assert.equal(data.quality![0].all.undecidable.taste, 1);
});

// 두 모델 모두 정답과 같고 부정 정답이 모든 판정 항목에 있다.
const PERFECT = { R1: "nnnn-n-", R2: "ppppppp" };

function bothSame(goldRows: Record<string, string>, jevOverride: Record<string, string> = {}, openaiOverride: Record<string, string> = {}) {
  const ids = Object.keys(goldRows);
  const records = ids.flatMap((id) => [ok("jev", id, jevOverride[id] ?? goldRows[id]), ok("openai", id, openaiOverride[id] ?? goldRows[id])]);
  return buildReport({ records, reviews: reviewsOf(ids), gold: gold(goldRows) });
}

test("세 기준을 모두 넘으면 Jev 채택", () => {
  const a = bothSame(PERFECT).adoption!;
  assert.equal(a.verdict, "Jev 채택");
  assert.equal(a.bothFailNote, null);
});

test("부정 정답이 없는 항목의 재현율은 판정 불가이고 통과로 치지 않는다", () => {
  // 신선도에 부정 정답이 없다.
  const data = bothSame({ R1: "nn-n-n-", R2: "ppppppp" });
  const freshness = data.adoption!.criterion2.find((c) => c.name.endsWith("freshness"))!;
  assert.equal(freshness.value, null);
  assert.equal(freshness.outcome, "undecidable");
  assert.equal(data.adoption!.verdict, "Jev 기준 미달");
  assert.match(renderMarkdown(data), /부정 재현율 freshness \| 판정 불가/);
});

test("기준 1·2는 넘고 OpenAI 대비만 못 넘으면 OpenAI가 낫지만 Jev도 쓸 만함", () => {
  const goldRows: Record<string, string> = {
    A1: "nnnn-n-", A2: "nnnn-n-", A3: "nnnn-n-", A4: "nnnn-n-",
    B1: "ppppppp", B2: "ppppppp", B3: "ppppppp", B4: "ppppppp",
    C1: "oppppp-", C2: "oppppp-",
  };
  // Jev는 중립 하나를 긍정으로 답한다. 전체 감성 F1 = (1 + 8/9 + 2/3) / 3 ≈ 0.85로 기준 1은 넘는다.
  // 10개 중 그 리뷰가 표본에서 빠질 확률이 0.9^10 ≈ 0.35라 차이 분포 하한은 −0.03보다 훨씬 아래다.
  const data = bothSame(goldRows, { C2: "pppppp-" });
  const a = data.adoption!;
  close(data.quality!.find((q) => q.provider === "jev")!.all.overall.macroF1.value, (1 + 8 / 9 + 2 / 3) / 3);
  assert.deepEqual(a.criterion1.map((c) => c.outcome), ["pass", "pass"]);
  assert.ok(a.criterion2.every((c) => c.outcome === "pass"));
  assert.equal(a.criterion3[0].outcome, "fail");
  assert.equal(a.verdict, "OpenAI가 낫지만 Jev도 쓸 만함: 비용·속도와 함께 판단");
  assert.equal(a.bothFailNote, null);
});

test("두 모델 모두 기준 1에 못 미치면 기준 미달과 함께 분류 기준을 먼저 고치라고 한다", () => {
  // 둘 다 전체 감성을 늘 긍정으로 답한다. 전체 감성 F1 = (긍정 2/3 + 부정 0) / 2 = 1/3.
  const data = bothSame(PERFECT, { R1: "pnnn-n-" }, { R1: "pnnn-n-" });
  const a = data.adoption!;
  close(a.criterion1[0].value, 1 / 3);
  assert.equal(a.verdict, "Jev 기준 미달");
  assert.equal(a.bothFailNote, "두 모델 모두 최소 기준 미달: 모델보다 분류 기준·질문을 먼저 고친다");
});

function pilotGate(hits: number) {
  const ids = Array.from({ length: 10 }, (_, i) => `R${i}`);
  const records = ids.map((id, i) => ok("jev", id, i < hits ? "ppppppp" : "nnnnnnn"));
  return buildReport({ records, reviews: reviewsOf(ids), gold: gold(Object.fromEntries(ids.map((id) => [id, "ppppppp"]))), pilot: true }).pilot!;
}

test("파일럿 관문은 Jev 전체 감성 정확도 0.4면 중단, 0.5면 통과", () => {
  assert.equal(pilotGate(4).gate, "중단: Jev 전체 감성 정확도 0.400 < 0.5");
  assert.match(pilotGate(5).gate, /^통과/);
  assert.deepEqual(pilotGate(5).meteringGaps, []);
});

test("파일럿 계측 점검은 가격 기준일이 빠진 성공 기록을 짚는다", () => {
  const record = { ...ok("jev", "R1", "ppppppp"), priceDate: "" } as RunRecord;
  const p = buildReport({ records: [record], reviews: reviewsOf(["R1"]), gold: gold({ R1: "ppppppp" }), pilot: true }).pilot!;
  assert.deepEqual(p.meteringGaps, [{ key: "jev/R1/1", missing: ["priceDate"] }]);
});

test("반복 실행 변화율은 반복된 칸 중 답이 바뀐 칸의 비율이고 순서 시드로 섞었는지 알아낸다", () => {
  const records = [
    ok("jev", "R1", "ppppppp", { repeat: 1, orderSeed: 1 }),
    ok("jev", "R1", "pnppppp", { repeat: 2, orderSeed: 2 }),
  ];
  const r = buildReport({ records, reviews: reviewsOf(["R1"]), gold: null }).repeats!;
  // 7칸 중 맛 한 칸만 바뀌었다.
  assert.equal(r.perProvider[0].cells, 7);
  assert.equal(r.perProvider[0].changed, 1);
  close(r.perProvider[0].changeRate, 1 / 7);
  assert.equal(r.orderPermuted, "per_repeat");

  const fixed = buildReport({
    records: [ok("jev", "R1", "ppppppp", { repeat: 1 }), ok("jev", "R1", "ppppppp", { repeat: 2 })],
    reviews: reviewsOf(["R1"]),
    gold: null,
  }).repeats!;
  assert.equal(fixed.orderPermuted, "fixed");
});

test("생성 리뷰가 있으면 보고서에 생성 데이터 기준 결과를 밝힌다", () => {
  const md = renderMarkdown(buildReport({ records: [ok("jev", "R1", "ppppppp")], reviews: reviewsOf(["R1"]), gold: null }));
  assert.match(md, /생성 데이터 기준 결과/);
});
