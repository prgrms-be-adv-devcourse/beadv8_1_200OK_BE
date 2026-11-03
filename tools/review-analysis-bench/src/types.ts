import type { QuestionKey } from "./labels.ts";

export type { QuestionKey };

export type Sentiment = "positive" | "negative" | "neutral" | "mixed";
export type AspectLabel = Sentiment | "not_mentioned";
export type Answers = { overall: Sentiment } & Record<Exclude<QuestionKey, "overall">, AspectLabel>;

export type ProviderId = "jev" | "openai";

export type Usage = { inputTokens: number; outputTokens: number };

// 제공자마다 확률·신뢰도의 의미가 달라 같은 칸으로 합치지 않고 받은 그대로 둔다.
export type ProviderExtras = { provider: ProviderId; raw: unknown };

export type RunKey = { runId: string; provider: ProviderId; reviewId: string; repeat: number };

type RecordBase = RunKey & {
  set: "tuning" | "eval" | "extra";
  synthetic: boolean;
  model: string;
  criteriaVersion: string;
  orderSeed: number;
  concurrency: number;
  startedAt: string;
  endedAt: string;
  attempts: number;
};

// 실패 기록에는 answers가 없다. 실패를 중립이나 언급 없음으로 바꿔 저장하면 품질 지표가 조용히 틀어진다.
export type RunRecord =
  | (RecordBase & { status: "ok"; answers: Answers; usage: Usage; priceDate: string; costUsd: number; extras: ProviderExtras })
  | (RecordBase & { status: "failed"; error: { kind: "retryable_exhausted" | "fatal" | "invalid_response"; httpStatus?: number; message: string } });

export type Choice = { value: string; label: string; description: string };
export type Question = { key: QuestionKey; label: string; question: string; guide: string; choices: Choice[] };
export type Criteria = { version: string; instruction: string; questions: Question[] };

// 리뷰마다 질문별 선택지 순서. 같은 리뷰에는 두 제공자에 같은 순서를 준다.
export type ChoiceOrder = Record<QuestionKey, string[]>;
