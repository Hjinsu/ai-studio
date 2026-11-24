# 비용 관리
기본 제안: 쇼츠 2000원, 롱폼 10000원, 일 20000원, 월 300000원. 모두 지출 한도이며 실제 단가 아님. 채널/일/월/영상 범위 모두 통과해야 실행.
비용 예약: 동일 트랜잭션에서 bucket들을 정렬해 lock; spent+reserved+estimate <= limit 확인; reserve 및 ledger insert. 완료 시 reserve 해제+actual 정산, 불명 요청 예약 유지 후 확인. 실제 비용이 초과하면 추가 호출 차단, 경고. 중복 정산 금지. 원장 append-only, 환율·시점·가격 버전 저장. 공급자 청구 지연 시 provisional과 confirmed 구분. 외부 공급자 지출 한도도 설정.
수익성: 매출-API-서버-저장소-기타 직접비; 세금/노동비 별도. 수익 미확인 시 0으로 위장하지 않고 NULL. ROI=순수익/비용(비용0이면 NULL).

## 결정 반영 (2026-11-25)
- ADR-017: DB 시간은 UTC. 채널 예산은 채널별 예산 시간대, 시스템 예산은 기본 Asia/Seoul. bucket은 기간 경계(period_start_utc/period_end_utc)와 적용 시간대를 저장하며 시간대 변경은 다음 기간부터 적용. 시스템·채널·콘텐츠 예산을 모두 통과해야 실행하고, 비용 예약과 예산 검사는 하나의 트랜잭션(정렬된 bucket 잠금)에서 원자적으로 처리. 원본 통화·원본 금액·환율(exchange_rate)·KRW 환산 금액을 기록, DECIMAL. 초과 시 BUDGET_EXCEEDED(422).
- ADR-019: cost_log는 append-only 원장. 유형 RESERVE, RELEASE, PROVISIONAL, CONFIRM, ADJUSTMENT, REFUND. CONFIRM은 잠정-확정 차액만 기록, 예약액은 실제 비용에 중복 합산하지 않음. 이벤트마다 고유 event_key(중복 이벤트는 멱등 무시), 기존 unique(request_key,entry_type) 제거. 다회 정산 조정·환불·예상액 초과 정산 지원. 구현 T02(상세안 docs/plans/T02_PLAN.md).
- ADR-026~030, 038 상세(계산식, 상태 전이, 잠금 순서, 시간대 전환, 환율): [design/T02_BUDGET_LEDGER.md](design/T02_BUDGET_LEDGER.md).
