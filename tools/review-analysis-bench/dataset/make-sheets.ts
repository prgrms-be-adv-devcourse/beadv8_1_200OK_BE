// 사람 정답 시트를 만든다. 실행: node dataset/make-sheets.ts <정답자 이름>
// data/labels/<이름>/tuning.csv, eval.csv가 생긴다.
// 시트에는 본문만 둔다. 생성 태그(어려운 사례 유형 등)를 보여 주면 정답자가 의도된 답에 끌려간다.
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { toCsv } from "../src/csv.ts";
import { QUESTION_KEYS } from "../src/labels.ts";

const labeler = process.argv[2];
if (!labeler || !/^[a-z0-9-]+$/.test(labeler)) {
  console.error("정답자 이름을 영문 소문자로 준다. 예: node dataset/make-sheets.ts keunhyeok");
  process.exit(1);
}

const data = new URL("../data/", import.meta.url);
const reviews = readFileSync(new URL("reviews.jsonl", data), "utf8")
  .split("\n")
  .filter((l) => l.trim())
  .map((l) => JSON.parse(l) as { id: string; set: string; product: string; text: string; repeatRun: boolean });

// "<이름>-recheck"는 혼자 매길 때 1주 뒤 자기 일치도를 보는 시트다. 미리 고른 평가용 50개만 담는다.
const recheck = labeler.endsWith("-recheck");

const dir = new URL(`labels/${labeler}/`, data);
mkdirSync(dir, { recursive: true });

for (const set of recheck ? ["eval"] : ["tuning", "eval"]) {
  const file = new URL(`${set}.csv`, dir);
  if (existsSync(file)) {
    console.log(`${set}.csv는 이미 있어 건너뛴다 (매긴 정답을 덮어쓰지 않는다)`);
    continue;
  }
  const rows = reviews
    .filter((r) => r.set === set && (!recheck || r.repeatRun))
    .map((r) => [r.id, r.product, r.text, ...QUESTION_KEYS.map(() => ""), ""]);
  writeFileSync(file, toCsv([["id", "product", "text", ...QUESTION_KEYS, "note"], ...rows]));
  console.log(`data/labels/${labeler}/${set}.csv (${rows.length}행)`);
}
