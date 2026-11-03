// 명세 점검용 질문지를 만든다. 조정용 리뷰를 고급 모델(채팅 화면)에 넣어, 명세가 애매한 곳을 찾는 데 쓴다.
// 실행: node dataset/probe-prompt.ts [묶음 크기, 기본 50]
// 결과: data/probe/prompt-1.md, prompt-2.md ...
// 정답을 정하는 용도가 아니다. 평가용 리뷰는 정답을 다 매기기 전까지 어떤 모델에도 보내지 않으므로 조정용만 넣는다.
// 생성 태그(hardCase, tags)는 넣지 않는다. 모델이 의도된 답을 보고 고르게 된다.
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";

type Choice = { value: string; label: string; description: string };
type Question = { key: string; label: string; question: string; guide: string; choices?: Choice[]; aspect?: boolean };
type Criteria = { version: string; instruction: string; questions: Question[]; aspectChoices: Choice[] };

const chunkSize = Number(process.argv[2] ?? 50);
if (!Number.isSafeInteger(chunkSize) || chunkSize < 1) {
  console.error("묶음 크기는 1 이상의 정수다.");
  process.exit(1);
}

const criteria = JSON.parse(readFileSync(new URL("../criteria/criteria.json", import.meta.url), "utf8")) as Criteria;
const reviews = readFileSync(new URL("../data/reviews.jsonl", import.meta.url), "utf8")
  .split("\n")
  .filter((l) => l.trim())
  .map((l) => JSON.parse(l) as { id: string; set: string; product: string; text: string })
  .filter((r) => r.set === "tuning");

const choicesOf = (q: Question) => q.choices ?? criteria.aspectChoices;
const questionBlock = criteria.questions
  .map((q) => {
    const choices = choicesOf(q)
      .map((c) => `  - \`${c.value}\` (${c.label}): ${c.description}`)
      .join("\n");
    return `### ${q.key} (${q.label})\n${q.question}\n${q.guide}\n${choices}`;
  })
  .join("\n\n");

const keys = criteria.questions.map((q) => q.key);
const example = JSON.stringify({
  id: "R0000",
  ...Object.fromEntries(keys.map((k) => [k, "<선택지 값>"])),
  reasons: Object.fromEntries(keys.map((k) => [k, "<한 줄 이유>"])),
});

const header = `# 리뷰 감성 판정 (분류 기준 ${criteria.version})

${criteria.instruction}

리뷰마다 아래 질문 ${keys.length}개에 답한다. 각 질문은 아래 선택지 값 가운데 하나만 고른다. 선택지에 없는 값은 쓰지 않는다.

${questionBlock}

## 출력 형식

리뷰 하나에 JSON 한 줄씩, 아래 리뷰 순서대로 출력한다. 설명 문장이나 코드 블록 표시 없이 JSON 줄만 쓴다.
\`reasons\`에는 질문마다 그 값을 고른 이유를 한 줄로 쓴다. 특히 판단이 애매했던 칸은 무엇과 무엇 사이에서 고민했는지 쓴다.

${example}

## 리뷰
`;

const dir = new URL("../data/probe/", import.meta.url);
mkdirSync(dir, { recursive: true });
for (let i = 0; i * chunkSize < reviews.length; i++) {
  const chunk = reviews.slice(i * chunkSize, (i + 1) * chunkSize);
  const body = chunk.map((r) => `- ${r.id} | ${r.product} | ${r.text}`).join("\n");
  writeFileSync(new URL(`prompt-${i + 1}.md`, dir), `${header}\n${body}\n`);
  console.log(`data/probe/prompt-${i + 1}.md (${chunk.length}개, ${chunk[0].id}~${chunk.at(-1)!.id})`);
}
