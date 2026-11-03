// 명세 점검: 사람 정답과 고급 모델 답을 칸마다 나란히 놓고, 다른 칸만 모은다.
// 실행: node dataset/probe-compare.ts <정답 시트> <모델이름=결과파일> [<모델이름=결과파일> ...]
//   예: node dataset/probe-compare.ts data/labels/keunhyeok/tuning.csv fable=data/probe/fable.jsonl astra=data/probe/astra.jsonl
// 결과 파일은 probe-prompt.ts 질문지에 대한 모델 답을 그대로 저장한 것이다. 여러 묶음의 답을 한 파일에 이어 붙여도 된다.
// 결과: data/probe/compare.csv. 사람 답과 다르게 고른 모델이 많은 칸부터 정렬한다.
// 회사가 다른 모델들이 모두 사람 답과 다르게 읽은 칸은 명세 문장이 그렇게 읽힐 여지가 크다는 신호다.
import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { readCsvRecords, toCsv } from "../src/csv.ts";
import { CODE_TO_LABEL, normalizeCode, QUESTION_KEYS, validateRow } from "../src/labels.ts";

const outPath = new URL("../data/probe/compare.csv", import.meta.url);
if (existsSync(outPath) && !process.argv.includes("--force")) {
  console.error("data/probe/compare.csv가 이미 있다. decision·memo에 적은 판단을 덮어쓰지 않도록 멈춘다. 다시 만들려면 --force");
  process.exit(1);
}
const [goldPath, ...modelArgs] = process.argv.slice(2).filter((a) => a !== "--force");
if (!goldPath || modelArgs.length === 0 || modelArgs.some((a) => !a.includes("="))) {
  console.error("사용법: node dataset/probe-compare.ts <정답 시트> <모델이름=결과파일> [...]");
  process.exit(1);
}

const gold = readCsvRecords(readFileSync(goldPath, "utf8"));
const goldErrors = gold.flatMap(validateRow);
if (goldErrors.length > 0) {
  console.error(`${goldPath}에 빈칸·잘못된 코드가 ${goldErrors.length}개 있다. check-sheet.ts로 먼저 고친다.`);
  process.exit(1);
}

const LABEL_TO_CODE = Object.fromEntries(Object.entries(CODE_TO_LABEL).map(([code, label]) => [label, code]));
type ModelRow = Record<string, string> & { reasons?: Record<string, string> };

const models = modelArgs.map((arg) => {
  const [name, path] = arg.split("=");
  const rows = new Map<string, ModelRow>();
  const problems: string[] = [];
  // 채팅 화면 답에는 코드 블록 표시나 설명 문장이 섞일 수 있어, "{"로 시작하는 줄만 읽는다.
  for (const line of readFileSync(path, "utf8").split("\n")) {
    const trimmed = line.trim();
    if (!trimmed.startsWith("{")) continue;
    let row: ModelRow;
    try {
      row = JSON.parse(trimmed);
    } catch {
      problems.push(`JSON으로 읽히지 않는 줄: ${trimmed.slice(0, 40)}…`);
      continue;
    }
    if (rows.has(row.id)) problems.push(`${row.id}가 두 번 나온다. 뒤의 답을 쓴다`);
    rows.set(row.id, row);
  }
  for (const r of gold) {
    const m = rows.get(r.id);
    if (!m) {
      problems.push(`${r.id} 답 없음`);
      continue;
    }
    for (const k of QUESTION_KEYS) {
      const code = LABEL_TO_CODE[m[k]];
      // 모델 선택지에는 판단 불가가 없고, 전체 감성에는 언급 없음이 없다.
      if (!code || code === "?" || (k === "overall" && code === "-")) problems.push(`${r.id} ${k}: '${m[k]}'는 선택지에 없는 값`);
    }
  }
  console.log(`${name}: ${rows.size}개 답${problems.length ? `, 문제 ${problems.length}건` : ""}`);
  for (const p of problems.slice(0, 10)) console.log(`  ${p}`);
  if (problems.length > 10) console.log(`  …외 ${problems.length - 10}건`);
  return { name, rows };
});

type Cell = { id: string; product: string; text: string; key: string; gold: string; answers: string[]; reasons: string[]; differ: number };
const cells: Cell[] = [];
for (const r of gold) {
  for (const k of QUESTION_KEYS) {
    const g = normalizeCode(r[k]);
    const answers = models.map((m) => {
      const raw = m.rows.get(r.id)?.[k];
      return raw === undefined ? "(없음)" : (LABEL_TO_CODE[raw] ?? `(${raw})`);
    });
    const reasons = models.map((m) => m.rows.get(r.id)?.reasons?.[k] ?? "");
    cells.push({ id: r.id, product: r.product, text: r.text, key: k, gold: g, answers, reasons, differ: answers.filter((a) => a !== g).length });
  }
}

const differing = cells.filter((c) => c.differ > 0).sort((a, b) => b.differ - a.differ || a.id.localeCompare(b.id));
const header = [
  "id", "product", "text", "question", "gold",
  ...models.map((m) => m.name),
  "differ",
  ...models.map((m) => `${m.name}_reason`),
  "decision", "memo",
];
writeFileSync(
  outPath,
  toCsv([header, ...differing.map((c) => [c.id, c.product, c.text, c.key, c.gold, ...c.answers, String(c.differ), ...c.reasons, "", ""])]),
);

console.log(`\n질문별 사람 답과 다른 칸 수 (리뷰 ${gold.length}개 기준)`);
console.log(`질문        ${models.map((m) => m.name.padEnd(8)).join(" ")} 모두 다름`);
for (const k of QUESTION_KEYS) {
  const ks = cells.filter((c) => c.key === k);
  const per = models.map((_, i) => String(ks.filter((c) => c.answers[i] !== c.gold).length).padEnd(8));
  console.log(`${k.padEnd(11)} ${per.join(" ")} ${ks.filter((c) => c.differ === models.length).length}`);
}
const undecided = cells.filter((c) => c.gold === "?").length;
console.log(`\n사람 답이 판단 불가(?)인 ${undecided}칸은 모델이 고를 수 없는 값이라 항상 다름으로 잡힌다.`);
console.log(`다른 칸 ${differing.length}개 → data/probe/compare.csv`);
console.log("decision 칸에 '내 실수' / '명세 애매' / '모델 오류' 가운데 하나를 적고 memo에 이유를 적는다.");
