import type { AspectLabel } from "./types.ts";
import { mulberry32 } from "./random.ts";

export type GoldLabel = AspectLabel | "undecidable";

const UNDECIDABLE = "undecidable";
const NOT_MENTIONED = "not_mentioned";
const LABEL_ORDER: readonly AspectLabel[] = ["positive", "negative", "neutral", "mixed", "not_mentioned"];

export type Accuracy = { value: number | null; n: number; excludedUndecidable: number };
export type ConfusionMatrix = {
  classes: readonly AspectLabel[];
  counts: Record<string, Record<string, number>>;
  excludedUndecidable: number;
};
export type ClassScore = { label: AspectLabel; precision: number; recall: number; f1: number; support: number };
export type MacroF1 = { value: number | null; n: number; excludedUndecidable: number; perClass: ClassScore[] };
export type AspectSentimentF1 = MacroF1 & { excludedNotMentioned: number };
export type MentionPRF = { precision: number; recall: number; f1: number; n: number; tp: number; fp: number; fn: number };
export type NegativeRecall = { value: number | null; support: number; excludedUndecidable: number };
export type Agreement = { value: number | null; n: number };
export type BootstrapDiff = { observed: number; lower: number; upper: number; iterations: number };

function checkLength(a: readonly unknown[], b: readonly unknown[]): void {
  if (a.length !== b.length) throw new Error(`두 배열 길이가 다르다: ${a.length} != ${b.length}`);
}

function decided(gold: readonly GoldLabel[], pred: readonly AspectLabel[]) {
  checkLength(gold, pred);
  const g: AspectLabel[] = [];
  const p: AspectLabel[] = [];
  for (let i = 0; i < gold.length; i++) {
    const label = gold[i];
    if (label === UNDECIDABLE) continue;
    g.push(label);
    p.push(pred[i]);
  }
  return { gold: g, pred: p, excludedUndecidable: gold.length - g.length };
}

function f1Of(precision: number, recall: number): number {
  return precision + recall === 0 ? 0 : (2 * precision * recall) / (precision + recall);
}

export function accuracy(gold: readonly GoldLabel[], pred: readonly AspectLabel[]): Accuracy {
  const d = decided(gold, pred);
  const n = d.gold.length;
  const hits = d.gold.filter((label, i) => label === d.pred[i]).length;
  return { value: n === 0 ? null : hits / n, n, excludedUndecidable: d.excludedUndecidable };
}

export function confusionMatrix(
  gold: readonly GoldLabel[],
  pred: readonly AspectLabel[],
  classes: readonly AspectLabel[],
): ConfusionMatrix {
  const d = decided(gold, pred);
  const counts: Record<string, Record<string, number>> = {};
  for (const g of classes) counts[g] = Object.fromEntries(classes.map((p) => [p, 0]));
  for (let i = 0; i < d.gold.length; i++) {
    const row = counts[d.gold[i]];
    if (row === undefined || row[d.pred[i]] === undefined) {
      throw new Error(`classes에 없는 라벨이다: 정답 ${d.gold[i]}, 예측 ${d.pred[i]}`);
    }
    row[d.pred[i]]++;
  }
  return { classes, counts, excludedUndecidable: d.excludedUndecidable };
}

// 범주는 정답과 예측에 한 번이라도 나온 라벨의 합집합이다(sklearn 기본값과 같다).
// 예측에만 나온 범주는 F1 0으로 평균을 끌어내려, 없는 범주를 지어내는 모델이 손해를 본다.
// 정밀도·재현율의 분모가 0이면 0으로 친다.
export function macroF1(gold: readonly GoldLabel[], pred: readonly AspectLabel[]): MacroF1 {
  return macroF1Over(gold, pred, LABEL_ORDER);
}

function macroF1Over(gold: readonly GoldLabel[], pred: readonly AspectLabel[], candidates: readonly AspectLabel[]): MacroF1 {
  const d = decided(gold, pred);
  const present = new Set<AspectLabel>([...d.gold, ...d.pred]);
  const classes = candidates.filter((label) => present.has(label));

  const perClass = classes.map((label): ClassScore => {
    let tp = 0;
    let predicted = 0;
    let support = 0;
    for (let i = 0; i < d.gold.length; i++) {
      if (d.pred[i] === label) predicted++;
      if (d.gold[i] === label) support++;
      if (d.pred[i] === label && d.gold[i] === label) tp++;
    }
    const precision = predicted === 0 ? 0 : tp / predicted;
    const recall = support === 0 ? 0 : tp / support;
    return { label, precision, recall, f1: f1Of(precision, recall), support };
  });

  const value = perClass.length === 0 ? null : perClass.reduce((sum, c) => sum + c.f1, 0) / perClass.length;
  return { value, n: d.gold.length, excludedUndecidable: d.excludedUndecidable, perClass };
}

