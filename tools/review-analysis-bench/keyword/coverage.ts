// #387 E1: 측정용 리뷰의 추출 표현을 목록이 얼마나 덮는지 잰다.
// 문구는 목록 매핑(grouping)에서 먼저 찾고, 없으면 측정용 판정 파일(judgments)에서 찾는다. 판정 파일로 목록을 고치지 않는다.
// 덮는 비율 = 그 상품 분류에 묻는 키워드로 잡힌 표현 / (잡힌 표현 + 다른 분류 전용이라 놓친 표현 + 목록에 없는 표현).
// 일반 칭찬·부정·사실 주장·일부러 뺀 배송·판매자·건강 효능은 분모에서 뺀다.
// 실행: node --no-warnings keyword/coverage.ts --grouping data/keyword/grouping-v0.2.json --judgments data/keyword/measure-judgments-v0.2.json
import { readFileSync } from "node:fs";
import { parseArgs } from "node:util";
import { parseCsv } from "../src/csv.ts";
import { appliesTo, loadKeywordList, type Keyword } from "./list.ts";

type Judgment = string[] | "GEN" | "NEG" | "FACT" | "EXCL";
type Outcome = "HIT" | "OOS" | "MISS" | "GEN" | "NEG" | "FACT" | "EXCL" | "UNJUDGED";

const { values } = parseArgs({
  options: {
    grouping: { type: "string" },
    judgments: { type: "string" },
    pool: { type: "string", default: "measure" },
    model: { type: "string", default: "luna" },
  },
  strict: true,
});
const grouping: { keywordsFile: string; map: { [p: string]: string[] } } = JSON.parse(readFileSync(values.grouping!, "utf8"));
const judgments: { [p: string]: Judgment } = JSON.parse(readFileSync(values.judgments!, "utf8")).map;
const keywordById = new Map<string, Keyword>(loadKeywordList(grouping.keywordsFile).keywords.map((k) => [k.id, k]));
const reviews = new Map(readFileSync("data/reviews.jsonl", "utf8").split("\n").filter(Boolean).map((l) => JSON.parse(l)).map((r) => [r.id, r]));
const categoryOf = new Map(parseCsv(readFileSync("data/keyword/product-categories.csv", "utf8")).slice(1).map(([p, , c]) => [p, c]));
const rows: { id: string; expressions: { phrase: string }[] }[] = readFileSync(`data/keyword/extract-${values.pool}-${values.model}.jsonl`, "utf8")
  .split("\n").filter(Boolean).map((l) => JSON.parse(l));

for (const [phrase, j] of Object.entries(judgments)) {
  if (Array.isArray(j)) for (const id of j) if (!id.startsWith("MISS:") && !keywordById.has(id)) throw new Error(`없는 키워드 ${id}: ${phrase}`);
}

function classify(phrase: string, category: string): { outcome: Outcome; source: "list" | "judged" | "none"; miss?: string } {
  const listed = grouping.map[phrase];
  const j: Judgment | undefined = listed ?? judgments[phrase];
  const source = listed ? "list" : j ? "judged" : "none";
  if (!j) return { outcome: "UNJUDGED", source };
  if (!Array.isArray(j)) return { outcome: j, source };
  const ids = j.filter((id) => !id.startsWith("MISS:"));
  if (ids.some((id) => appliesTo(keywordById.get(id)!, category))) return { outcome: "HIT", source };
  if (ids.length > 0) return { outcome: "OOS", source };
  return { outcome: "MISS", source, miss: j[0].slice(5) };
}

const byCategory = new Map<string, Map<Outcome, number>>();
const bySource = new Map<string, number>();
const misses = new Map<string, number>();
const unjudged = new Set<string>();
for (const row of rows) {
  const category = categoryOf.get(reviews.get(row.id).product)!;
  for (const e of row.expressions) {
    const { outcome, source, miss } = classify(e.phrase, category);
    const counts = byCategory.get(category) ?? new Map();
    counts.set(outcome, (counts.get(outcome) ?? 0) + 1);
    byCategory.set(category, counts);
    if (outcome === "HIT") bySource.set(source, (bySource.get(source) ?? 0) + 1);
    if (miss) misses.set(miss, (misses.get(miss) ?? 0) + 1);
    if (outcome === "UNJUDGED") unjudged.add(e.phrase);
  }
}

const total = new Map<Outcome, number>();
const line = (name: string, c: Map<Outcome, number>) => {
  const get = (o: Outcome) => c.get(o) ?? 0;
  const denominator = get("HIT") + get("OOS") + get("MISS");
  const rate = denominator ? Math.round((get("HIT") / denominator) * 100) : 0;
  return `${name.padEnd(15)} ${get("HIT")}/${denominator} = ${rate}%  (분류 밖 ${get("OOS")}, 목록에 없음 ${get("MISS")}, 분모 제외 ${get("GEN") + get("NEG") + get("FACT") + get("EXCL")})`;
};
for (const [category, c] of [...byCategory].sort()) {
  console.log(line(category, c));
  for (const [o, n] of c) total.set(o, (total.get(o) ?? 0) + n);
}
console.log(line("TOTAL", total));
console.log(`\n잡힌 표현의 출처: 목록 매핑 ${bySource.get("list") ?? 0}, 측정용 판정 ${bySource.get("judged") ?? 0}`);
console.log("목록에 없는 후보: " + [...misses].sort((a, b) => b[1] - a[1]).map(([m, n]) => `${m}(${n})`).join(", "));
if (unjudged.size) console.log("판정 안 된 문구: " + [...unjudged].join(", "));
