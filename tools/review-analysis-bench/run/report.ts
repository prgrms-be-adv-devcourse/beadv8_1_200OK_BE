// data/runs/<runId>.jsonl로 보고서를 만들어 data/reports/<runId>.md에 쓴다.
// 실행: node --no-warnings run/report.ts --run-id <이름> [--gold <csv>] [--pilot] [--bootstrap-seed N]
// --gold를 안 주면 평가용 리뷰가 든 실행은 data/labels/gold-eval.csv를 쓰고, 아니면 --gold가 필수다.
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { parseArgs } from "node:util";
import { readCsvRecords } from "../src/csv.ts";
import { buildReport, DEFAULT_BOOTSTRAP_SEED, parseGold, renderMarkdown } from "../src/report.ts";
import { loadReviews } from "../src/runner.ts";
import { openStore } from "../src/store.ts";

function fail(message: string): never {
  console.error(message);
  process.exit(1);
}

let values;
try {
  ({ values } = parseArgs({
    options: {
      "run-id": { type: "string" },
      gold: { type: "string" },
      pilot: { type: "boolean", default: false },
      "bootstrap-seed": { type: "string", default: String(DEFAULT_BOOTSTRAP_SEED) },
    },
    strict: true,
    allowPositionals: false,
  }));
} catch (e) {
  fail(`인자 오류: ${(e as Error).message}`);
}

const runId = values["run-id"];
if (!runId || !/^[\w.-]+$/.test(runId)) fail("--run-id는 필수이고 영문·숫자·_.- 만 쓴다.");
const bootstrapSeed = Number(values["bootstrap-seed"]);
if (!Number.isSafeInteger(bootstrapSeed)) fail(`--bootstrap-seed는 정수다: ${values["bootstrap-seed"]}`);

const dataDir = new URL("../data/", import.meta.url);
const runPath = fileURLToPath(new URL(`runs/${runId}.jsonl`, dataDir));
// openStore는 없는 파일을 빈 기록으로 열므로 여기서 먼저 막는다.
if (!existsSync(runPath)) fail(`실행 기록 파일이 없다: ${runPath}`);

try {
  const store = openStore(runPath);
  const records = [...store.latest.values()];
  if (records.length === 0) fail(`실행 기록이 비어 있다: ${runPath}`);
  if (store.skippedLines > 0) console.warn(`잘린 줄 ${store.skippedLines}개를 건너뛰었다.`);

  const goldPath =
    values.gold ??
    (records.some((r) => r.set === "eval")
      ? fileURLToPath(new URL("labels/gold-eval.csv", dataDir))
      : fail("평가용 리뷰가 없는 실행은 --gold <csv>로 사람 정답 시트를 준다."));
  if (!existsSync(goldPath)) fail(`사람 정답 시트가 없다: ${goldPath}`);

  const data = buildReport({
    records,
    reviews: loadReviews(new URL("reviews.jsonl", dataDir)),
    gold: parseGold(readCsvRecords(readFileSync(goldPath, "utf8"))),
    pilot: values.pilot,
    bootstrapSeed,
  });
  const outPath = fileURLToPath(new URL(`reports/${runId}.md`, dataDir));
  mkdirSync(fileURLToPath(new URL("reports/", dataDir)), { recursive: true });
  writeFileSync(outPath, renderMarkdown(data));

  console.log(outPath);
  if (data.adoption) console.log(`판정: ${data.adoption.verdict}${data.adoption.bothFailNote ? ` / ${data.adoption.bothFailNote}` : ""}`);
  if (data.pilot) console.log(`파일럿 관문: ${data.pilot.gate} (계측 누락 ${data.pilot.meteringGaps.length}건)`);
} catch (e) {
  fail((e as Error).message);
}
