// #387 E3: 키워드 사람 정답 시트를 만들고 검사한다.
// 리뷰마다 그 분류에 묻는 키워드 중 "말함"인 것만 id로 적는 형식이다. 칸마다 말함/안 함을 매기면 2,700칸이 넘어서다.
// 적지 않은 키워드는 안 말함으로 본다. 그래서 빈칸과 "말한 키워드 없음"을 구분하려고 없으면 "-"를 적는다.
// 만들기: node --no-warnings keyword/gold-sheet.ts make          → 조정용 gold-keywords.csv
//         node --no-warnings keyword/gold-sheet.ts make eval     → 평가용 gold-keywords-eval.csv (조정용과 겹치지 않음)
// 검사:   node --no-warnings keyword/gold-sheet.ts check data/keyword/gold-keywords.csv
import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { parseCsv } from "../src/csv.ts";
import { mulberry32, shuffle } from "../src/random.ts";
import { appliesTo, loadKeywordList } from "./list.ts";

const SIZE = 100;
const TUNING_SHEET = "data/keyword/gold-keywords.csv";
const GUIDE = "data/keyword/gold-keywords-guide.md";
// 조정용 정답으로 확률 기준을 고른 뒤에는 조정용 점수가 부풀려지므로, 설정을 고정하고 겹치지 않는 평가용으로 한 번 잰다
const SHEETS = {
  tuning: { path: TUNING_SHEET, seed: 20261106 },
  eval: { path: "data/keyword/gold-keywords-eval.csv", seed: 20261107 },
} as const;

const list = loadKeywordList();
const reviews: { id: string; set: string; product: string; text: string }[] = readFileSync("data/reviews.jsonl", "utf8")
  .split("\n").filter(Boolean).map((l) => JSON.parse(l));
const categoryOf = new Map(parseCsv(readFileSync("data/keyword/product-categories.csv", "utf8")).slice(1).map(([p, , c]) => [p, c]));
const keywordsFor = (category: string) => list.keywords.filter((k) => appliesTo(k, category));

function make(kind: keyof typeof SHEETS) {
  const { path: SHEET, seed: SEED } = SHEETS[kind];
  // 한 번 매기기 시작한 시트를 덮어쓰면 정답이 사라진다
  if (existsSync(SHEET)) throw new Error(`${SHEET}가 이미 있다. 다시 만들려면 직접 옮기거나 지운다`);
  // #385에서 감성 정답을 매긴 평가용 리뷰(R0101~R0150과 직접 고른 15개)는 이미 읽은 리뷰라 뺀다
  const sentimentLabeled = new Set(parseCsv(readFileSync("data/labels/gold-eval.csv", "utf8")).slice(1).map(([id]) => id));
  const tuningIds = kind === "eval" ? new Set(parseCsv(readFileSync(TUNING_SHEET, "utf8")).slice(1).map(([id]) => id)) : new Set<string>();
  const candidates = reviews.filter((r) => r.set === "eval" && !sentimentLabeled.has(r.id) && !tuningIds.has(r.id) && categoryOf.get(r.product) !== "EXCLUDED");
  const picked = shuffle(candidates, mulberry32(SEED)).slice(0, SIZE).sort((a, b) => a.id.localeCompare(b.id));

  const quote = (v: string) => `"${v.replaceAll('"', '""')}"`;
  const lines = ["id,product,category,text,candidates,mentioned,note"];
  for (const r of picked) {
    const category = categoryOf.get(r.product)!;
    const candidatesText = keywordsFor(category).map((k) => `${k.id} ${k.phrase}`).join(" / ");
    lines.push([r.id, r.product, category, r.text, candidatesText, "", ""].map(quote).join(","));
  }
  writeFileSync(SHEET, "﻿" + lines.join("\n") + "\n");

  const guide = [
    `# 키워드 정답 매기기 (#387, 목록 ${list.version})`,
    "",
    "## 방법",
    "",
    "1. `gold-keywords.csv`를 연다. 한 줄이 리뷰 하나다.",
    "2. `text`를 읽고, `candidates`에 있는 키워드 중 리뷰가 **좋다고 말한** 것의 id만 `mentioned`에 쉼표로 적는다. 예: `C02,C22`",
    "3. 말한 키워드가 하나도 없으면 `-`를 적는다. 빈칸은 아직 안 매긴 것으로 본다.",
    "4. 판단이 어려우면 고른 뒤 `note`에 이유를 짧게 적는다.",
    "5. 끝나면 검사한다: `node --no-warnings keyword/gold-sheet.ts check data/keyword/gold-keywords.csv`",
    "",
    "## 지킬 것",
    "",
    "- 아래 판정 기준만 보고 매긴다. 모델 결과(`extract-*.jsonl`, Jev 답)는 매기는 동안 보지 않는다.",
    "- 매기기 시작한 뒤에는 `keyword/keywords.json`의 판정 기준을 바꾸지 않는다.",
    "",
    "## 공통 지시",
    "",
    list.instruction,
    "",
    "## 키워드와 판정 기준",
    "",
    "| id | 키워드 | 적용 분류 | 판정 기준 |",
    "|---|---|---|---|",
    ...list.keywords.map((k) => `| ${k.id} | ${k.phrase} | ${k.categories.join(", ")} | ${k.guide.replaceAll("|", "/")} |`),
    "",
  ].join("\n");
  if (kind === "tuning") writeFileSync(GUIDE, guide);

  const byCategory = new Map<string, number>();
  for (const r of picked) byCategory.set(categoryOf.get(r.product)!, (byCategory.get(categoryOf.get(r.product)!) ?? 0) + 1);
  console.log(`${SHEET} (${picked.length}개, 후보 ${candidates.length}개 중)\n${GUIDE}`);
  console.log([...byCategory].sort((a, b) => b[1] - a[1]).map(([c, n]) => `${c} ${n}`).join(", "));
}

function check(path: string) {
  const [header, ...rows] = parseCsv(readFileSync(path, "utf8"));
  const col = (name: string) => header.indexOf(name);
  const problems: string[] = [];
  let done = 0;
  for (const row of rows) {
    const id = row[col("id")];
    const category = row[col("category")];
    const mentioned = (row[col("mentioned")] ?? "").trim();
    if (!mentioned) {
      problems.push(`${id}: 빈칸`);
      continue;
    }
    done++;
    if (mentioned === "-") continue;
    const allowed = new Set(keywordsFor(category).map((k) => k.id));
    const ids = mentioned.split(",").map((s) => s.trim().toUpperCase());
    for (const k of ids) if (!allowed.has(k)) problems.push(`${id}: ${k}는 ${category} 후보가 아니다`);
    if (new Set(ids).size !== ids.length) problems.push(`${id}: 같은 id를 두 번 적었다`);
  }
  console.log(`매김 ${done}/${rows.length}`);
  if (problems.length) {
    console.log(problems.join("\n"));
    process.exitCode = 1;
  }
}

const [command, path] = process.argv.slice(2);
if (command === "make") make(path === "eval" ? "eval" : "tuning");
else if (command === "check" && path) check(path);
else throw new Error("사용: make | check <csv>");
