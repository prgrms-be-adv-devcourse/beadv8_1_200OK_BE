// 생성 배치(data/raw/*.jsonl)를 합쳐 세트를 나누고 data/reviews.jsonl을 만든다.
// 실행: node dataset/split.ts [--seed=<정수>] [--force]
// 한 번 나눈 뒤 사람 정답을 매기기 시작하면 다시 돌리지 않는다. 세트가 바뀌면 정답과 어긋난다.
import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { mulberry32, shuffle } from "../src/random.ts";

// 시드를 위치 인자로 받으면 "--force"가 시드 자리에 들어가 NaN이 되고, 조용히 다른 분할이 나온다.
const seedArg = process.argv.find((a) => a.startsWith("--seed="));
const SEED = seedArg ? Number(seedArg.slice("--seed=".length)) : 20261103;
if (!Number.isSafeInteger(SEED)) {
  console.error(`시드는 정수여야 한다: ${seedArg}`);
  process.exit(1);
}
const unknown = process.argv.slice(2).filter((a) => a !== "--force" && !a.startsWith("--seed="));
if (unknown.length > 0) {
  console.error(`알 수 없는 인자: ${unknown.join(" ")}. 사용법: node dataset/split.ts [--seed=<정수>] [--force]`);
  process.exit(1);
}
const EVAL_GENERAL = 240;
const EXTRA = 600;
const REPEAT_RUN = 50;

type Raw = { product: string; productType: string; text: string; tags: string[]; hardCase: string | null };
type Review = Raw & { id: string; set: "tuning" | "eval" | "extra"; synthetic: true; batch: string; repeatRun: boolean };

const root = new URL("../data/", import.meta.url);
const out = new URL("reviews.jsonl", root);
if (existsSync(out) && !process.argv.includes("--force")) {
  console.error("data/reviews.jsonl이 이미 있다. 정답 작업이 시작됐다면 다시 나누면 안 된다. 정말 다시 나누려면 --force");
  process.exit(1);
}

function load(batch: string): (Raw & { batch: string })[] {
  return readFileSync(new URL(`raw/${batch}.jsonl`, root), "utf8")
    .split("\n")
    .filter((l) => l.trim())
    .map((l) => ({ ...(JSON.parse(l) as Raw), batch }));
}

// 띄어쓰기·문장부호만 다른 리뷰가 세트를 넘나들면 평가가 새므로 정규화한 본문으로 중복을 뺀다.
const normalize = (t: string) => t.replace(/[\s\p{P}\p{S}]/gu, "");
const seen = new Set<string>();
const dedupe = <T extends Raw>(rows: T[]) =>
  rows.filter((r) => {
    const key = normalize(r.text);
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });

const rand = mulberry32(SEED);
const tuning = dedupe(load("hard-tuning"));
const hardEval = dedupe(load("hard-eval"));
const general = shuffle(dedupe(["general-1", "general-2", "general-3", "general-4", "general-5"].flatMap(load)), rand);

if (general.length < EVAL_GENERAL + EXTRA) {
  console.error(`일반 리뷰가 ${general.length}개라 ${EVAL_GENERAL + EXTRA}개에 모자란다. 배치를 보충하고 다시 돌린다.`);
  process.exit(1);
}

const evalRows = shuffle([...hardEval, ...general.slice(0, EVAL_GENERAL)], rand);
const repeatIds = new Set(shuffle(evalRows, rand).slice(0, REPEAT_RUN));

let n = 0;
const make = (r: Raw & { batch: string }, set: Review["set"]): Review => ({
  id: `R${String(++n).padStart(4, "0")}`,
  set,
  synthetic: true,
  repeatRun: repeatIds.has(r as never),
  ...r,
});

const reviews: Review[] = [
  ...shuffle(tuning, rand).map((r) => make(r, "tuning")),
  ...evalRows.map((r) => make(r, "eval")),
  ...general.slice(EVAL_GENERAL, EVAL_GENERAL + EXTRA).map((r) => make(r, "extra")),
];

type Replacement = { id: string; from: string; text: string; tags: string[] };
const { items: replacements } = JSON.parse(
  readFileSync(new URL("replacements.json", import.meta.url), "utf8"),
) as { items: Replacement[] };
for (const rep of replacements) {
  const target = reviews.find((r) => r.id === rep.id);
  if (!target || target.text !== rep.from) {
    console.error(`${rep.id}의 본문이 replacements.json의 from과 다르다. 분할이 바뀌었으면 교체 목록을 다시 정한다.`);
    process.exit(1);
  }
  target.text = rep.text;
  target.tags = rep.tags;
}

writeFileSync(out, reviews.map((r) => JSON.stringify(r)).join("\n") + "\n");
const count = (set: string) => reviews.filter((r) => r.set === set).length;
console.log(
  `시드 ${SEED}: 조정용 ${count("tuning")}, 평가용 ${count("eval")} (어려운 사례 ${hardEval.length}, 반복 실행 ${REPEAT_RUN}), 추가 비교용 ${count("extra")}, 중복 제거 후 남은 일반 리뷰 ${general.length}`,
);
