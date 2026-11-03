// 실행 기록·리뷰·사람 정답으로 보고서 숫자를 계산하고 마크다운으로 그린다. 파일을 읽고 쓰지 않는다.
import { CODE_TO_LABEL, normalizeCode, QUESTION_KEYS, validateRow, type QuestionKey } from "./labels.ts";
import {
  accuracy,
  agreementRate,
  aspectSentimentMacroF1,
  confusionMatrix,
  macroF1,
  mentionPRF,
  negativeRecall,
  pairedBootstrapDiff,
  type Accuracy,
  type Agreement,
  type AspectSentimentF1,
  type BootstrapDiff,
  type ConfusionMatrix,
  type GoldLabel,
  type MacroF1,
  type MentionPRF,
  type NegativeRecall,
} from "./metrics.ts";
import type { Review } from "./runner.ts";
import type { Answers, AspectLabel, ProviderId, RunRecord } from "./types.ts";

type OkRecord = Extract<RunRecord, { status: "ok" }>;
type ErrorKind = Extract<RunRecord, { status: "failed" }>["error"]["kind"];

export type ReportReview = Pick<Review, "id" | "synthetic" | "text" | "hardCase">;
export type GoldRow = Record<QuestionKey, GoldLabel>;
export type GoldMap = Map<string, GoldRow>;

const PROVIDERS: readonly ProviderId[] = ["jev", "openai"];
const ASPECT_KEYS = QUESTION_KEYS.filter((k) => k !== "overall") as Exclude<QuestionKey, "overall">[];
type AspectKey = (typeof ASPECT_KEYS)[number];
const NEGATIVE_RECALL_KEYS = ["overall", "taste", "freshness", "condition", "delivery"] as const;
type NegativeRecallKey = (typeof NEGATIVE_RECALL_KEYS)[number];
const SENTIMENTS: readonly AspectLabel[] = ["positive", "negative", "neutral", "mixed"];
const ERROR_KINDS: readonly ErrorKind[] = ["retryable_exhausted", "fatal", "invalid_response"];

// 결과를 보기 전에 고정한 채택 기준이다. 결과를 본 뒤 이 숫자를 바꾸면 기준이 아니라 결과 맞추기가 된다.
export const ADOPTION_THRESHOLDS = {
  overallMacroF1: 0.75,
  pooledAspectSentimentMacroF1: 0.7,
  negativeRecall: 0.85,
  bootstrapLowerBound: -0.03,
  bootstrapIterations: 2000,
} as const;
export const PILOT_MIN_ACCURACY = 0.5;
export const DEFAULT_BOOTSTRAP_SEED = 20261103;
const MAX_DISAGREEMENTS = 20;

export function parseGold(rows: Record<string, string>[]): GoldMap {
  const errors = rows.flatMap(validateRow);
  if (errors.length > 0) throw new Error(`사람 정답 시트에 잘못된 코드가 있다.\n${errors.slice(0, 20).join("\n")}`);
  return new Map(
    rows.map((row) => [
      row.id,
      Object.fromEntries(QUESTION_KEYS.map((k) => [k, CODE_TO_LABEL[normalizeCode(row[k])] as GoldLabel])) as GoldRow,
    ]),
  );
}

// 1. 실행 정보

export type RunInfo = {
  runIds: string[];
  models: Partial<Record<ProviderId, string[]>>;
  criteriaVersions: string[];
  sets: string[];
  reviewCount: number;
  repeats: number;
  concurrencies: number[];
  orderSeedCount: number;
  priceDates: string[];
  synthetic: boolean;
};

const distinct = <T>(items: Iterable<T>): T[] => [...new Set(items)].sort();

