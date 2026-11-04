# review-analysis-bench

리뷰 감성 분석 모델(Jev vs OpenAI) 비교 실험(#385)의 분류 기준과 용어만 둔다. 실험 결과 Jev를 쓰기로 했고, 결정과 근거·한계는 [`docs/decisions/product/385-review-sentiment-bench.md`](../../docs/decisions/product/385-review-sentiment-bench.md)에 있다.

| 경로 | 내용 |
|---|---|
| `criteria/criteria.json` | 분류 기준 원본(v0.2). 서비스 연동 때 Jev에 보낼 질문·선택지·안내문 |
| `criteria/CHANGELOG.md` | 분류 기준을 바꾼 이유와 근거 리뷰 |
| `GLOSSARY.md` | 용어와 피할 말 |

비교 실험 코드(실행기, 지표, 보고서, 사람 정답 도구)는 서비스에 쓰이지 않는 일회성 코드라 머지하지 않고 `feature/385` 브랜치에 보관한다. 다시 돌리려면 그 브랜치를 체크아웃한다. 리뷰 원문·정답·실행 기록(`data/`)은 커밋하지 않는다.
