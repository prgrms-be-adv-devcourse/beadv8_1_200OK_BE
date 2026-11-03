import { appendFileSync, existsSync, mkdirSync, readFileSync } from "node:fs";
import { dirname } from "node:path";
import type { RunKey, RunRecord } from "./types.ts";

export function keyOf(k: RunKey): string {
  return JSON.stringify([k.runId, k.provider, k.reviewId, k.repeat]);
}

export type Store = { latest: Map<string, RunRecord>; skippedLines: number; append(record: RunRecord): void };

// 파일은 덧붙이기만 한다. 같은 키의 기록이 여러 줄이면 뒤에 쓴 줄이 이긴다.
export function openStore(path: string): Store {
  mkdirSync(dirname(path), { recursive: true });
  const latest = new Map<string, RunRecord>();
  let skippedLines = 0;
  if (existsSync(path)) {
    const text = readFileSync(path, "utf8");
    for (const line of text.split("\n")) {
      if (!line.trim()) continue;
      try {
        const record = JSON.parse(line) as RunRecord;
        latest.set(keyOf(record), record);
      } catch {
        // 쓰다가 죽어 잘린 줄이다. 그 키는 기록이 없는 셈이라 다시 돌린다.
        skippedLines++;
      }
    }
    // 잘린 줄 뒤에 바로 이어 쓰면 다음 기록까지 깨지므로 줄을 끊어 둔다.
    if (text.length > 0 && !text.endsWith("\n")) appendFileSync(path, "\n");
  }
  return {
    latest,
    skippedLines,
    append(record) {
      appendFileSync(path, JSON.stringify(record) + "\n");
      latest.set(keyOf(record), record);
    },
  };
}

export function pendingKeys<K extends RunKey>(keys: K[], latest: Map<string, RunRecord>): K[] {
  return keys.filter((k) => latest.get(keyOf(k))?.status !== "ok");
}