function runInfo(records: RunRecord[]): RunInfo {
  const ok = records.filter(isOk);
  return {
    runIds: distinct(records.map((r) => r.runId)),
    models: Object.fromEntries(
      providersIn(records).map((p) => [p, distinct(records.filter((r) => r.provider === p).map((r) => r.model))]),
    ),
    criteriaVersions: distinct(records.map((r) => r.criteriaVersion)),
    sets: distinct(records.map((r) => r.set)),
    reviewCount: new Set(records.map((r) => r.reviewId)).size,
    repeats: Math.max(0, ...records.map((r) => r.repeat)),
    concurrencies: distinct(records.map((r) => r.concurrency)),
    orderSeedCount: new Set(records.map((r) => r.orderSeed)).size,
    priceDates: distinct(ok.map((r) => r.priceDate)),
    synthetic: records.some((r) => r.synthetic),
  };
}

// 2. 안정성·비용

export type ProviderReliability = {
  provider: ProviderId;
  keys: number;
  ok: number;
  failedByKind: Record<ErrorKind, number>;
  failureRate: number;
  retryRate: number;
  costUsd: number;
  costPer1000Reviews: number | null;
  inputTokens: number;
  outputTokens: number;
};

function reliability(records: RunRecord[]): ProviderReliability[] {
  return providersIn(records).map((provider) => {
    const mine = records.filter((r) => r.provider === provider);
    const ok = mine.filter(isOk);
    const failedByKind = Object.fromEntries(ERROR_KINDS.map((k) => [k, 0])) as Record<ErrorKind, number>;
    for (const r of mine) if (r.status === "failed") failedByKind[r.error.kind]++;
    const costUsd = sum(ok.map((r) => r.costUsd));
    const reviews = new Set(ok.map((r) => r.reviewId)).size;
    return {
      provider,
      keys: mine.length,
      ok: ok.length,
      failedByKind,
      failureRate: (mine.length - ok.length) / mine.length,
      retryRate: mine.filter((r) => r.attempts > 1).length / mine.length,
      costUsd,
      costPer1000Reviews: reviews === 0 ? null : (costUsd / reviews) * 1000,
      inputTokens: sum(ok.map((r) => r.usage.inputTokens)),
      outputTokens: sum(ok.map((r) => r.usage.outputTokens)),
    };
  });
}

// 3. 속도

export type SpeedRow = {
  provider: ProviderId;
  concurrency: number;
  medianMs: number | null;
  p95Ms: number | null;
  used: number;
  excludedRetried: number;
  wallMs: number;
};

function speed(records: RunRecord[]): SpeedRow[] {
  const rows: SpeedRow[] = [];
  for (const provider of providersIn(records)) {
    for (const concurrency of distinct(records.filter((r) => r.provider === provider).map((r) => r.concurrency))) {
      const group = records.filter((r) => r.provider === provider && r.concurrency === concurrency);
      const ok = group.filter(isOk);
      // 재시도한 요청의 시간에는 백오프 대기가 들어 있어 모델 응답 시간이 아니다.
      const latencies = ok.filter((r) => r.attempts === 1).map(latencyMs).sort((a, b) => a - b);
      rows.push({
        provider,
        concurrency,
        medianMs: median(latencies),
        p95Ms: latencies.length === 0 ? null : latencies[Math.ceil(0.95 * latencies.length) - 1],
        used: latencies.length,
        excludedRetried: ok.length - latencies.length,
        wallMs: Math.max(...group.map((r) => Date.parse(r.endedAt))) - Math.min(...group.map((r) => Date.parse(r.startedAt))),
      });
    }
  }
  return rows;
}

const latencyMs = (r: RunRecord) => Date.parse(r.endedAt) - Date.parse(r.startedAt);

function median(sorted: number[]): number | null {
  if (sorted.length === 0) return null;
  const mid = sorted.length >> 1;
  return sorted.length % 2 === 1 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
}

// 4. 품질

type Graded = { review: ReportReview; gold: GoldRow; answers: Answers };

export type QualitySlice = {
  reviews: number;
  overall: { accuracy: Accuracy; macroF1: MacroF1; confusion: ConfusionMatrix };
  aspects: { key: AspectKey; mention: MentionPRF; sentiment: AspectSentimentF1 }[];
  pooledAspectSentiment: AspectSentimentF1;
  negativeRecall: Record<NegativeRecallKey, NegativeRecall>;
  undecidable: Record<QuestionKey, number>;
};
export type ProviderQuality = { provider: ProviderId; all: QualitySlice; hard: QualitySlice; general: QualitySlice };

