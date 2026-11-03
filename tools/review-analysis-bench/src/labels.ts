// 사람 정답 시트의 칸과 입력 코드. 스프레드시트에서 한글 입력기를 바꾸지 않고 칠 수 있게 영문 한 글자를 쓴다.

export const QUESTION_KEYS = ["overall", "taste", "freshness", "condition", "packaging", "delivery", "price"] as const;
export type QuestionKey = (typeof QUESTION_KEYS)[number];

export const CODE_TO_LABEL: Record<string, string> = {
  p: "positive",
  n: "negative",
  o: "neutral",
  m: "mixed",
  "-": "not_mentioned",
  "?": "undecidable",
};

export function allowedCodes(key: QuestionKey): string[] {
  // 전체 감성에는 언급 없음이 없다.
  return key === "overall" ? ["p", "n", "o", "m", "?"] : ["p", "n", "o", "m", "-", "?"];
}

// 스프레드시트에서 "-"가 수식으로 바뀌지 않게 "'-"로 입력하면 앞의 작은따옴표가 CSV에 남을 수 있다.
export function normalizeCode(raw: string | undefined): string {
  return (raw ?? "").trim().replace(/^'/, "").toLowerCase();
}

export function validateRow(row: Record<string, string>): string[] {
  const errors: string[] = [];
  for (const key of QUESTION_KEYS) {
    const code = normalizeCode(row[key]);
    if (!allowedCodes(key).includes(code)) {
      errors.push(`${row.id} ${key}: '${row[key] ?? ""}'는 허용되지 않는 코드다 (${allowedCodes(key).join(" ")})`);
    }
  }
  return errors;
}