// 판단 불가는 "언급은 있지만 감성을 정할 수 없다"는 뜻이라 언급 여부에서는 언급으로 센다.
export function mentionPRF(gold: readonly GoldLabel[], pred: readonly AspectLabel[]): MentionPRF {
  checkLength(gold, pred);
  let tp = 0;
  let fp = 0;
  let fn = 0;
  for (let i = 0; i < gold.length; i++) {
    const g = gold[i] !== NOT_MENTIONED;
    const p = pred[i] !== NOT_MENTIONED;
    if (g && p) tp++;
    else if (p) fp++;
    else if (g) fn++;
  }
  const precision = tp + fp === 0 ? 0 : tp / (tp + fp);
  const recall = tp + fn === 0 ? 0 : tp / (tp + fn);
  return { precision, recall, f1: f1Of(precision, recall), n: gold.length, tp, fp, fn };
}

// 정답이 언급한 칸만 본다. 그 칸을 모델이 언급 없음으로 답하면 틀린 예측으로 남겨 정답 범주의 재현율을 깎는다.
// 언급 없음은 평균 낼 범주에 넣지 않는다. 넣으면 언급된 칸 100개 중 한 칸만 놓쳐도 F1 0인 범주가 생겨
// 완벽한 감성 판정이 1.0에서 0.8로 떨어지고, 채택 기준(0.70)을 한 칸이 좌우한다. 언급을 놓친 벌은 언급 여부 지표가 따로 준다.
export function aspectSentimentMacroF1(gold: readonly GoldLabel[], pred: readonly AspectLabel[]): AspectSentimentF1 {
  checkLength(gold, pred);
  const keep = gold.flatMap((label, i) => (label === NOT_MENTIONED ? [] : [i]));
  const result = macroF1Over(
    keep.map((i) => gold[i]),
    keep.map((i) => pred[i]),
    LABEL_ORDER.filter((label) => label !== NOT_MENTIONED),
  );
  return { ...result, excludedNotMentioned: gold.length - keep.length };
}

export function negativeRecall(gold: readonly GoldLabel[], pred: readonly AspectLabel[]): NegativeRecall {
  const d = decided(gold, pred);
  let support = 0;
  let hits = 0;
  for (let i = 0; i < d.gold.length; i++) {
    if (d.gold[i] !== "negative") continue;
    support++;
    if (d.pred[i] === "negative") hits++;
  }
  // 부정 정답이 없으면 0으로 치면 기준 미달로, 1로 치면 통과로 잘못 읽힌다. 값을 비워 따로 보고한다.
  return { value: support === 0 ? null : hits / support, support, excludedUndecidable: d.excludedUndecidable };
}

export function agreementRate(predA: readonly AspectLabel[], predB: readonly AspectLabel[]): Agreement {
  checkLength(predA, predB);
  const n = predA.length;
  const same = predA.filter((label, i) => label === predB[i]).length;
  return { value: n === 0 ? null : same / n, n };
}

export type PairedMetric = {
  n: number;
  metricA: (indices: readonly number[]) => number;
  metricB: (indices: readonly number[]) => number;
};

// 두 제공자에 같은 행 표본을 줘야 리뷰 난이도 차이가 상쇄되고 차이만 남는다(대응 부트스트랩).
export function pairedBootstrapDiff(
  { n, metricA, metricB }: PairedMetric,
  { iterations = 2000, seed }: { iterations?: number; seed: number },
): BootstrapDiff {
  if (n <= 0) throw new Error("부트스트랩할 행이 없다");
  const all = Array.from({ length: n }, (_, i) => i);
  const observed = metricA(all) - metricB(all);

  const rand = mulberry32(seed);
  const diffs: number[] = [];
  for (let it = 0; it < iterations; it++) {
    const sample = Array.from({ length: n }, () => Math.floor(rand() * n));
    diffs.push(metricA(sample) - metricB(sample));
  }
  diffs.sort((a, b) => a - b);
  const lower = diffs[Math.floor(0.025 * iterations)];
  const upper = diffs[Math.ceil(0.975 * iterations) - 1];
  return { observed, lower, upper, iterations };
}
