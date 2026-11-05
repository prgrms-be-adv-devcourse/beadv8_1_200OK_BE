// 키워드 목록(keywords.json)을 읽고, 어떤 하위 분류 상품에 어떤 키워드를 묻는지 정한다.
import { readFileSync } from "node:fs";
import { mulberry32, shuffle } from "../src/random.ts";

export type Keyword = { id: string; categories: string[]; phrase: string; aspect: string; guide: string };
export type KeywordList = { version: string; instruction: string; keywords: Keyword[] };

export function loadKeywordList(path = "keyword/keywords.json"): KeywordList {
  return JSON.parse(readFileSync(path, "utf8"));
}

export function appliesTo(keyword: Keyword, category: string): boolean {
  return keyword.categories.includes("ALL") || keyword.categories.includes(category);
}

const CHOICES = {
  mentioned: "말함: 리뷰가 이 점을 좋다고 말했다. 표현이 달라도 뜻이 같으면 말한 것이다.",
  not_mentioned: "안 말함: 이 점을 말하지 않았거나, 아쉽다·나쁘다고 말했거나, 좋다·나쁘다는 평가 없이 사실만 적었다.",
};

// 리뷰 하나에 그 분류의 키워드 질문을 모두 담은 Jev 요청 본문을 만든다
export function buildJevBody(list: KeywordList, keywords: Keyword[], review: { id: string; product: string; text: string }) {
  // #386과 같이 리뷰 ID를 시드로 선택지 순서를 섞는다. 선택지가 둘이라 앞쪽 치우침이 곧 오탐이다
  const rand = mulberry32(Number(review.id.slice(1)));
  const questions = Object.fromEntries(keywords.map((k) => [
    k.id,
    {
      type: "choice",
      instructions: `${list.instruction}\n이 리뷰는 "${k.phrase}"라고 말하나?\n판정 기준: ${k.guide}`,
      criteria: Object.fromEntries(shuffle(Object.entries(CHOICES), rand)),
    },
  ]));
  return { model: "jev-1.14.0", state: `상품: ${review.product}\n본문: ${review.text}`, questions };
}
