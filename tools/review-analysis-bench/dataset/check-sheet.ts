// 정답 시트의 빈칸과 잘못된 코드를 찾는다. 실행: node dataset/check-sheet.ts <csv 경로>
import { readFileSync } from "node:fs";
import { readCsvRecords } from "../src/csv.ts";
import { normalizeCode, QUESTION_KEYS, validateRow } from "../src/labels.ts";

const path = process.argv[2];
if (!path) {
  console.error("사용법: node dataset/check-sheet.ts data/labels/<이름>/eval.csv");
  process.exit(1);
}

const rows = readCsvRecords(readFileSync(path, "utf8"));
const errors = rows.flatMap(validateRow);
const done = rows.filter((r) => validateRow(r).length === 0).length;
const undecidable = rows.reduce((sum, r) => sum + QUESTION_KEYS.filter((k) => normalizeCode(r[k]) === "?").length, 0);

console.log(`${done}/${rows.length}행 완료, 판단 불가 ${undecidable}칸`);
for (const e of errors.slice(0, 30)) console.log(`  ${e}`);
if (errors.length > 30) console.log(`  …외 ${errors.length - 30}건`);
process.exit(errors.length === 0 ? 0 : 1);