// 품질은 첫 반복만 본다. 반복 결과까지 넣으면 반복 대상 리뷰만 가중치가 커진다.
function graded(records: RunRecord[], reviews: Map<string, ReportReview>, gold: GoldMap, provider: ProviderId): Graded[] {
  return records
    .filter((r): r is OkRecord => isOk(r) && r.provider === provider && r.repeat === 1 && gold.has(r.reviewId))
    .map((r) => ({ review: reviews.get(r.reviewId) ?? missingReview(r.reviewId), gold: gold.get(r.reviewId)!, answers: r.answers }))
    .sort((a, b) => a.review.id.localeCompare(b.review.id));
}

function qualitySlice(items: Graded[]): QualitySlice {
  const goldOf = (k: QuestionKey) => items.map((i) => i.gold[k]);
  const predOf = (k: QuestionKey) => items.map((i) => i.answers[k] as AspectLabel);
  return {
    reviews: items.length,
    overall: {
      accuracy: accuracy(goldOf("overall"), predOf("overall")),
      macroF1: macroF1(goldOf("overall"), predOf("overall")),
      confusion: confusionMatrix(goldOf("overall"), predOf("overall"), SENTIMENTS),
    },
    aspects: ASPECT_KEYS.map((key) => ({
      key,
      mention: mentionPRF(goldOf(key), predOf(key)),
      sentiment: aspectSentimentMacroF1(goldOf(key), predOf(key)),
    })),
    pooledAspectSentiment: pooledAspectSentiment(items),
    negativeRecall: Object.fromEntries(
      NEGATIVE_RECALL_KEYS.map((k) => [k, negativeRecall(goldOf(k), predOf(k))]),
    ) as Record<NegativeRecallKey, NegativeRecall>,
    undecidable: Object.fromEntries(
      QUESTION_KEYS.map((k) => [k, goldOf(k).filter((g) => g === "undecidable").length]),
    ) as Record<QuestionKey, number>,
  };
}

// 채택 기준의 항목 감성 숫자다. 항목별 F1을 평균 내지 않고 여섯 항목의 칸을 이어 붙여 한 번에 잰다.
// 항목별 평균은 칸이 몇 개 없는 항목(가격 등)이 맛과 같은 무게를 받아 흔들린다.
function pooledAspectSentiment(items: Graded[]): AspectSentimentF1 {
  const gold = ASPECT_KEYS.flatMap((k) => items.map((i) => i.gold[k]));
  const pred = ASPECT_KEYS.flatMap((k) => items.map((i) => i.answers[k] as AspectLabel));
  return aspectSentimentMacroF1(gold, pred);
}

function quality(records: RunRecord[], reviews: Map<string, ReportReview>, gold: GoldMap): ProviderQuality[] {
  return providersIn(records).map((provider) => {
    const items = graded(records, reviews, gold, provider);
    return {
      provider,
      all: qualitySlice(items),
      hard: qualitySlice(items.filter((i) => i.review.hardCase !== null)),
      general: qualitySlice(items.filter((i) => i.review.hardCase === null)),
    };
  });
}

// 5. 두 모델 비교

export type Disagreement = {
  reviewId: string;
  question: QuestionKey;
  jev: string;
  openai: string;
  gold: GoldLabel | null;
  text: string;
};
export type Comparison = {
  reviews: number;
  overall: Agreement;
  perQuestion: Record<QuestionKey, Agreement>;
  disagreements: Disagreement[];
};

type Pair = { reviewId: string; jev: Answers; openai: Answers };

// 둘 다 성공한 리뷰만 짝짓는다. 한쪽 실패를 불일치로 세면 일치율이 안정성 숫자와 섞인다.
function pairs(records: RunRecord[]): Pair[] {
  const byProvider = (p: ProviderId) =>
    new Map(records.filter((r): r is OkRecord => isOk(r) && r.provider === p && r.repeat === 1).map((r) => [r.reviewId, r.answers]));
  const jev = byProvider("jev");
  const openai = byProvider("openai");
  return [...jev.keys()]
    .filter((id) => openai.has(id))
    .sort()
    .map((reviewId) => ({ reviewId, jev: jev.get(reviewId)!, openai: openai.get(reviewId)! }));
}

