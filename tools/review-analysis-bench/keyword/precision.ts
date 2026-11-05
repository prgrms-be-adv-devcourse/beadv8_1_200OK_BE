// #387 E3: 키워드 정답 시트의 리뷰에 Jev를 돌려 키워드 정밀도·재현율을 잰다.
// 정밀도 = Jev가 "말함"이라고 한 (리뷰, 키워드) 칸 중 정답도 말함인 비율. 노출 칩이 틀리는지를 본다.
// Jev 답은 data/keyword/jev-gold-<목록 버전>.jsonl에 남기고, 다시 돌리면 아직 성공하지 않은 리뷰만 보낸다.
// 실행: node --no-warnings --env-file=../../.env keyword/precision.ts [--gold data/keyword/gold-keywords.csv]
import { appendFileSync, existsSync, readFileSync } from "node:fs";
import { parseArgs } from "node:util";
import { parseCsv } from "../src/csv.ts";
import { callWithRetry } from "../src/http.ts";
import { appliesTo, buildJevBody, loadKeywordList } from "./list.ts";

const { values } = parseArgs({ options: { gold: { type: "string", default: "data/keyword/gold-keywords.csv" } }, strict: true });
const list = loadKeywordList();
const phraseOf = new Map(list.keywords.map((k) => [k.id, k.phrase]));
const [header, ...rows] = parseCsv(readFileSync(values.gold!, "utf8"));
const col = (name: string) => header.indexOf(name);
const gold = rows.map((r) => ({
  id: r[col("id")],
  product: r[col("product")],
  category: r[col("category")],
  text: r[col("text")],
  mentioned: new Set(r[col("mentioned")].trim() === "-" ? [] : r[col("mentioned")].split(",").map((s) => s.trim().toUpperCase())),
}));

type Answer = { id: string; ok: true; said: string[]; probs: { [k: string]: number }; ms: number; inputTokens: number } | { id: string; ok: false; message: string };
// 조정용과 평가용의 Jev 답이 섞이지 않게 정답 파일 이름별로 나눈다
const goldName = values.gold!.replace(/^.*\//, "").replace(/\.csv$/, "");
const outPath = `data/keyword/jev-${goldName}-${list.version}.jsonl`;
const answers = new Map<string, Answer>();
if (existsSync(outPath)) {
  for (const a of readFileSync(outPath, "utf8").split("\n").filter(Boolean).map((l) => JSON.parse(l) as Answer)) if (a.ok) answers.set(a.id, a);
}

const apiKey = process.env.TYPESAFE_API_KEY;
if (!apiKey) throw new Error("TYPESAFE_API_KEY가 없다");
for (const g of gold.filter((g) => !answers.has(g.id))) {
  const keywords = list.keywords.filter((k) => appliesTo(k, g.category));
  const body = buildJevBody(list, keywords, g);
  const started = performance.now();
  const outcome = await callWithRetry(
    () => fetch("https://api.typesafe.ai/v1/systemone", {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${apiKey}` },
      body: JSON.stringify(body),
    }),
    { maxRetries: 3, baseDelayMs: 1000, sleep: (ms) => new Promise((r) => setTimeout(r, ms)) },
  );
  const ms = Math.round(performance.now() - started);
  let answer: Answer;
  if (outcome.kind !== "ok") {
    answer = { id: g.id, ok: false, message: outcome.message };
  } else {
    const json = JSON.parse(outcome.body);
    const missing = keywords.filter((k) => json.answers?.[k.id]?.choice === undefined);
    answer = missing.length
      ? { id: g.id, ok: false, message: `답 없는 질문: ${missing.map((k) => k.id).join(",")}` }
      : {
          id: g.id, ok: true,
          said: keywords.filter((k) => json.answers[k.id].choice === "mentioned").map((k) => k.id),
          // 확률 기준을 바꿔 보려고 "말함" 확률을 남긴다
          probs: Object.fromEntries(keywords.map((k) => [k.id, json.answers[k.id].probabilities?.mentioned ?? 0])),
          ms, inputTokens: json.usage?.input_tokens ?? 0,
        };
  }
  appendFileSync(outPath, JSON.stringify(answer) + "\n");
  if (answer.ok) answers.set(g.id, answer);
  else console.log(`${g.id} 실패: ${answer.message}`);
}

let tp = 0, fp = 0, fn = 0;
const fpByKeyword = new Map<string, string[]>();
const fnByKeyword = new Map<string, string[]>();
for (const g of gold) {
  const a = answers.get(g.id);
  if (!a || !a.ok) continue;
  for (const k of a.said) {
    if (g.mentioned.has(k)) tp++;
    else {
      fp++;
      fpByKeyword.set(k, [...(fpByKeyword.get(k) ?? []), g.id]);
    }
  }
  for (const k of g.mentioned) {
    if (!a.said.includes(k)) {
      fn++;
      fnByKeyword.set(k, [...(fnByKeyword.get(k) ?? []), g.id]);
    }
  }
}
const done = gold.filter((g) => answers.get(g.id)?.ok).length;
const ms = [...answers.values()].filter((a) => a.ok).map((a) => (a as { ms: number }).ms).sort((a, b) => a - b);
const fmt = (n: number) => n.toFixed(3);
console.log(`목록 ${list.version}, 리뷰 ${done}/${gold.length}`);
console.log(`정밀도 ${fmt(tp / (tp + fp))} (${tp}/${tp + fp}), 재현율 ${fmt(tp / (tp + fn))} (${tp}/${tp + fn})`);
console.log(`지연 중앙값 ${ms[Math.floor(ms.length / 2)]}ms, 최대 ${ms.at(-1)}ms`);
const show = (m: Map<string, string[]>) => [...m].sort((a, b) => b[1].length - a[1].length).map(([k, ids]) => `${k} ${phraseOf.get(k)} ${ids.length} (${ids.join(",")})`).join("\n  ");
console.log(`\n오탐 (Jev만 말함):\n  ${show(fpByKeyword)}`);
console.log(`\n놓침 (정답만 말함):\n  ${show(fnByKeyword)}`);

// 확률 기준과 "식감이 좋아요" 규칙에 따라 정밀도·재현율이 어떻게 바뀌는지 본다. 이 정답으로 고르면 이 정답은 조정용이 된다
const SPECIFIC_TEXTURE = ["C18", "C19", "C20", "C21", "C22", "C23", "C26", "C28"];
function score(threshold: number, suppressGenericTexture: boolean) {
  let tp = 0, fp = 0, fn = 0;
  for (const g of gold) {
    const a = answers.get(g.id);
    if (!a || !a.ok) continue;
    let said = Object.entries(a.probs).filter(([, p]) => p >= threshold).map(([k]) => k);
    // 판정 기준: 쫄깃·부드러움 같은 구체적인 식감을 말했으면 "식감이 좋아요"는 고르지 않는다
    if (suppressGenericTexture && said.some((k) => SPECIFIC_TEXTURE.includes(k))) said = said.filter((k) => k !== "C25");
    for (const k of said) g.mentioned.has(k) ? tp++ : fp++;
    for (const k of g.mentioned) if (!said.includes(k)) fn++;
  }
  return `정밀도 ${fmt(tp / (tp + fp))} (${tp}/${tp + fp}), 재현율 ${fmt(tp / (tp + fn))}`;
}
console.log("\n기준      규칙 없음                         식감 규칙");
for (const t of [0.5, 0.6, 0.7, 0.8, 0.9, 0.95, 0.99]) console.log(`p>=${t.toFixed(2)}  ${score(t, false).padEnd(34)}${score(t, true)}`);
