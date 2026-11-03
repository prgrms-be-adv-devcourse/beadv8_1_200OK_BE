// Cohen's κ: 두 정답자(또는 같은 사람의 1주 간격 두 번)의 일치도에서 우연히 맞을 확률을 뺀 값.
// κ = (관측 일치율 - 우연 일치율) / (1 - 우연 일치율)
// 우연 일치율은 각 정답자가 범주를 고른 비율을 곱해 범주별로 더한다.

export type KappaResult = { n: number; observed: number; expected: number; kappa: number | null };

export function cohenKappa(a: readonly string[], b: readonly string[]): KappaResult {
  if (a.length !== b.length) throw new Error(`두 정답 길이가 다르다: ${a.length} != ${b.length}`);
  const n = a.length;
  if (n === 0) return { n, observed: 0, expected: 0, kappa: null };

  let agree = 0;
  const countA = new Map<string, number>();
  const countB = new Map<string, number>();
  for (let i = 0; i < n; i++) {
    if (a[i] === b[i]) agree++;
    countA.set(a[i], (countA.get(a[i]) ?? 0) + 1);
    countB.set(b[i], (countB.get(b[i]) ?? 0) + 1);
  }

  const observed = agree / n;
  let expected = 0;
  for (const [category, ca] of countA) expected += (ca / n) * ((countB.get(category) ?? 0) / n);

  // 두 정답자가 모두 한 범주만 골랐으면 분모가 0이라 κ를 정의할 수 없다. 1로 치지 않고 따로 보고한다.
  const kappa = expected === 1 ? null : (observed - expected) / (1 - expected);
  return { n, observed, expected, kappa };
}

const NOT_MENTIONED = "-";
const UNDECIDABLE = "?";

export type SplitAgreement = {
  mention: KappaResult | null;
  sentiment: KappaResult;
  undecidableRows: number;
};

// 언급 없음까지 한 범주로 섞어 κ를 내면, 대부분의 칸이 둘 다 언급 없음일 때 감성 판단이 엇갈려도 κ가 높게 나온다.
// 그래서 평가 지표(언급 여부 P/R/F1, 언급된 칸의 감성 Macro F1)처럼 κ도 둘로 나눈다.
// - 언급 여부: 언급 없음 대 나머지. 판단 불가도 "언급은 있다"이므로 언급으로 센다.
// - 감성: 두 정답자가 모두 언급했고 어느 쪽도 판단 불가가 아닌 칸만.
// 전체 감성에는 언급 없음이 없으므로 mention이 null이다.
export function splitAgreement(a: readonly string[], b: readonly string[], hasMention: boolean): SplitAgreement {
  if (a.length !== b.length) throw new Error(`두 정답 길이가 다르다: ${a.length} != ${b.length}`);
  const rows = a.map((va, i) => [va, b[i]] as const);

  const mention = hasMention
    ? cohenKappa(
        rows.map(([va]) => String(va !== NOT_MENTIONED)),
        rows.map(([, vb]) => String(vb !== NOT_MENTIONED)),
      )
    : null;

  const judged = rows.filter(
    ([va, vb]) => va !== NOT_MENTIONED && vb !== NOT_MENTIONED && va !== UNDECIDABLE && vb !== UNDECIDABLE,
  );
  const sentiment = cohenKappa(
    judged.map(([va]) => va),
    judged.map(([, vb]) => vb),
  );

  const undecidableRows = rows.filter(([va, vb]) => va === UNDECIDABLE || vb === UNDECIDABLE).length;
  return { mention, sentiment, undecidableRows };
}
