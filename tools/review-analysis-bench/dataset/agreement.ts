// 두 정답 시트의 일치도(Cohen's κ)를 낸다.
// 실행: node dataset/agreement.ts <A 시트> <B 시트> <결과 이름> [--force]
//   두 명: node dataset/agreement.ts data/labels/a/eval.csv data/labels/b/eval.csv eval
//   혼자: node dataset/agreement.ts data/labels/a/eval.csv data/labels/a-recheck/eval.csv eval-self
//
// 두 시트의 id가 같으면(두 명) 논의용 파일을 만든다.
//   data/labels/<결과 이름>-disagreements.csv: 다른 칸이 있는 행
//   data/labels/<결과 이름>-gold-draft.csv: 같은 칸은 채우고 다른 칸은 "a|b"로 둔 초안
// B가 A의 일부이면(혼자 50개 다시 매기기) κ만 낸다. 이때 정답은 A 시트 전체다.
// 50개만 비교한 초안을 정답으로 쓰면 평가용 정답이 300개가 아니라 50개가 되기 때문이다.
import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { readCsvRecords, toCsv } from "../src/csv.ts";
import { splitAgreement, type KappaResult } from "../src/kappa.ts";
import { normalizeCode, QUESTION_KEYS, validateRow } from "../src/labels.ts";

const args = process.argv.slice(2).filter((a) => a !== "--force");
const force = process.argv.includes("--force");
const [pathA, pathB, name] = args;
if (!pathA || !pathB || !name) {
  console.error("사용법: node dataset/agreement.ts <A 시트> <B 시트> <결과 이름> [--force]");
  process.exit(1);
}

const load = (path: string) => {
  const rows = readCsvRecords(readFileSync(path, "utf8"));
  const errors = rows.flatMap(validateRow);
  if (errors.length > 0) {
    console.error(`${path}에 빈칸·잘못된 코드가 ${errors.length}개 있다. check-sheet.ts로 먼저 고친다.`);
    process.exit(1);
  }
  const byId = new Map(rows.map((r) => [r.id, r]));
  if (byId.size !== rows.length) {
    console.error(`${path}에 같은 id가 두 번 이상 있다.`);
    process.exit(1);
  }
  return byId;
};

const a = load(pathA);
const b = load(pathB);
const missingInA = [...b.keys()].filter((id) => !a.has(id));
if (missingInA.length > 0) {
  console.error(`B에만 있는 id가 ${missingInA.length}개 있다: ${missingInA.slice(0, 5).join(", ")}`);
  process.exit(1);
}
const sameIds = a.size === b.size;
const ids = [...b.keys()];
console.log(`비교 대상 ${ids.length}행${sameIds ? "" : ` (A ${a.size}행 중 일부, 자기 일치도)`}`);

const fmt = (r: KappaResult | null) => {
  if (r === null) return "".padEnd(16);
  // toFixed는 -0.0001을 "-0.000"으로 찍는다.
  const kappa = r.kappa === null ? "계산 불가" : (Math.abs(r.kappa) < 0.0005 ? 0 : r.kappa).toFixed(3);
  const flag = r.kappa !== null && r.kappa < 0.6 ? "!" : " ";
  return `${kappa.padStart(7)}${flag} (n=${r.n})`.padEnd(16);
};

console.log("\n항목        언급 여부 κ      감성 κ           판단 불가 칸");
for (const key of QUESTION_KEYS) {
  const r = splitAgreement(
    ids.map((id) => normalizeCode(a.get(id)![key])),
    ids.map((id) => normalizeCode(b.get(id)![key])),
    key !== "overall",
  );
  console.log(`${key.padEnd(11)} ${fmt(r.mention)} ${fmt(r.sentiment)} ${r.undecidableRows}`);
}
console.log("\n! = κ < 0.6. 그 항목은 모델보다 명세를 먼저 고친다.");
console.log("감성 κ는 두 사람이 모두 언급했고 판단 불가가 아닌 칸만 센다. n이 작으면 몇 칸에 크게 흔들린다.");

if (!sameIds) {
  console.log(`\n자기 일치도 확인이라 정답 초안을 만들지 않는다. 정답은 ${pathA} 전체를 쓴다.`);
  process.exit(0);
}

const dir = new URL("../data/labels/", import.meta.url);
const outputs = [`${name}-disagreements.csv`, `${name}-gold-draft.csv`];
const existing = outputs.filter((f) => existsSync(new URL(f, dir)));
if (existing.length > 0 && !force) {
  console.error(`\n${existing.join(", ")}이 이미 있다. 논의해서 고친 내용을 덮어쓰지 않도록 멈춘다. 다시 만들려면 --force`);
  process.exit(1);
}

const header = ["id", "product", "text", ...QUESTION_KEYS, "note"];
const draft: string[][] = [];
const disagreements: string[][] = [];
for (const id of ids) {
  const ra = a.get(id)!;
  const rb = b.get(id)!;
  const cells = QUESTION_KEYS.map((k) => {
    const va = normalizeCode(ra[k]);
    const vb = normalizeCode(rb[k]);
    return va === vb ? va : `${va}|${vb}`;
  });
  const note = [ra.note, rb.note].filter(Boolean).join(" / ");
  const row = [id, ra.product, ra.text, ...cells, note];
  draft.push(row);
  if (cells.some((c) => c.includes("|"))) disagreements.push(row);
}

writeFileSync(new URL(outputs[0], dir), toCsv([header, ...disagreements]));
writeFileSync(new URL(outputs[1], dir), toCsv([header, ...draft]));
console.log(`\n다른 칸이 있는 행 ${disagreements.length}개 → data/labels/${outputs[0]}`);
