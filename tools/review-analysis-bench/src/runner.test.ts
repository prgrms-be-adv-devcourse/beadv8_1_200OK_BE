import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { loadCriteria } from "./criteria.ts";
import { choiceOrder } from "./order.ts";
import { PROVIDERS, type Provider } from "./providers.ts";
import { evalGoldErrors, runBench, type Review, type RunOptions } from "./runner.ts";
import type { RunRecord } from "./types.ts";

const criteria = loadCriteria();
const ANSWERS = {
  overall: "positive",
  taste: "positive",
  freshness: "not_mentioned",
  condition: "not_mentioned",
  packaging: "not_mentioned",
  delivery: "negative",
  price: "not_mentioned",
};

const review = (id: string, set: Review["set"] = "tuning"): Review => ({
  id,
  set,
  synthetic: true,
  product: "사과 3kg",
  text: "맛있는데 배송이 늦었어요",
  repeatRun: false,
  hardCase: null,
});

const openaiOk = (answers: Record<string, unknown> = ANSWERS, usage = { input_tokens: 1000, output_tokens: 50 }) =>
  new Response(
    JSON.stringify({
      status: "completed",
      output: [{ type: "message", content: [{ type: "output_text", text: JSON.stringify(answers) }] }],
      usage,
    }),
    { status: 200 },
  );

type Step = Response | Error | ((body: any) => Response);

// 요청마다 받은 본문을 남기고, 상품명별로 정해 둔 응답을 차례로 돌려준다. 프롬프트에는 리뷰 id가 없다.
function fakeFetch(script: (product: string) => Step[]) {
  const calls: { url: string; body: any }[] = [];
  const queues = new Map<string, Step[]>();
  const fn = (async (url: string, init: RequestInit) => {
    const body = JSON.parse(String(init.body));
    calls.push({ url, body });
    const product = body.input.match(/상품: (\S+)/)[1];
    if (!queues.has(product)) queues.set(product, script(product));
    const step = queues.get(product)!.shift() ?? openaiOk();
    if (step instanceof Error) throw step;
    return typeof step === "function" ? step(body) : step;
  }) as unknown as typeof fetch;
  return { fn, calls };
}

function options(over: Partial<RunOptions> & { fetch: typeof fetch }): RunOptions & { dir: string } {
  const dir = mkdtempSync(join(tmpdir(), "bench-"));
  return {
    dir,
    runId: "t",
    criteria,
    providers: [PROVIDERS.openai],
    reviews: [review("R0001")],
    repeats: 1,
    permuteOrderPerRepeat: false,
    seed: 1,
    concurrency: 1,
    apiKeys: { openai: "sk-test", jev: "jev-test" },
    storePath: join(dir, "runs", "t.jsonl"),
    goldEvalPath: join(dir, "gold-eval.csv"),
    sleep: async () => {},
    now: () => new Date("2026-11-03T00:00:00Z"),
    ...over,
  };
}

const records = (path: string): RunRecord[] =>
  readFileSync(path, "utf8")
    .split("\n")
    .filter(Boolean)
    .map((l) => JSON.parse(l));

const http = (status: number) => new Response("{}", { status });

test("429 다음 200이면 ok, 시도 2번", async () => {
  const f = fakeFetch(() => [http(429), openaiOk()]);
  const o = options({ fetch: f.fn });
  await runBench(o);
  const [r] = records(o.storePath);
  assert.equal(r.status, "ok");
  assert.equal(r.attempts, 2);
});

test("400이면 다시 시도하지 않고 fatal, answers 없음", async () => {
  const f = fakeFetch(() => [http(400)]);
  const o = options({ fetch: f.fn });
  await runBench(o);
  const [r] = records(o.storePath);
  assert.equal(r.status, "failed");
  assert.equal(r.status === "failed" && r.error.kind, "fatal");
  assert.equal(r.status === "failed" && r.error.httpStatus, 400);
  assert.equal(r.attempts, 1);
  assert.equal("answers" in r, false);
  assert.equal(f.calls.length, 1);
});

test("500이 네 번이면 retryable_exhausted, 시도 4번, 대기는 1·2·4초", async () => {
  const f = fakeFetch(() => [http(500), http(502), http(503), http(500), openaiOk()]);
  const waits: number[] = [];
  const o = options({ fetch: f.fn, sleep: async (ms) => void waits.push(ms) });
  await runBench(o);
  assert.deepEqual(waits, [1000, 2000, 4000]);
  const [r] = records(o.storePath);
  assert.equal(r.status === "failed" && r.error.kind, "retryable_exhausted");
  assert.equal(r.attempts, 4);
  assert.equal(f.calls.length, 4);
});

test("네트워크 오류 다음 성공이면 ok", async () => {
  const f = fakeFetch(() => [new TypeError("fetch failed"), openaiOk()]);
  const o = options({ fetch: f.fn });
  await runBench(o);
  const [r] = records(o.storePath);
  assert.equal(r.status, "ok");
  assert.equal(r.attempts, 2);
});

test("선택지에 없는 값이 오면 invalid_response", async () => {
  const f = fakeFetch(() => [openaiOk({ ...ANSWERS, taste: "great" })]);
  const o = options({ fetch: f.fn });
  await runBench(o);
  const [r] = records(o.storePath);
  assert.equal(r.status === "failed" && r.error.kind, "invalid_response");
  assert.equal("answers" in r, false);
});

