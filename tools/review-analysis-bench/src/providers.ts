import type { Answers, ChoiceOrder, Criteria, ProviderExtras, ProviderId, Usage } from "./types.ts";

export type ReviewText = { product: string; text: string };
export type Price = { inputPerMillion: number; outputPerMillion: number };
export type Parsed = { answers: Answers; usage: Usage; extras: ProviderExtras };

export type Provider = {
  id: ProviderId;
  model: string;
  apiKeyEnv: string;
  priceDate: string;
  price: Price;
  buildRequest(criteria: Criteria, order: ChoiceOrder, review: ReviewText, apiKey: string): { url: string; init: RequestInit };
  parseResponse(json: unknown, criteria: Criteria): Parsed;
};

// 이 오류만 invalid_response 기록이 된다. 다른 오류는 코드 문제라서 실행을 멈춘다.
export class InvalidResponseError extends Error {}

export function costUsd(usage: Usage, price: Price): number {
  return (usage.inputTokens * price.inputPerMillion + usage.outputTokens * price.outputPerMillion) / 1e6;
}

// 두 제공자가 같은 지시문·질문·안내문·선택지 설명을 받도록 프롬프트는 이 함수 하나로 만든다.
export function renderPrompt(criteria: Criteria, order: ChoiceOrder, review: ReviewText): string {
  const questions = criteria.questions
    .map((q) => {
      const choices = order[q.key]
        .map((value) => q.choices.find((c) => c.value === value)!)
        .map((c) => `  - ${c.value} (${c.label}): ${c.description}`)
        .join("\n");
      return `### ${q.key} (${q.label})\n${q.question}\n${q.guide}\n${choices}`;
    })
    .join("\n\n");
  return `${criteria.instruction}\n\n각 질문에 선택지 값 하나로 답한다.\n\n${questions}\n\n## 리뷰\n상품: ${review.product}\n본문: ${review.text}`;
}

export function parseAnswers(raw: unknown, criteria: Criteria): Answers {
  if (typeof raw !== "object" || raw === null || Array.isArray(raw)) throw new InvalidResponseError("답이 JSON 객체가 아니다");
  const obj = raw as Record<string, unknown>;
  const expected = criteria.questions.map((q) => q.key);
  const extra = Object.keys(obj).filter((k) => !expected.includes(k as never));
  if (extra.length > 0) throw new InvalidResponseError(`모르는 키: ${extra.join(",")}`);
  for (const q of criteria.questions) {
    const v = obj[q.key];
    if (!q.choices.some((c) => c.value === v)) throw new InvalidResponseError(`${q.key}: 선택지에 없는 값 ${JSON.stringify(v)}`);
  }
  return obj as Answers;
}

const openai: Provider = {
  id: "openai",
  model: "gpt-5.4-mini-2026-03-17",
  apiKeyEnv: "OPENAI_API_KEY",
  priceDate: "2026-11-03",
  price: { inputPerMillion: 0.75, outputPerMillion: 4.5 },
  // Responses API 형식은 기억에 의존했고 검증하지 않았다. 예비 실행에서 틀린 곳이 나오면 이 항목만 고친다.
  buildRequest(criteria, order, review, apiKey) {
    const properties = Object.fromEntries(criteria.questions.map((q) => [q.key, { type: "string", enum: order[q.key] }]));
    const body = {
      model: this.model,
      reasoning: { effort: "none" },
      input: renderPrompt(criteria, order, review),
      text: {
        format: {
          type: "json_schema",
          name: "review_sentiment",
          strict: true,
          schema: {
            type: "object",
            properties,
            required: criteria.questions.map((q) => q.key),
            additionalProperties: false,
          },
        },
      },
    };
    return {
      url: "https://api.openai.com/v1/responses",
      init: {
        method: "POST",
        headers: { "Content-Type": "application/json", Authorization: `Bearer ${apiKey}` },
        body: JSON.stringify(body),
      },
    };
  },
  parseResponse(json, criteria) {
    const res = json as {
      status?: string;
      output?: { type?: string; content?: { type?: string; text?: string }[] }[];
      usage?: { input_tokens?: unknown; output_tokens?: unknown };
    };
    const text = res.output
      ?.filter((o) => o.type === "message")
      .flatMap((o) => o.content ?? [])
      .find((c) => c.type === "output_text")?.text;
    if (typeof text !== "string") throw new InvalidResponseError(`output_text가 없다 (status: ${res.status})`);
    let raw: unknown;
    try {
      raw = JSON.parse(text);
    } catch {
      throw new InvalidResponseError(`output_text가 JSON이 아니다: ${text.slice(0, 200)}`);
    }
    const { input_tokens, output_tokens } = res.usage ?? {};
    if (typeof input_tokens !== "number" || typeof output_tokens !== "number") {
      throw new InvalidResponseError("usage.input_tokens/output_tokens가 없다");
    }
    return {
      answers: parseAnswers(raw, criteria),
      usage: { inputTokens: input_tokens, outputTokens: output_tokens },
      // logprobs를 요청하지 않아 OpenAI 쪽에는 확률·신뢰도가 없다.
      extras: { provider: "openai", raw: null },
    };
  },
};

// 알려진 것: Choice 질문마다 choice, probabilities, confidence를 돌려주고, Choice 질문 7개를 요청 하나에 담는다.
// 요청 본문·인증 헤더·응답 형식은 문서로 확인하지 못해 지어내지 않는다.
const JEV_UNVERIFIED =
  "Jev 요청 형식이 검증되지 않았다. https://docs.typesafe.ai/models.md 를 보고 src/providers.ts의 jev buildRequest/parseResponse를 채운 뒤 실행한다.";

const jev: Provider = {
  id: "jev",
  model: "jev-1.14.0",
  apiKeyEnv: "TYPESAFE_API_KEY",
  priceDate: "2026-11-03",
  price: { inputPerMillion: 0.042, outputPerMillion: 0 },
  buildRequest() {
    // 엔드포인트: https://api.typesafe.ai/v1/systemone
    throw new Error(JEV_UNVERIFIED);
  },
  parseResponse() {
    throw new Error(JEV_UNVERIFIED);
  },
};

export const PROVIDERS: Record<ProviderId, Provider> = { jev, openai };