function comparison(paired: Pair[], reviews: Map<string, ReportReview>, gold: GoldMap | null): Comparison {
  const of = (side: "jev" | "openai", keys: readonly QuestionKey[]) =>
    keys.flatMap((k) => paired.map((p) => p[side][k] as AspectLabel));
  const disagreements: Disagreement[] = [];
  for (const p of paired) {
    for (const question of QUESTION_KEYS) {
      if (p.jev[question] === p.openai[question]) continue;
      disagreements.push({
        reviewId: p.reviewId,
        question,
        jev: p.jev[question],
        openai: p.openai[question],
        gold: gold?.get(p.reviewId)?.[question] ?? null,
        text: truncate(reviews.get(p.reviewId)?.text ?? "", 60),
      });
    }
  }
  return {
    reviews: paired.length,
    overall: agreementRate(of("jev", QUESTION_KEYS), of("openai", QUESTION_KEYS)),
    perQuestion: Object.fromEntries(QUESTION_KEYS.map((k) => [k, agreementRate(of("jev", [k]), of("openai", [k]))])) as Record<
      QuestionKey,
      Agreement
    >,
    disagreements: disagreements.slice(0, MAX_DISAGREEMENTS),
  };
}

// 6. 반복 실행·선택지 순서

export type RepeatStability = {
  perProvider: { provider: ProviderId; cells: number; changed: number; changeRate: number | null }[];
  orderPermuted: "per_repeat" | "fixed" | "unknown";
};

function repeatStability(records: RunRecord[]): RepeatStability {
  const perProvider = providersIn(records).map((provider) => {
    const byReview = groupBy(
      records.filter((r): r is OkRecord => isOk(r) && r.provider === provider),
      (r) => r.reviewId,
    );
    let cells = 0;
    let changed = 0;
    for (const runs of byReview.values()) {
      if (runs.length < 2) continue;
      for (const k of QUESTION_KEYS) {
        cells++;
        if (new Set(runs.map((r) => r.answers[k])).size > 1) changed++;
      }
    }
    return { provider, cells, changed, changeRate: cells === 0 ? null : changed / cells };
  });
  // 실행 인자는 기록에 없으므로, 같은 리뷰의 반복끼리 순서 시드가 다른지로 순서를 섞었는지 알아낸다.
  const repeated = [...groupBy(records, (r) => r.reviewId).values()].filter((rs) => new Set(rs.map((r) => r.repeat)).size > 1);
  const orderPermuted =
    repeated.length === 0 ? "unknown" : repeated.some((rs) => new Set(rs.map((r) => r.orderSeed)).size > 1) ? "per_repeat" : "fixed";
  return { perProvider, orderPermuted };
}

// 7. 채택 판정

export type Outcome = "pass" | "fail" | "undecidable";
export type Check = { name: string; value: number | null; threshold: string; outcome: Outcome };
export type Adoption = {
  criterion1: Check[];
  criterion2: Check[];
  criterion3: Check[];
  bootstrap: { reviews: number; overall: BootstrapDiff | null; pooled: BootstrapDiff | null; seed: number };
  openaiCriterion1: Check[];
  verdict: string;
  bothFailNote: string | null;
};

const atLeast = (name: string, value: number | null, min: number): Check => ({
  name,
  value,
  threshold: `≥ ${min}`,
  outcome: value === null ? "undecidable" : value >= min ? "pass" : "fail",
});
const passes = (checks: Check[]) => checks.every((c) => c.outcome === "pass");

function criterion1(q: QualitySlice): Check[] {
  return [
    atLeast("전체 감성 Macro F1", q.overall.macroF1.value, ADOPTION_THRESHOLDS.overallMacroF1),
    atLeast("항목 감성 Macro F1(여섯 항목 합침)", q.pooledAspectSentiment.value, ADOPTION_THRESHOLDS.pooledAspectSentimentMacroF1),
  ];
}

