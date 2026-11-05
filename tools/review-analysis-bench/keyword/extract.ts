// #387 E1: 리뷰에서 긍정 표현을 자유 추출한다. 목록용 리뷰의 추출 결과로 사람이 키워드 목록 초안을 묶고,
// 측정용 리뷰의 추출 결과로 그 목록이 표현을 얼마나 덮는지 잰다.
// 실행: node --no-warnings --env-file=../../.env keyword/extract.ts --pool list|measure [--model luna|5.5|5.4|terra|mini] [--limit N] [--concurrency N] [--dry-run]
// 같은 명령을 다시 돌리면 아직 성공하지 않은 리뷰만 보낸다.
import { appendFileSync, existsSync, readFileSync, writeFileSync } from "node:fs";
import { parseArgs } from "node:util";
import { parseCsv } from "../src/csv.ts";
import { callWithRetry } from "../src/http.ts";
import { costUsd } from "../src/providers.ts";
import { mulberry32, shuffle } from "../src/random.ts";

// OpenAI 계열만 쓴다. 합성 리뷰를 Claude로 만들어서, 같은 계열 모델이 자기 문체를 잘 읽어 덮는 비율이 부풀려지는 것을 피한다.
// 재현할 수 있게 날짜가 붙은 스냅숏을 고정한다. 가격은 2026-11-05 공식 가격표 기준
const MODELS = {
  mini: { id: "gpt-5.4-mini-2026-03-17", effort: "none", price: { inputPerMillion: 0.75, outputPerMillion: 4.5 } },
  "5.5": { id: "gpt-5.5-2026-04-23", effort: "low", price: { inputPerMillion: 5, outputPerMillion: 30 } },
  "5.4": { id: "gpt-5.4-2026-03-05", effort: "none", price: { inputPerMillion: 2.5, outputPerMillion: 15 } },
  // 5.6은 날짜 붙은 스냅숏이 없어 같은 이름이 나중에 다른 모델을 가리킬 수 있다
  terra: { id: "gpt-5.6-terra", effort: "low", price: { inputPerMillion: 2, outputPerMillion: 12 } },
  luna: { id: "gpt-5.6-luna", effort: "low", price: { inputPerMillion: 0.2, outputPerMillion: 1.2 } },
} as const;
const SPLIT_SEED = 20261105;
const ASPECTS = ["taste", "freshness", "condition", "packaging", "delivery", "price", "other"] as const;

type Review = { id: string; set: string; product: string; text: string };
type Expression = { phrase: string; aspect: (typeof ASPECTS)[number]; evidence: string };
type ExtractRow =
  | { id: string; ok: true; expressions: (Expression & { evidenceFound: boolean })[]; inputTokens: number; outputTokens: number }
  | { id: string; ok: false; message: string };

const { values } = parseArgs({
  options: {
    pool: { type: "string" },
    model: { type: "string", default: "luna" }, // keyword/MODEL-SELECTION.md
    limit: { type: "string" },
    concurrency: { type: "string", default: "4" },
    "dry-run": { type: "boolean", default: false },
  },
  strict: true,
});
const pool = values.pool;
if (pool !== "list" && pool !== "measure") throw new Error("--pool은 list|measure다");
const modelKey = values.model as keyof typeof MODELS;
if (!(modelKey in MODELS)) throw new Error(`--model은 ${Object.keys(MODELS).join("|")}다`);
const model = MODELS[modelKey];

const dataDir = "data/keyword";
const reviews: Review[] = readFileSync("data/reviews.jsonl", "utf8").split("\n").filter(Boolean).map((l) => JSON.parse(l));
const categoryOf = new Map(
  parseCsv(readFileSync(`${dataDir}/product-categories.csv`, "utf8")).slice(1).map(([product, , category]) => [product, category]),
);

// 추가 비교용을 하위 분류마다 반씩 나눈다. 작은 분류가 한쪽에 몰리지 않게 분류 안에서 섞는다.
// 목록용에는 조정용 100개를 더한다. 평가용(R0151 이후)은 키워드 정답용이라 어느 쪽에도 넣지 않는다
function splitPools(): { list: string[]; measure: string[] } {
  const list = reviews.filter((r) => r.set === "tuning").map((r) => r.id);
  const measure: string[] = [];
  const rand = mulberry32(SPLIT_SEED);
  const byCategory = new Map<string, string[]>();
  for (const r of reviews.filter((r) => r.set === "extra")) {
    const category = categoryOf.get(r.product)!;
    if (category === "EXCLUDED") continue;
    byCategory.set(category, [...(byCategory.get(category) ?? []), r.id]);
  }
  for (const category of [...byCategory.keys()].sort()) {
    const ids = shuffle(byCategory.get(category)!, rand);
    const half = Math.ceil(ids.length / 2);
    list.push(...ids.slice(0, half));
    measure.push(...ids.slice(half));
  }
  return { list: list.filter((id) => categoryOf.get(reviews.find((r) => r.id === id)!.product) !== "EXCLUDED"), measure };
}

// 나눈 결과를 파일로 고정한다. 분류 CSV를 고친 뒤 다시 나누면 목록을 만든 리뷰가 측정용으로 옮겨 갈 수 있다
const splitPath = `${dataDir}/pools.json`;
if (!existsSync(splitPath)) writeFileSync(splitPath, JSON.stringify(splitPools(), null, 2) + "\n");
const pools: { list: string[]; measure: string[] } = JSON.parse(readFileSync(splitPath, "utf8"));

