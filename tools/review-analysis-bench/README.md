# review-analysis-bench

상품 리뷰의 전체 감성과 여섯 분석 항목 감성을 Jev와 OpenAI 저가형 모델로 분류해 비교하는 실행 도구다(#385). 결과로 "Jev로 충분한가"를 정한다. 백엔드 코드를 import하지 않는 독립 스크립트라서 서비스 모듈에 넣지 않는다. 용어와 피할 말은 [`GLOSSARY.md`](GLOSSARY.md)를 따른다. 판정 규칙의 원본은 `criteria/criteria.json`이고, 사람이 읽기 쉬운 요약은 `dataset/labeling-guide.md`의 판정 요약표다.

Node 23.6 이상에서 `.ts`를 그대로 실행한다. 설치할 의존성은 아직 없다.

## 구성

| 경로 | 내용 | 커밋 |
|---|---|---|
| `criteria/criteria.json` | 분류 기준 원본. Jev·OpenAI 요청을 모두 여기서 만든다. `version`이 분류 기준 버전이다 | O |
| `dataset/generation-prompt.md` | 생성 리뷰 지시문(생성 모델, 비율, 어려운 사례 유형) | O |
| `dataset/labeling-guide.md` | 사람 정답 매기는 절차와 입력 코드 | O |
| `dataset/*.ts` | 세트 나누기, 정답 시트 만들기·검사, 일치도(κ) | O |
| `dataset/replacements.json` | 세트를 나눈 뒤 본문을 바꾼 리뷰와 이유 | O |
| `run/bench.ts` | 두 제공자 호출, `data/runs/<run-id>.jsonl` 기록, 실패분만 다시 실행 | O |
| `run/report.ts` | 실행 기록과 사람 정답으로 품질·속도·비용·채택 판정 보고서(`data/reports/<run-id>.md`) | O |
| `src/` | 공통 코드와 단위 테스트. 제공자별 요청 형식은 `src/providers.ts` 한 곳에 있다 | O |
| `data/` | 리뷰 원문, 사람 정답, 모델 결과, 보고서 | X (`.gitignore`) |

## 진행 상태

| 단계 | 상태 |
|---|---|
| 1. 분석 명세 | `criteria.json` v0.2. 조정용 100개 사람 정답과 고급 모델 점검으로 다듬음(`criteria/CHANGELOG.md`) |
| 2. 데이터셋·사람 정답 | 생성 1,000개, 세트 분리, 조정용 정답 완료. 평가용 정답 대기 |
| 3. 비교 실행 도구 | 실행기·보고서 완료 |
| 4~5. 예비 실행·본 실험 | 아직 |

평가용 300개는 사람 정답을 다 매긴 뒤에 모델에 돌린다. `run/bench.ts`는 `data/labels/gold-eval.csv`가 없거나, 빈칸이 있거나, 정답이 빠진 평가용 리뷰가 있으면 요청을 보내기 전에 멈춘다.

**확인하지 못한 요청 형식.**
- Jev: https://docs.typesafe.ai/api.md 의 POST /v1/systemone 형식으로 작성했다(2026-11-03 확인). 공통 지시문 자리가 없어 질문마다 앞에 붙인다.
- OpenAI: Responses API 형식(`reasoning.effort: "none"`, strict `json_schema`)은 문서 확인 없이 작성했다. 예비 실행 첫 요청에서 응답을 보고 맞춘다.

## 실행 순서

```bash
export OPENAI_API_KEY=... TYPESAFE_API_KEY=...     # .env에 두고 커밋하지 않는다
node --no-warnings run/bench.ts --run-id pilot --set tuning --limit 40          # 예비 실행 40개
node --no-warnings run/report.ts --run-id pilot --gold data/labels/keunhyeok/tuning.csv --pilot
node --no-warnings run/bench.ts --run-id main-c1 --set eval --concurrency 1     # 사람 정답을 다 매긴 뒤
node --no-warnings run/bench.ts --run-id main-c10 --set eval --concurrency 10
node --no-warnings run/bench.ts --run-id repeat --set eval --repeat-run-only --repeats 3 --permute-order-per-repeat
node --no-warnings run/report.ts --run-id main-c1
```

같은 `bench.ts` 명령을 다시 돌리면 실패했거나 아직 안 돈 요청만 보낸다. 예비 실행에서 Jev 전체 감성 정확도가 0.5 미만이면 보고서가 "중단"을 출력하고, 그때는 본 실험 전에 질문을 영어로 바꾸는 방안 등을 검토한다.

## 명령

```bash
node dataset/split.ts                # data/raw/*.jsonl → data/reviews.jsonl (기본 시드 20261103, 분할 뒤 replacements.json 적용)
node dataset/make-sheets.ts <이름>   # 정답 시트 만들기
node dataset/check-sheet.ts <csv>    # 빈칸·잘못된 코드 찾기
node dataset/agreement.ts <A> <B> <결과 이름>   # 항목별 언급 여부 κ·감성 κ, 두 명이면 다른 칸 모으기
node dataset/probe-prompt.ts         # 명세 점검용 질문지 (조정용 100개만, data/probe/prompt-*.md)
node dataset/probe-compare.ts <정답 시트> fable=<답 파일> astra=<답 파일>   # 사람 답과 다른 칸 모으기
node --test src/*.test.ts            # 단위 테스트
```