function adoption(
  jev: QualitySlice,
  openai: QualitySlice,
  paired: Pair[],
  gold: GoldMap,
  seed: number,
): Adoption {
  const c1 = criterion1(jev);
  const c2 = NEGATIVE_RECALL_KEYS.map((k) =>
    atLeast(`부정 재현율 ${k}`, jev.negativeRecall[k].value, ADOPTION_THRESHOLDS.negativeRecall),
  );

  const rows = paired.filter((p) => gold.has(p.reviewId)).map((p) => ({ ...p, gold: gold.get(p.reviewId)! }));
  type Row = (typeof rows)[number];
  // 표본에 판정할 칸이 하나도 없으면 F1이 null이다. 아주 작은 세트에서만 생기며 0으로 쳐서 하한을 보수적으로 둔다.
  const overallF1 = (side: "jev" | "openai") => (idx: readonly number[]) =>
    macroF1(idx.map((i) => rows[i].gold.overall), idx.map((i) => rows[i][side].overall)).value ?? 0;
  // 리뷰를 다시 뽑고, 뽑힌 리뷰마다 여섯 항목 칸을 함께 넣는다. 칸 단위로 뽑으면 같은 리뷰의 칸끼리 상관이 무시돼 구간이 좁아진다.
  const pooledF1 = (side: "jev" | "openai") => (idx: readonly number[]) => {
    const picked: Row[] = idx.map((i) => rows[i]);
    return (
      aspectSentimentMacroF1(
        picked.flatMap((r) => ASPECT_KEYS.map((k) => r.gold[k])),
        picked.flatMap((r) => ASPECT_KEYS.map((k) => r[side][k] as AspectLabel)),
      ).value ?? 0
    );
  };
  const boot = (metric: (side: "jev" | "openai") => (idx: readonly number[]) => number) =>
    rows.length === 0
      ? null
      : pairedBootstrapDiff(
          { n: rows.length, metricA: metric("jev"), metricB: metric("openai") },
          { iterations: ADOPTION_THRESHOLDS.bootstrapIterations, seed },
        );
  const overall = boot(overallF1);
  const pooled = boot(pooledF1);
  const lowerBound = (name: string, d: BootstrapDiff | null): Check => ({
    name,
    value: d?.lower ?? null,
    threshold: `> ${ADOPTION_THRESHOLDS.bootstrapLowerBound}`,
    outcome: d === null ? "undecidable" : d.lower > ADOPTION_THRESHOLDS.bootstrapLowerBound ? "pass" : "fail",
  });
  const c3 = [lowerBound("전체 감성 Macro F1 차이 하한", overall), lowerBound("항목 감성 Macro F1 차이 하한", pooled)];

  const openaiC1 = criterion1(openai);
  const verdict =
    passes(c1) && passes(c2)
      ? passes(c3)
        ? "Jev 채택"
        : "OpenAI가 낫지만 Jev도 쓸 만함: 비용·속도와 함께 판단"
      : "Jev 기준 미달";
  return {
    criterion1: c1,
    criterion2: c2,
    criterion3: c3,
    bootstrap: { reviews: rows.length, overall, pooled, seed },
    openaiCriterion1: openaiC1,
    verdict,
    bothFailNote:
      !passes(c1) && !passes(openaiC1) ? "두 모델 모두 최소 기준 미달: 모델보다 분류 기준·질문을 먼저 고친다" : null,
  };
}

// 8. 파일럿 점검

export type PilotCheck = { gate: string; meteringGaps: { key: string; missing: string[] }[] };