const INSTRUCTION = `다음은 농산물·식품 쇼핑몰의 상품 리뷰다. 구매자가 상품에 대해 좋다고 말한 점을 모두 뽑는다.
- 좋다고 말한 점만 뽑는다. 불만, 아쉬운 점, 좋다·나쁘다는 평가 없는 사실은 뽑지 않는다.
- "다행히 터진 건 없었어요", "포대는 안 터졌네요"처럼 걱정한 문제가 없었다는 안도는 좋은 점이 아니므로 뽑지 않는다.
- 리뷰에 쓰여 있는 것만 뽑는다. 짐작해서 채우지 않는다.
- phrase는 상품 상세 화면에 보여 줄 짧은 해요체 문장으로 쓴다. 예: "달아요", "아삭해요", "포장이 꼼꼼해요", "양이 많아요".
- 한 가지 좋은 점은 하나만 뽑는다. 같은 말을 여러 번 해도 하나다.
- evidence는 그 phrase의 근거가 된 리뷰 본문 구절을 그대로 옮긴다.
- aspect는 taste(맛·식감·향), freshness(신선도·변질 없음), condition(크기·양·외관·손상 없음), packaging(포장), delivery(배송 속도·기사 응대), price(가격·가성비), other(그 밖: 조리 편함, 선물용, 판매자 응대 등) 중 하나다.
- 좋다고 말한 점이 없으면 빈 배열을 준다.`;

function buildBody(review: Review) {
  return {
    model: model.id,
    reasoning: { effort: model.effort },
    input: `${INSTRUCTION}\n\n상품: ${review.product}\n리뷰: ${review.text}`,
    text: {
      format: {
        type: "json_schema",
        name: "positive_expressions",
        strict: true,
        schema: {
          type: "object",
          properties: {
            expressions: {
              type: "array",
              items: {
                type: "object",
                properties: {
                  phrase: { type: "string" },
                  aspect: { type: "string", enum: ASPECTS },
                  evidence: { type: "string" },
                },
                required: ["phrase", "aspect", "evidence"],
                additionalProperties: false,
              },
            },
          },
          required: ["expressions"],
          additionalProperties: false,
        },
      },
    },
  };
}

const compact = (s: string) => s.replace(/\s+/g, "");

function parse(body: string, review: Review): Omit<Extract<ExtractRow, { ok: true }>, "id" | "ok"> {
  const res = JSON.parse(body) as {
    output?: { type?: string; content?: { type?: string; text?: string }[] }[];
    usage?: { input_tokens?: number; output_tokens?: number };
  };
  const text = res.output?.filter((o) => o.type === "message").flatMap((o) => o.content ?? []).find((c) => c.type === "output_text")?.text;
  if (typeof text !== "string") throw new Error("output_text가 없다");
  const { expressions } = JSON.parse(text) as { expressions: Expression[] };
  return {
    // 근거 구절이 본문에 없으면 모델이 지어낸 표현일 수 있다. 버리지 않고 표시해 목록을 묶을 때 거른다
    expressions: expressions.map((e) => ({ ...e, evidenceFound: compact(review.text).includes(compact(e.evidence)) })),
    inputTokens: res.usage?.input_tokens ?? 0,
    outputTokens: res.usage?.output_tokens ?? 0,
  };
}

// 모델마다 파일을 나눈다. 목록용과 측정용은 같은 모델의 결과끼리 비교해야 한다
const outPath = `${dataDir}/extract-${pool}-${modelKey}.jsonl`;
const done = new Set(
  existsSync(outPath)
    ? readFileSync(outPath, "utf8").split("\n").filter(Boolean).map((l) => JSON.parse(l) as ExtractRow).filter((r) => r.ok).map((r) => r.id)
    : [],
);
const reviewById = new Map(reviews.map((r) => [r.id, r]));
let targets = pools[pool].filter((id) => !done.has(id)).map((id) => reviewById.get(id)!);
if (values.limit) targets = targets.slice(0, Number(values.limit));

console.log(`pool=${pool} 전체 ${pools[pool].length}개, 완료 ${done.size}개, 이번 ${targets.length}개`);
if (values["dry-run"]) {
  console.log(JSON.stringify(buildBody(targets[0] ?? reviewById.get(pools[pool][0])!), null, 2));
  process.exit(0);
}

const apiKey = process.env.OPENAI_API_KEY;
if (!apiKey) throw new Error("OPENAI_API_KEY가 없다");

let inputTokens = 0;
let outputTokens = 0;
let failed = 0;
async function handle(review: Review) {
  const outcome = await callWithRetry(
    () => fetch("https://api.openai.com/v1/responses", {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${apiKey}` },
      body: JSON.stringify(buildBody(review)),
    }),
    { maxRetries: 3, baseDelayMs: 1000, sleep: (ms) => new Promise((r) => setTimeout(r, ms)) },
  );
  let record: ExtractRow;
  if (outcome.kind !== "ok") {
    record = { id: review.id, ok: false, message: outcome.message };
  } else {
    try {
      record = { id: review.id, ok: true, ...parse(outcome.body, review) };
    } catch (e) {
      record = { id: review.id, ok: false, message: (e as Error).message };
    }
  }
  if (record.ok) {
    inputTokens += record.inputTokens;
    outputTokens += record.outputTokens;
  } else {
    failed++;
  }
  appendFileSync(outPath, JSON.stringify(record) + "\n");
}

const queue = [...targets];
await Promise.all(
  Array.from({ length: Number(values.concurrency) }, async () => {
    for (let review = queue.shift(); review; review = queue.shift()) await handle(review);
  }),
);
const cost = costUsd({ inputTokens, outputTokens }, model.price);
console.log(`끝: 실패 ${failed}개, 입력 ${inputTokens} / 출력 ${outputTokens} 토큰, 약 $${cost.toFixed(4)}`);
