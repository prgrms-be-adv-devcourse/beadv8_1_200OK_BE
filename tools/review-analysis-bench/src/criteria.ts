import { readFileSync } from "node:fs";
import { QUESTION_KEYS } from "./labels.ts";
import type { Choice, Criteria, Question } from "./types.ts";

const SENTIMENTS = ["positive", "negative", "neutral", "mixed"];
const ASPECT_LABELS = [...SENTIMENTS, "not_mentioned"];

export const CRITERIA_PATH = new URL("../criteria/criteria.json", import.meta.url);

export function loadCriteria(path: URL | string = CRITERIA_PATH): Criteria {
  return parseCriteria(JSON.parse(readFileSync(path, "utf8")));
}

// 선택지 값이 Answers 타입과 정확히 같아야 모델 답을 이 값들로 검사한 결과를 Answers로 믿을 수 있다.
export function parseCriteria(raw: unknown): Criteria {
  const obj = asObject(raw, "criteria.json");
  const version = asString(obj.version, "version");
  const instruction = asString(obj.instruction, "instruction");
  const aspectChoices = parseChoices(obj.aspectChoices, "aspectChoices", ASPECT_LABELS);
  if (!Array.isArray(obj.questions)) throw new Error("criteria.json: questions가 배열이 아니다");

  const keys = obj.questions.map((q) => asObject(q, "questions[]").key);
  if (keys.join() !== QUESTION_KEYS.join()) {
    throw new Error(`criteria.json: 질문 키가 ${QUESTION_KEYS.join(",")} 순서와 다르다 (${keys.join(",")})`);
  }

  const questions: Question[] = obj.questions.map((q, i) => {
    const o = asObject(q, "questions[]");
    const key = QUESTION_KEYS[i];
    const choices =
      key === "overall" ? parseChoices(o.choices, `${key}.choices`, SENTIMENTS) : o.aspect === true ? aspectChoices : null;
    if (!choices) throw new Error(`criteria.json: ${key}는 aspect: true여야 한다`);
    return {
      key,
      label: asString(o.label, `${key}.label`),
      question: asString(o.question, `${key}.question`),
      guide: asString(o.guide, `${key}.guide`),
      choices,
    };
  });
  return { version, instruction, questions };
}

function parseChoices(raw: unknown, where: string, expected: string[]): Choice[] {
  if (!Array.isArray(raw)) throw new Error(`criteria.json: ${where}가 배열이 아니다`);
  const choices = raw.map((c, i) => {
    const o = asObject(c, `${where}[${i}]`);
    return {
      value: asString(o.value, `${where}[${i}].value`),
      label: asString(o.label, `${where}[${i}].label`),
      description: asString(o.description, `${where}[${i}].description`),
    };
  });
  const values = choices.map((c) => c.value);
  if ([...values].sort().join() !== [...expected].sort().join()) {
    throw new Error(`criteria.json: ${where}의 값은 ${expected.join(",")}여야 한다 (${values.join(",")})`);
  }
  return choices;
}

function asObject(v: unknown, where: string): Record<string, unknown> {
  if (typeof v !== "object" || v === null || Array.isArray(v)) throw new Error(`criteria.json: ${where}가 객체가 아니다`);
  return v as Record<string, unknown>;
}

function asString(v: unknown, where: string): string {
  if (typeof v !== "string" || v.trim() === "") throw new Error(`criteria.json: ${where}가 빈 문자열이거나 없다`);
  return v;
}