function pilot(records: RunRecord[], jev: QualitySlice | null): PilotCheck {
  const acc = jev?.overall.accuracy.value ?? null;
  const gate =
    acc === null
      ? "판정 불가: Jev 전체 감성 정확도를 잴 정답 행이 없다"
      : acc < PILOT_MIN_ACCURACY
        ? `중단: Jev 전체 감성 정확도 ${fmt(acc)} < ${PILOT_MIN_ACCURACY}`
        : `통과: Jev 전체 감성 정확도 ${fmt(acc)}`;
  const meteringGaps = records.filter(isOk).flatMap((r) => {
    const missing = [
      !r.model && "model",
      !r.criteriaVersion && "criteriaVersion",
      !Number.isFinite(r.orderSeed) && "orderSeed",
      !Number.isFinite(r.usage?.inputTokens) && "usage.inputTokens",
      !Number.isFinite(r.usage?.outputTokens) && "usage.outputTokens",
      !Number.isFinite(r.costUsd) && "costUsd",
      !r.priceDate && "priceDate",
    ].filter((m): m is string => typeof m === "string");
    return missing.length === 0 ? [] : [{ key: `${r.provider}/${r.reviewId}/${r.repeat}`, missing }];
  });
  return { gate, meteringGaps };
}

// 보고서 전체

export type ReportData = {
  info: RunInfo;
  reliability: ProviderReliability[];
  speed: SpeedRow[];
  quality: ProviderQuality[] | null;
  comparison: Comparison | null;
  repeats: RepeatStability | null;
  adoption: Adoption | null;
  pilot: PilotCheck | null;
};

export type ReportInput = {
  records: RunRecord[];
  reviews: ReportReview[];
  gold: GoldMap | null;
  pilot?: boolean;
  bootstrapSeed?: number;
};

export function buildReport({ records, reviews, gold, pilot: isPilot = false, bootstrapSeed = DEFAULT_BOOTSTRAP_SEED }: ReportInput): ReportData {
  if (records.length === 0) throw new Error("실행 기록이 없다");
  const reviewMap = new Map(reviews.map((r) => [r.id, r]));
  const info = runInfo(records);
  const q = gold === null ? null : quality(records, reviewMap, gold);
  const sliceOf = (p: ProviderId) => q?.find((x) => x.provider === p)?.all ?? null;
  const paired = pairs(records);
  const bothPresent = PROVIDERS.every((p) => records.some((r) => r.provider === p && isOk(r)));
  const jev = sliceOf("jev");
  const openai = sliceOf("openai");
  return {
    info,
    reliability: reliability(records),
    speed: speed(records),
    quality: q,
    comparison: bothPresent ? comparison(paired, reviewMap, gold) : null,
    repeats: info.repeats > 1 ? repeatStability(records) : null,
    adoption: gold !== null && jev !== null && openai !== null ? adoption(jev, openai, paired, gold, bootstrapSeed) : null,
    pilot: isPilot ? pilot(records, jev) : null,
  };
}

// 마크다운

