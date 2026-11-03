import { mulberry32, shuffle } from "./random.ts";
import type { ChoiceOrder, Criteria } from "./types.ts";

// Jev는 앞쪽 선택지를 더 고르는 경향이 있어 리뷰마다 순서를 섞는다.
// 제공자는 시드 계산에 넣지 않는다. 같은 리뷰·반복에는 두 제공자가 같은 순서를 받아야 비교가 공정하다.
export function orderSeed(runSeed: number, reviewId: string, repeat: number, permutePerRepeat: boolean): number {
  return fnv1a(`${runSeed}|${reviewId}|${permutePerRepeat ? repeat : 0}`);
}

export function choiceOrder(criteria: Criteria, seed: number): ChoiceOrder {
  const rand = mulberry32(seed);
  return Object.fromEntries(
    criteria.questions.map((q) => [q.key, shuffle(q.choices, rand).map((c) => c.value)]),
  ) as ChoiceOrder;
}

function fnv1a(text: string): number {
  let hash = 0x811c9dc5;
  for (const byte of new TextEncoder().encode(text)) {
    hash ^= byte;
    hash = Math.imul(hash, 0x01000193);
  }
  return hash >>> 0;
}