test("다시 돌리면 실패한 키만 보내고 ok 기록은 건드리지 않는다", async () => {
  const reviews = [
    { ...review("R0001"), product: "A" },
    { ...review("R0002"), product: "B" },
    { ...review("R0003"), product: "C" },
  ];
  const first = fakeFetch((id) => (id === "B" ? [http(400)] : [openaiOk()]));
  const o = options({ fetch: first.fn, reviews });
  await runBench(o);
  assert.equal(first.calls.length, 3);

  const second = fakeFetch(() => [openaiOk()]);
  const summary = await runBench({ ...o, fetch: second.fn });
  assert.equal(second.calls.length, 1);
  assert.match(second.calls[0].body.input, /상품: B/);
  assert.equal(records(o.storePath).length, 4);
  assert.deepEqual(summary.byProvider.openai && { ok: summary.byProvider.openai.ok, failed: summary.byProvider.openai.failed }, {
    ok: 3,
    failed: 0,
  });

  const third = fakeFetch(() => []);
  await runBench({ ...o, fetch: third.fn });
  assert.equal(third.calls.length, 0);
});

test("같은 리뷰·반복이면 두 제공자의 선택지 순서가 같고, 반복마다 섞기를 켜면 반복끼리 달라진다", async () => {
  // Jev 요청 형식이 아직 없어서, 받은 순서를 비교할 수 있게 OpenAI 형식으로 요청하는 jev를 대신 둔다.
  const asOpenai = (id: "jev" | "openai"): Provider => ({ ...PROVIDERS.openai, id });
  const reviews = [review("R0001"), review("R0002")];
  const run = async (permute: boolean) => {
    const f = fakeFetch(() => []);
    const o = options({ fetch: f.fn, providers: [asOpenai("jev"), asOpenai("openai")], reviews, repeats: 3, permuteOrderPerRepeat: permute });
    await runBench(o);
    return f.calls.map((c) => Object.values(c.body.text.format.schema.properties).map((p: any) => p.enum.join()));
  };

  const fixed = await run(false);
  // 순서: R0001 반복1 jev, openai, 반복2 jev, openai ...
  for (let i = 0; i < fixed.length; i += 2) assert.deepEqual(fixed[i], fixed[i + 1]);
  assert.deepEqual(fixed[0], fixed[2]);
  assert.notDeepEqual(fixed[0], fixed[6]);

  const permuted = await run(true);
  for (let i = 0; i < permuted.length; i += 2) assert.deepEqual(permuted[i], permuted[i + 1]);
  assert.notDeepEqual(permuted[0], permuted[2]);
});

test("OpenAI 요청은 7개 키 모두 strict json_schema이고 enum 순서가 기록한 orderSeed의 순서와 같다", async () => {
  const f = fakeFetch(() => []);
  const o = options({ fetch: f.fn });
  await runBench(o);
  const [r] = records(o.storePath);
  const format = f.calls[0].body.text.format;
  assert.equal(format.type, "json_schema");
  assert.equal(format.strict, true);
  assert.equal(format.schema.additionalProperties, false);
  const keys = ["overall", "taste", "freshness", "condition", "packaging", "delivery", "price"];
  assert.deepEqual(Object.keys(format.schema.properties), keys);
  assert.deepEqual(format.schema.required, keys);

  const order = choiceOrder(criteria, r.orderSeed);
  for (const k of keys) assert.deepEqual(format.schema.properties[k].enum, order[k as keyof typeof order]);
  // 프롬프트의 선택지 나열도 같은 순서다.
  const overallBlock = f.calls[0].body.input.split("### overall")[1].split("###")[0];
  const listed = [...overallBlock.matchAll(/^ {2}- (\w+) /gm)].map((m: RegExpMatchArray) => m[1]);
  assert.deepEqual(listed, order.overall);
});

test("평가용 세트는 gold-eval.csv가 없으면 요청 없이 거부한다", async () => {
  const f = fakeFetch(() => []);
  const o = options({ fetch: f.fn, reviews: [review("R0101", "eval")] });
  await assert.rejects(runBench(o), /사람 정답/);
  assert.equal(f.calls.length, 0);
  assert.deepEqual(evalGoldErrors(o.goldEvalPath, ["R0101"]), [`${o.goldEvalPath}가 없다`]);
});

test("평가용 정답이 빈칸이거나 빠진 리뷰가 있으면 거부하고, 다 차 있으면 통과한다", () => {
  const dir = mkdtempSync(join(tmpdir(), "gold-"));
  const path = join(dir, "gold-eval.csv");
  const header = "id,product,text,overall,taste,freshness,condition,packaging,delivery,price,note";
  writeFileSync(path, `${header}\nR0101,a,b,p,p,-,-,-,n,-,\nR0102,a,b,p,,-,-,-,n,-,\n`);
  assert.equal(evalGoldErrors(path, ["R0101", "R0102", "R0103"]).length, 2);
  writeFileSync(path, `${header}\nR0101,a,b,p,p,-,-,-,n,-,\n`);
  assert.deepEqual(evalGoldErrors(path, ["R0101"]), []);
});

test("costUsd = 입력 토큰 × 입력 단가 + 출력 토큰 × 출력 단가, 백만 토큰 기준", async () => {
  const f = fakeFetch(() => [openaiOk(ANSWERS, { input_tokens: 1200, output_tokens: 80 })]);
  const o = options({ fetch: f.fn });
  await runBench(o);
  const [r] = records(o.storePath);
  assert.equal(r.status, "ok");
  // 1200 × 0.75 / 1e6 + 80 × 4.50 / 1e6 = 0.0009 + 0.00036
  assert.ok(r.status === "ok" && Math.abs(r.costUsd - 0.00126) < 1e-12);
  assert.ok(r.status === "ok" && r.priceDate === "2026-11-03");
  assert.deepEqual(r.status === "ok" && r.usage, { inputTokens: 1200, outputTokens: 80 });
});