export function renderMarkdown(d: ReportData): string {
  const out: string[] = [];
  const i = d.info;
  out.push(`# 리뷰 분석 비교 보고서: ${i.runIds.join(", ")}`, "");
  if (i.synthetic) out.push("> 생성 데이터 기준 결과", "");

  out.push("## 1. 실행 정보", "");
  out.push(`- 실행 이름: ${i.runIds.join(", ")}`);
  for (const [p, models] of Object.entries(i.models)) out.push(`- ${p} 모델: ${models.join(", ")}`);
  out.push(`- 분류 기준 버전: ${i.criteriaVersions.join(", ")}`);
  out.push(`- 세트: ${i.sets.join(", ")}`);
  out.push(`- 리뷰 수: ${i.reviewCount}`);
  out.push(`- 반복 횟수: ${i.repeats}`);
  out.push(`- 동시 요청 수: ${i.concurrencies.join(", ")}`);
  out.push(`- 선택지 순서 시드: ${i.orderSeedCount}가지`);
  out.push(`- 가격 기준일: ${i.priceDates.join(", ") || "없음"}`, "");

  out.push("## 2. 안정성·비용", "");
  out.push(
    table(
      ["제공자", "기록된 키", "ok", ...ERROR_KINDS.map((k) => `실패 ${k}`), "실패율", "재시도율", "총비용(USD)", "1,000건당(USD)", "입력 토큰", "출력 토큰"],
      d.reliability.map((r) => [
        r.provider,
        r.keys,
        r.ok,
        ...ERROR_KINDS.map((k) => r.failedByKind[k]),
        pct(r.failureRate),
        pct(r.retryRate),
        r.costUsd.toFixed(4),
        r.costPer1000Reviews === null ? "없음" : r.costPer1000Reviews.toFixed(4),
        r.inputTokens,
        r.outputTokens,
      ]),
    ),
    "",
  );

  out.push("## 3. 속도", "");
  out.push("지연 시간은 한 번에 성공한 요청만 쓴다. 재시도한 요청에는 백오프 대기가 섞여 있어 뺀다.", "");
  out.push(
    table(
      ["제공자", "동시", "중앙값(ms)", "p95(ms)", "사용", "제외(재시도)", "전체 처리 시간(s)"],
      d.speed.map((s) => [s.provider, s.concurrency, num(s.medianMs, 0), num(s.p95Ms, 0), s.used, s.excludedRetried, (s.wallMs / 1000).toFixed(1)]),
    ),
    "",
  );

  if (d.quality) {
    out.push("## 4. 품질", "");
    out.push("첫 반복만, 사람 정답이 있는 리뷰만 본다. 실패한 요청은 품질에서 빼고 2절 실패율에 센다. 판단 불가 칸은 빼고 개수만 센다.", "");
    for (const pq of d.quality) {
      for (const [title, slice] of [
        ["전체", pq.all],
        ["어려운 사례", pq.hard],
        ["일반 리뷰", pq.general],
      ] as const) {
        out.push(`### ${pq.provider}: ${title} (${slice.reviews}개)`, "");
        out.push(...renderSlice(slice), "");
      }
    }
  }

  if (d.comparison) {
    const c = d.comparison;
    out.push("## 5. 두 모델 비교", "");
    out.push(`둘 다 성공한 첫 반복 리뷰 ${c.reviews}개 기준이다.`, "");
    out.push(
      table(
        ["질문", "일치율", "칸 수"],
        [["전체 질문", num(c.overall.value), c.overall.n], ...QUESTION_KEYS.map((k) => [k, num(c.perQuestion[k].value), c.perQuestion[k].n])],
      ),
      "",
    );
    if (c.disagreements.length > 0) {
      out.push(`불일치 사례(최대 ${MAX_DISAGREEMENTS}개):`, "");
      out.push(
        table(
          ["id", "질문", "jev", "openai", "사람 정답", "본문"],
          c.disagreements.map((x) => [x.reviewId, x.question, x.jev, x.openai, x.gold ?? "", x.text]),
        ),
        "",
      );
    }
  }

  if (d.repeats) {
    out.push("## 6. 반복 실행·선택지 순서", "");
    out.push(
      table(
        ["제공자", "반복된 칸", "답이 바뀐 칸", "변화율"],
        d.repeats.perProvider.map((r) => [r.provider, r.cells, r.changed, num(r.changeRate)]),
      ),
      "",
    );
    const order = { per_repeat: "반복마다 섞음", fixed: "반복끼리 같은 순서", unknown: "알 수 없음" }[d.repeats.orderPermuted];
    out.push(`선택지 순서: ${order}`, "");
  }

  if (d.adoption) {
    const a = d.adoption;
    out.push("## 7. 채택 판정", "");
    out.push("기준은 결과를 보기 전에 고정했다.", "");
    const checkRows = (label: string, checks: Check[]) => checks.map((c) => [label, c.name, num(c.value), c.threshold, OUTCOME[c.outcome]]);
    out.push(
      table(
        ["기준", "항목", "값", "기준값", "결과"],
        [...checkRows("1 최소 기준", a.criterion1), ...checkRows("2 부정 재현율", a.criterion2), ...checkRows("3 OpenAI 대비", a.criterion3)],
      ),
      "",
    );
    out.push(
      `부트스트랩: 둘 다 성공한 정답 리뷰 ${a.bootstrap.reviews}개, ${ADOPTION_THRESHOLDS.bootstrapIterations}회, 시드 ${a.bootstrap.seed}. ` +
        `전체 감성 차이 ${diff(a.bootstrap.overall)}, 항목 감성 차이 ${diff(a.bootstrap.pooled)}`,
      "",
    );
    out.push(`OpenAI 최소 기준: ${a.openaiCriterion1.map((c) => `${c.name} ${num(c.value)} ${OUTCOME[c.outcome]}`).join(", ")}`, "");
    out.push(`**판정: ${a.verdict}**`, "");
    if (a.bothFailNote) out.push(`**${a.bothFailNote}**`, "");
  }

  if (d.pilot) {
    out.push("## 8. 파일럿 점검", "");
    out.push(`- 관문: ${d.pilot.gate}`);
    out.push(
      d.pilot.meteringGaps.length === 0
        ? "- 계측 누락: 없음"
        : `- 계측 누락 ${d.pilot.meteringGaps.length}건: ${d.pilot.meteringGaps.slice(0, 20).map((g) => `${g.key}(${g.missing.join(", ")})`).join("; ")}`,
      "",
    );
  }
  return out.join("\n");
}

