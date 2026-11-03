import { test } from "node:test";
import assert from "node:assert/strict";
import { cohenKappa, splitAgreement } from "./kappa.ts";

test("손계산 예제: 10개 중 8개 일치, 두 정답자 모두 긍정 6·부정 4", () => {
  // 관측 일치율 0.8, 우연 일치율 0.6*0.6 + 0.4*0.4 = 0.52, κ = 0.28 / 0.48 = 0.58333...
  const a = ["p", "p", "n", "n", "p", "n", "p", "p", "n", "p"];
  const b = ["p", "n", "n", "n", "p", "n", "p", "p", "p", "p"];
  const r = cohenKappa(a, b);
  assert.equal(r.observed, 0.8);
  assert.ok(Math.abs(r.expected - 0.52) < 1e-12);
  assert.ok(Math.abs((r.kappa ?? NaN) - 0.28 / 0.48) < 1e-12);
});

test("세 범주 손계산: 관측 4/6, 우연 (2*3 + 2*2 + 2*1)/36 = 1/3, κ = 0.5", () => {
  const a = ["p", "p", "n", "n", "m", "m"];
  const b = ["p", "p", "n", "p", "m", "n"];
  const r = cohenKappa(a, b);
  assert.ok(Math.abs((r.kappa ?? NaN) - 0.5) < 1e-12);
});

test("완전히 일치하면 1", () => {
  assert.equal(cohenKappa(["p", "n", "m"], ["p", "n", "m"]).kappa, 1);
});

test("우연 수준보다 나쁘면 음수", () => {
  assert.ok((cohenKappa(["p", "n"], ["n", "p"]).kappa ?? 0) < 0);
});

test("두 정답자가 한 범주만 골랐으면 κ를 정의하지 않는다", () => {
  assert.equal(cohenKappa(["-", "-", "-"], ["-", "-", "-"]).kappa, null);
});

test("길이가 다르면 오류", () => {
  assert.throws(() => cohenKappa(["p"], ["p", "n"]));
});

test("언급 없음이 대부분이면 한꺼번에 낸 κ는 높아도 감성 κ는 낮다", () => {
  // 300칸 중 270칸은 둘 다 언급 없음. 언급된 30칸 중 감성은 18칸만 일치하고, 두 사람 모두 긍정 15·부정 15를 골랐다.
  // 감성: 관측 0.6, 우연 0.5*0.5 + 0.5*0.5 = 0.5, κ = 0.1 / 0.5 = 0.2
  const a: string[] = [];
  const b: string[] = [];
  for (let i = 0; i < 270; i++) {
    a.push("-");
    b.push("-");
  }
  for (let i = 0; i < 18; i++) {
    const v = i % 2 ? "p" : "n";
    a.push(v);
    b.push(v);
  }
  for (let i = 0; i < 12; i++) {
    a.push(i % 2 ? "p" : "n");
    b.push(i % 2 ? "n" : "p");
  }

  assert.ok((cohenKappa(a, b).kappa ?? 0) > 0.75);
  const r = splitAgreement(a, b, true);
  assert.equal(r.mention?.kappa, 1);
  assert.equal(r.sentiment.n, 30);
  assert.ok(Math.abs((r.sentiment.kappa ?? NaN) - 0.2) < 1e-12);
});

test("감성 κ는 한쪽이라도 언급 없음이나 판단 불가인 칸을 빼고, 판단 불가는 언급으로 센다", () => {
  const a = ["p", "n", "-", "?", "p", "-"];
  const b = ["p", "n", "p", "n", "?", "-"];
  const r = splitAgreement(a, b, true);
  assert.equal(r.sentiment.n, 2);
  assert.equal(r.sentiment.kappa, 1);
  assert.equal(r.undecidableRows, 2);
  // 언급 여부: a = [T,T,F,T,T,F], b = [T,T,T,T,T,F] → 관측 5/6
  assert.ok(Math.abs((r.mention?.observed ?? NaN) - 5 / 6) < 1e-12);
});

test("전체 감성은 언급 여부 κ가 없다", () => {
  assert.equal(splitAgreement(["p", "n"], ["p", "n"], false).mention, null);
});
