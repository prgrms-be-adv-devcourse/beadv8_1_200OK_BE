// #387 키워드 목록 초안 검토 시트를 만든다. 추출 문구를 묶음 파일(grouping-*.json)로 키워드에 옮겨 세고,
// 묶음에서 빠진 문구와 키워드 범위(하위 분류)를 벗어난 언급을 찾는다.
// 실행: node --no-warnings keyword/sheet.ts --grouping data/keyword/grouping-v0.1.json [--pool list] [--model luna]
import { readFileSync, writeFileSync } from "node:fs";
import { parseArgs } from "node:util";
import { parseCsv } from "../src/csv.ts";
import { appliesTo, loadKeywordList } from "./list.ts";

type Grouping = { version: string; keywordsFile: string; map: { [phrase: string]: string[] }; excluded: { [phrase: string]: string } };
type Expression = { phrase: string; aspect: string; evidence: string };

const { values } = parseArgs({
  options: {
    grouping: { type: "string" },
    pool: { type: "string", default: "list" },
    model: { type: "string", default: "luna" },
  },
  strict: true,
});
if (!values.grouping) throw new Error("--grouping이 필요하다");

const grouping: Grouping = JSON.parse(readFileSync(values.grouping, "utf8"));
// 키워드 정의는 커밋하는 목록 파일 하나에만 둔다. 묶음 파일에는 문구 매핑만 있다
const keywords = loadKeywordList(grouping.keywordsFile).keywords;
const reviews = new Map(
  readFileSync("data/reviews.jsonl", "utf8").split("\n").filter(Boolean).map((l) => JSON.parse(l)).map((r) => [r.id, r]),
);
const categoryOf = new Map(
  parseCsv(readFileSync("data/keyword/product-categories.csv", "utf8")).slice(1).map(([product, , category]) => [product, category]),
);
const rows: { id: string; expressions: Expression[] }[] = readFileSync(`data/keyword/extract-${values.pool}-${values.model}.jsonl`, "utf8")
  .split("\n").filter(Boolean).map((l) => JSON.parse(l));

const keywordById = new Map(keywords.map((k) => [k.id, k]));
for (const [phrase, ids] of Object.entries(grouping.map)) {
  for (const id of ids) if (!keywordById.has(id)) throw new Error(`없는 키워드 ${id}: ${phrase}`);
}

type Stat = { reviews: Set<string>; byCategory: Map<string, number>; phrases: Map<string, number>; examples: string[] };
const stats = new Map<string, Stat>(keywords.map((k) => [k.id, { reviews: new Set(), byCategory: new Map(), phrases: new Map(), examples: [] }]));
const unmapped = new Map<string, number>();
const outOfScope: string[] = [];
let excludedCount = 0;

for (const row of rows) {
  const category = categoryOf.get(reviews.get(row.id).product)!;
  for (const e of row.expressions) {
    const ids = grouping.map[e.phrase];
    if (!ids) {
      if (e.phrase in grouping.excluded) excludedCount++;
      else unmapped.set(e.phrase, (unmapped.get(e.phrase) ?? 0) + 1);
      continue;
    }
    const inScope = ids.filter((id) => appliesTo(keywordById.get(id)!, category));
    // 키워드는 적용 분류(categories) 상품에만 질문한다. 적용 분류 밖 리뷰의 문구를 묶으면 서비스에서는 잡히지 않는다.
    // 같은 문구가 범위 안의 다른 키워드로 잡혔으면(아삭해요: 과일·채소) 놓친 것이 아니라 보고하지 않는다
    if (inScope.length === 0) outOfScope.push(`${ids.join("+")} ← "${e.phrase}" (${category}, ${row.id})`);
    for (const id of inScope) {
      const s = stats.get(id)!;
      s.reviews.add(row.id);
      s.byCategory.set(category, (s.byCategory.get(category) ?? 0) + 1);
      s.phrases.set(e.phrase, (s.phrases.get(e.phrase) ?? 0) + 1);
      if (s.examples.length < 2) s.examples.push(`${row.id}: ${e.evidence}`);
    }
  }
}

const quote = (v: string | number) => `"${String(v).replaceAll('"', '""')}"`;
const lines = ["id,categories,keyword,aspect,reviews,byCategory,phrases,examples,review"];
for (const k of keywords) {
  const s = stats.get(k.id)!;
  const by = [...s.byCategory].sort((a, b) => b[1] - a[1]).map(([c, n]) => `${c}:${n}`).join(" ");
  const phrases = [...s.phrases].sort((a, b) => b[1] - a[1]).map(([p, n]) => `${p}(${n})`).join(" / ");
  // review 칸은 사람이 채운다: 유지, 문구 수정, 합치기, 빼기
  lines.push([k.id, k.categories.join(" "), k.phrase, k.aspect, s.reviews.size, by, phrases, s.examples.join(" | "), ""].map(quote).join(","));
}
const out = values.grouping.replace(/grouping-(.+)\.json$/, `grouping-sheet-$1-${values.pool}.csv`);
writeFileSync(out, "﻿" + lines.join("\n") + "\n"); // 스프레드시트 앱이 한글을 읽도록 BOM을 붙인다

const total = rows.reduce((n, r) => n + r.expressions.length, 0);
console.log(`${out}\n표현 ${total}개: 키워드로 옮김 ${total - excludedCount - [...unmapped.values()].reduce((a, b) => a + b, 0)}, 뺌 ${excludedCount}, 묶음에 없음 ${[...unmapped.values()].reduce((a, b) => a + b, 0)}`);
if (unmapped.size) console.log("\n묶음에 없는 문구:\n" + [...unmapped].map(([p, n]) => `  ${p}(${n})`).join("\n"));
if (outOfScope.length) console.log("\n범위 밖 언급:\n" + outOfScope.map((l) => "  " + l).join("\n"));
