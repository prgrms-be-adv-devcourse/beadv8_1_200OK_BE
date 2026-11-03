# review-analysis-bench

상품 리뷰의 전체 감성과 여섯 분석 항목 감성을 Jev와 OpenAI 저가형 모델로 분류해 비교하는 실행 도구다(#385). 결과로 "Jev로 충분한가"를 정한다. 백엔드 코드를 import하지 않는 독립 스크립트라서 서비스 모듈에 넣지 않는다. 용어는 저장소 루트의 `GLOSSARY.md` "리뷰 분석" 절을 따른다.

Node 23.6 이상에서 `.ts`를 그대로 실행한다. 설치할 의존성은 아직 없다.

## 구성

| 경로 | 내용 | 커밋 |
|---|---|---|
| `criteria/criteria.json` | 분류 기준 원본. Jev·OpenAI 요청을 모두 여기서 만든다. `version`이 분류 기준 버전이다 | O |
| `dataset/generation-prompt.md` | 생성 리뷰 지시문(생성 모델, 비율, 어려운 사례 유형) | O |
| `dataset/labeling-guide.md` | 사람 정답 매기는 절차와 입력 코드 | O |
| `dataset/*.ts` | 세트 나누기, 정답 시트 만들기·검사, 일치도(κ) | O |
| `dataset/replacements.json` | 세트를 나눈 뒤 본문을 바꾼 리뷰와 이유 | O |
| `src/` | 공통 코드와 단위 테스트 | O |
| `data/` | 리뷰 원문, 사람 정답, 모델 결과 | X (`.gitignore`) |

## 진행 상태

| 단계 | 상태 |
|---|---|
| 1. 분석 명세 | `criteria.json` v0.1 초안. 조정용 100개로 다듬는 중 |
| 2. 데이터셋·사람 정답 | 생성 1,000개, 세트 분리 완료. 사람 정답 대기 |
| 3~5. 도구·예비 실행·본 실험 | 아직 |

평가용 300개는 사람 정답을 다 매긴 뒤에 모델에 돌린다.

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
