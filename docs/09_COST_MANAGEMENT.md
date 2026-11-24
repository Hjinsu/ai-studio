# 비용 관리
기본 제안: 쇼츠 2000원, 롱폼 10000원, 일 20000원, 월 300000원. 모두 지출 한도이며 실제 단가 아님. 채널/일/월/영상 범위 모두 통과해야 실행.
비용 예약: 동일 트랜잭션에서 bucket들을 정렬해 lock; spent+reserved+estimate <= limit 확인; reserve 및 ledger insert. 완료 시 reserve 해제+actual 정산, 불명 요청 예약 유지 후 확인. 실제 비용이 초과하면 추가 호출 차단, 경고. 중복 정산 금지. 원장 append-only, 환율·시점·가격 버전 저장. 공급자 청구 지연 시 provisional과 confirmed 구분. 외부 공급자 지출 한도도 설정.
수익성: 매출-API-서버-저장소-기타 직접비; 세금/노동비 별도. 수익 미확인 시 0으로 위장하지 않고 NULL. ROI=순수익/비용(비용0이면 NULL).