const OUTCOME: Record<Outcome, string> = { pass: "통과", fail: "미달", undecidable: "판정 불가" };

function renderSlice(s: QualitySlice): string[] {
  if (s.reviews === 0) return ["해당 리뷰 없음"];
  const o = s.overall;
  return [
    `- 전체 감성 정확도 ${num(o.accuracy.value)} (n=${o.accuracy.n}), Macro F1 ${num(o.macroF1.value)}`,
    `- 항목 감성 Macro F1(여섯 항목 합침) ${num(s.pooledAspectSentiment.value)} (n=${s.pooledAspectSentiment.n})`,
    `- 부정 재현율: ${NEGATIVE_RECALL_KEYS.map((k) => `${k} ${num(s.negativeRecall[k].value)}(${s.negativeRecall[k].support})`).join(", ")}`,
    `- 판단 불가 칸: ${QUESTION_KEYS.map((k) => `${k} ${s.undecidable[k]}`).join(", ")}`,
    "",
    "전체 감성 혼동 행렬(행 정답, 열 예측):",
    "",
    table(["정답 \\ 예측", ...o.confusion.classes], o.confusion.classes.map((g) => [g, ...o.confusion.classes.map((p) => o.confusion.counts[g][p])])),
    "",
    table(
      ["항목", "언급 P", "언급 R", "언급 F1", "감성 Macro F1", "감성 n"],
      s.aspects.map((a) => [a.key, num(a.mention.precision), num(a.mention.recall), num(a.mention.f1), num(a.sentiment.value), a.sentiment.n]),
    ),
  ];
}

// 공용 도우미

function isOk(r: RunRecord): r is OkRecord {
  return r.status === "ok";
}

function providersIn(records: RunRecord[]): ProviderId[] {
  return PROVIDERS.filter((p) => records.some((r) => r.provider === p));
}

function missingReview(id: string): never {
  throw new Error(`리뷰 파일에 없는 리뷰다: ${id}`);
}

function groupBy<T>(items: T[], key: (item: T) => string): Map<string, T[]> {
  const map = new Map<string, T[]>();
  for (const item of items) {
    const k = key(item);
    map.set(k, [...(map.get(k) ?? []), item]);
  }
  return map;
}

const sum = (xs: number[]) => xs.reduce((a, b) => a + b, 0);
const fmt = (n: number, digits = 3) => n.toFixed(digits);
const num = (n: number | null, digits = 3) => (n === null ? "판정 불가" : fmt(n, digits));
const pct = (n: number) => `${(n * 100).toFixed(1)}%`;
const diff = (d: BootstrapDiff | null) =>
  d === null ? "계산 불가" : `${fmt(d.observed)} [${fmt(d.lower)}, ${fmt(d.upper)}]`;
const truncate = (text: string, max: number) => (text.length > max ? `${text.slice(0, max)}…` : text);

function table(header: string[], rows: (string | number)[][]): string {
  const cell = (v: string | number) => String(v).replaceAll("|", "\\|").replaceAll("\n", " ");
  return [header, header.map(() => "---"), ...rows].map((r) => `| ${r.map(cell).join(" | ")} |`).join("\n");
}
