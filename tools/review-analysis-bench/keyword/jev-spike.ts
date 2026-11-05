// #387 E4: 키워드 질문을 상품당 최대 30개 담은 Jev 요청이 동작하는지 확인한다. 정밀도 측정이 아니다(정답 없음).
// 오류 없이 답이 오는지, 지연이 얼마인지, 답이 대충 말이 되는지만 본다.
// 실행: node --no-warnings --env-file=../../.env keyword/jev-spike.ts [--keywords keyword/keywords.json] [--ids R0001,R0002]
import { readFileSync } from "node:fs";
import { parseArgs } from "node:util";
import { parseCsv } from "../src/csv.ts";
import { appliesTo, buildJevBody, loadKeywordList } from "./list.ts";

const { values } = parseArgs({ options: { keywords: { type: "string", default: "keyword/keywords.json" }, ids: { type: "string" } }, strict: true });
const list = loadKeywordList(values.keywords);
const reviews = new Map(readFileSync("data/reviews.jsonl", "utf8").split("\n").filter(Boolean).map((l) => JSON.parse(l)).map((r) => [r.id, r]));
const categoryOf = new Map(parseCsv(readFileSync("data/keyword/product-categories.csv", "utf8")).slice(1).map(([p, , c]) => [p, c]));
const extracted = new Map(
  readFileSync("data/keyword/extract-list-luna.jsonl", "utf8").split("\n").filter(Boolean).map((l) => JSON.parse(l))
    .map((r) => [r.id, r.expressions.map((e: { phrase: string }) => e.phrase)]),
);

const ids = values.ids?.split(",") ?? ["R0005", "R0028"];
const apiKey = process.env.TYPESAFE_API_KEY;
if (!apiKey) throw new Error("TYPESAFE_API_KEY가 없다");

for (const id of ids) {
  const review = reviews.get(id);
  const category = categoryOf.get(review.product)!;
  const keywords = list.keywords.filter((k) => appliesTo(k, category));
  const body = buildJevBody(list, keywords, review);
  const started = performance.now();
  const res = await fetch("https://api.typesafe.ai/v1/systemone", {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${apiKey}` },
    body: JSON.stringify(body),
  });
  const ms = Math.round(performance.now() - started);
  const text = await res.text();
  console.log(`\n${id} [${category}] 질문 ${keywords.length}개, HTTP ${res.status}, ${ms}ms, 요청 ${JSON.stringify(body).length}바이트`);
  console.log(`  본문: ${review.text.replace(/\n/g, " ")}`);
  console.log(`  luna 추출: ${(extracted.get(id) ?? []).join(", ") || "-"}`);
  if (!res.ok) {
    console.log(`  오류: ${text.slice(0, 300)}`);
    continue;
  }
  const json = JSON.parse(text);
  const said = keywords.filter((k) => json.answers?.[k.id]?.choice === "mentioned").map((k) => k.phrase);
  const missing = keywords.filter((k) => !json.answers?.[k.id]).map((k) => k.id);
  console.log(`  Jev 말함: ${said.join(", ") || "-"}`);
  if (missing.length) console.log(`  답 없는 질문: ${missing.join(", ")}`);
  console.log(`  토큰: 입력 ${json.usage?.input_tokens}`);
}
