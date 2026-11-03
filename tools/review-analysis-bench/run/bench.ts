// Jev·OpenAI에 리뷰를 보내 data/runs/<runId>.jsonl에 기록한다.
// 실행: node --no-warnings run/bench.ts --run-id <이름> [--provider jev|openai|all] [--set tuning|eval|extra] [--limit N]
//        [--repeat-run-only] [--repeats N] [--permute-order-per-repeat] [--concurrency N] [--seed N]
// 같은 명령을 다시 돌리면 실패했거나 아직 안 돈 요청만 보낸다.
import { fileURLToPath } from "node:url";
import { parseArgs } from "node:util";
import { loadCriteria } from "../src/criteria.ts";
import { PROVIDERS } from "../src/providers.ts";
import { loadReviews, runBench } from "../src/runner.ts";
import type { ProviderId } from "../src/types.ts";

function fail(message: string): never {
  console.error(message);
  process.exit(1);
}

let values;
try {
  ({ values } = parseArgs({
    options: {
      "run-id": { type: "string" },
      provider: { type: "string", default: "all" },
      set: { type: "string", default: "tuning" },
      limit: { type: "string" },
      "repeat-run-only": { type: "boolean", default: false },
      repeats: { type: "string", default: "1" },
      "permute-order-per-repeat": { type: "boolean", default: false },
      concurrency: { type: "string", default: "1" },
      seed: { type: "string", default: "20261103" },
    },
    strict: true,
    allowPositionals: false,
  }));
} catch (e) {
  fail(`인자 오류: ${(e as Error).message}`);
}

const runId = values["run-id"];
// 실행 이름이 파일 경로가 되므로 경로 문자를 막는다.
if (!runId || !/^[\w.-]+$/.test(runId)) fail("--run-id는 필수이고 영문·숫자·_.- 만 쓴다.");
const providerIds: ProviderId[] =
  values.provider === "all" ? ["jev", "openai"] : values.provider === "jev" || values.provider === "openai" ? [values.provider] : fail(`--provider는 jev|openai|all이다: ${values.provider}`);
const set = values.set;
if (set !== "tuning" && set !== "eval" && set !== "extra") fail(`--set은 tuning|eval|extra다: ${set}`);

const positiveInt = (name: string, raw: string | undefined): number | undefined => {
  if (raw === undefined) return undefined;
  const n = Number(raw);
  if (!Number.isSafeInteger(n) || n < 1) fail(`--${name}는 1 이상의 정수다: ${raw}`);
  return n;
};
const limit = positiveInt("limit", values.limit);
const repeats = positiveInt("repeats", values.repeats)!;
const concurrency = positiveInt("concurrency", values.concurrency)!;
const seed = Number(values.seed);
if (!Number.isSafeInteger(seed)) fail(`--seed는 정수다: ${values.seed}`);

const dataDir = new URL("../data/", import.meta.url);
const reviews = loadReviews(new URL("reviews.jsonl", dataDir))
  .filter((r) => r.set === set && (!values["repeat-run-only"] || r.repeatRun))
  .sort((a, b) => a.id.localeCompare(b.id))
  .slice(0, limit);
if (reviews.length === 0) fail("조건에 맞는 리뷰가 없다.");

const providers = providerIds.map((id) => PROVIDERS[id]);
const apiKeys = Object.fromEntries(providers.map((p) => [p.id, process.env[p.apiKeyEnv]]));
const storePath = fileURLToPath(new URL(`runs/${runId}.jsonl`, dataDir));

try {
  const summary = await runBench({
    runId,
    criteria: loadCriteria(),
    providers,
    reviews,
    repeats,
    permuteOrderPerRepeat: values["permute-order-per-repeat"],
    seed,
    concurrency,
    apiKeys,
    storePath,
    goldEvalPath: fileURLToPath(new URL("labels/gold-eval.csv", dataDir)),
    fetch,
    sleep: (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
    now: () => new Date(),
  });
  if (summary.skippedLines > 0) console.warn(`잘린 줄 ${summary.skippedLines}개를 건너뛰었다(이전 실행이 쓰다가 멈춤).`);
  let total = 0;
  for (const [id, s] of Object.entries(summary.byProvider)) {
    console.log(`${id}: ok ${s.ok}, 실패 ${s.failed}, 비용 $${s.costUsd.toFixed(4)}`);
    total += s.costUsd;
  }
  console.log(
    `계획 ${summary.planned}건 중 이번에 보낸 요청 ${summary.sent}건, 총비용 $${total.toFixed(4)}, 걸린 시간 ${(summary.wallMs / 1000).toFixed(1)}초`,
  );
  console.log(storePath);
} catch (e) {
  fail((e as Error).message);
}
