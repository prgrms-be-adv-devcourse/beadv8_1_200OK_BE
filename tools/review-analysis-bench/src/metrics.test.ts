import { test } from "node:test";
import assert from "node:assert/strict";
import type { AspectLabel } from "./types.ts";
import type { GoldLabel } from "./metrics.ts";
import {
  accuracy,
  agreementRate,
  aspectSentimentMacroF1,
  confusionMatrix,
  macroF1,
  mentionPRF,
  negativeRecall,
  pairedBootstrapDiff,
} from "./metrics.ts";

const P = "positive";
const N = "negative";
const O = "neutral";
const M = "mixed";
const NM = "not_mentioned";
const U = "undecidable";

function close(actual: number | null, expected: number): void {
  assert.ok(actual !== null && Math.abs(actual - expected) < 1e-12, `${actual} != ${expected}`);
}

const gold3: GoldLabel[] = [P, P, P, N, N, O];
const pred3: AspectLabel[] = [P, P, N, N, O, O];

test("세 범주 Macro F1 = (4/5 + 1/2 + 2/3) / 3 = 59/90", () => {
  // 긍정 P 2/2 R 2/3 → F1 4/5, 부정 P 1/2 R 1/2 → 1/2, 중립 P 1/2 R 1/1 → 2/3
  const r = macroF1(gold3, pred3);
  close(r.value, 59 / 90);
  assert.deepEqual(
    r.perClass.map((c) => c.label),
    [P, N, O],
  );
  close(r.perClass[0].f1, 4 / 5);
});

test("정확도: 6칸 중 4칸 일치 → 4/6", () => {
  close(accuracy(gold3, pred3).value, 4 / 6);
});

test("혼동 행렬: 정답 긍정은 긍정 2·부정 1, 정답 부정은 부정 1·중립 1, 정답 중립은 중립 1", () => {
  const r = confusionMatrix(gold3, pred3, [P, N, O]);
  assert.deepEqual(r.counts, {
    positive: { positive: 2, negative: 1, neutral: 0 },
    negative: { positive: 0, negative: 1, neutral: 1 },
    neutral: { positive: 0, negative: 0, neutral: 1 },
  });
});

test("혼동 행렬은 classes에 없는 라벨이 오면 오류", () => {
  assert.throws(() => confusionMatrix([P], [M], [P, N]));
});

test("예측에만 나온 혼합이 F1 0으로 평균을 끌어내린다: (1 + 2/3 + 0) / 3 = 5/9", () => {
  // 혼합은 정답 0개라 재현율 분모 0 → 0, 정밀도 0/1 → F1 0. 혼합을 빼면 5/6이었을 값이다.
  const r = macroF1([P, P, N, N], [P, P, N, M]);
  close(r.value, 5 / 9);
  assert.equal(r.perClass.length, 3);
});

test("판단 불가 정답 2칸을 빼고 남은 2칸만 센다", () => {
  const gold: GoldLabel[] = [P, U, N, U];
  const pred: AspectLabel[] = [P, N, N, P];
  const acc = accuracy(gold, pred);
  assert.equal(acc.n, 2);
  assert.equal(acc.excludedUndecidable, 2);
  close(acc.value, 1);
  const f1 = macroF1(gold, pred);
  assert.equal(f1.excludedUndecidable, 2);
  close(f1.value, 1);
  assert.equal(negativeRecall(gold, pred).excludedUndecidable, 2);
});

test("언급 여부: 판단 불가도 언급으로 세서 TP 1·FP 1·FN 2 → P 1/2, R 1/3, F1 2/5", () => {
  const r = mentionPRF([P, U, NM, NM, N], [P, NM, P, NM, NM]);
  assert.deepEqual([r.tp, r.fp, r.fn], [1, 1, 2]);
  close(r.precision, 1 / 2);
  close(r.recall, 1 / 3);
  close(r.f1, 2 / 5);
});

test("항목 감성: 정답 언급 없음·판단 불가를 빼고, 예측 언급 없음은 오답으로만 센다 → (2/3 + 0) / 2 = 1/3", () => {
  // 남는 칸: 긍정→긍정, 부정→언급 없음, 긍정→부정. 정답 언급 없음 칸의 부정 예측은 세지 않는다.
  // 긍정 P 1/1 R 1/2 → 2/3, 부정 P 0/1 R 0/1 → 0. 언급 없음은 평균 낼 범주가 아니다
  const r = aspectSentimentMacroF1([P, N, NM, U, P], [P, NM, N, P, N]);
  close(r.value, 1 / 3);
  assert.equal(r.n, 3);
  assert.equal(r.excludedNotMentioned, 1);
  assert.equal(r.excludedUndecidable, 1);
  assert.deepEqual(
    r.perClass.map((c) => c.label),
    [P, N],
  );
});

test("항목 감성: 언급된 100칸 중 한 칸만 언급 없음으로 놓쳐도 범주가 늘지 않는다", () => {
  // 25칸씩 네 범주를 모두 맞히고 긍정 한 칸만 놓침. 긍정 P 24/24 R 24/25 → 48/49, 나머지 셋은 1
  // (48/49 + 3) / 4 = 195/196
  const gold: string[] = [];
  for (let i = 0; i < 100; i++) gold.push([P, N, O, M][i % 4]);
  const pred = [...gold];
  pred[0] = NM;
  close(aspectSentimentMacroF1(gold as never, pred as never).value, 195 / 196);
});

test("부정 재현율: 정답 부정 3칸 중 2칸 맞힘 → 2/3, 긍정을 부정으로 고른 칸은 영향 없음", () => {
  const r = negativeRecall([N, N, N, P, U], [N, N, P, N, N]);
  close(r.value, 2 / 3);
  assert.equal(r.support, 3);
  assert.equal(r.excludedUndecidable, 1);
});

test("정답에 부정이 없으면 부정 재현율을 비운다", () => {
  assert.equal(negativeRecall([P, O], [N, N]).value, null);
});

test("두 제공자 일치율: 4칸 중 3칸 → 3/4", () => {
  close(agreementRate([P, N, O, M], [P, N, P, M]).value, 3 / 4);
});

test("길이가 다르면 오류", () => {
  assert.throws(() => macroF1([P], [P, N]));
});

function macroOver(gold: readonly GoldLabel[], pred: readonly AspectLabel[]) {
  return (indices: readonly number[]) =>
    macroF1(
      indices.map((i) => gold[i]),
      indices.map((i) => pred[i]),
    ).value ?? 0;
}

const perfect: AspectLabel[] = [P, P, P, N, N, O];

test("부트스트랩 관측값은 전체 표본 차이 59/90 - 1 = -31/90", () => {
  const r = pairedBootstrapDiff(
    { n: 6, metricA: macroOver(gold3, pred3), metricB: macroOver(gold3, perfect) },
    { iterations: 200, seed: 1 },
  );
  close(r.observed, -31 / 90);
  assert.ok(r.lower <= r.upper);
});

test("같은 시드면 같은 신뢰구간", () => {
  const input = { n: 6, metricA: macroOver(gold3, pred3), metricB: macroOver(gold3, perfect) };
  assert.deepEqual(pairedBootstrapDiff(input, { seed: 42 }), pairedBootstrapDiff(input, { seed: 42 }));
});

test("두 제공자 답이 같으면 관측 차이 0, 신뢰구간 [0, 0]", () => {
  const metric = macroOver(gold3, pred3);
  const r = pairedBootstrapDiff({ n: 6, metricA: metric, metricB: metric }, { seed: 7 });
  assert.deepEqual([r.observed, r.lower, r.upper, r.iterations], [0, 0, 0, 2000]);
});
