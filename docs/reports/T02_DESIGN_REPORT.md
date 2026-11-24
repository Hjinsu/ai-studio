# T02 상세 설계 보고서 (ChatGPT 설계 검토용)
작성 2026-11-25 · 작성 Claude Code · 상태: **설계 초안 완료, 구현 미착수(도메인 코드 0줄)** · 커밋 없음

## 1. 지시 이행 결과
| # | 지시 | 결과 | 산출물 |
|---|---|---|---|
| 1 | D-01~D-13 반영 | ADR-026~038로 기록, 관련 설계 문서 갱신 | DECISIONS.md, docs/03, 04, 09, 12, 13, 14, 16 |
| 2 | T02_PLAN 갱신 | 확정 사항 매핑, 구현 순서, 의존성, 테스트 매핑, 결정 요청 T2-Q1~Q14 | docs/plans/T02_PLAN.md |
| 3 | ERD·컬럼·FK·UNIQUE·CHECK·인덱스·DDL | 12 테이블, 트리거 12개, V3~V5 구획 | docs/design/T02_DATA_MODEL.md, T02_DDL_DRAFT.sql |
| 4 | OpenAPI 3.1 전체 초안 | 경로 29개·작업 35개(인증·대시보드·채널·설정·정책·사용량·정합성·예약·원장·환율) | docs/api/openapi.yaml |
| 5 | 상태 전이·계산식·잠금 순서·정책 변경 규칙 | 상태×이벤트 표, 계산식과 수치 예시, 전역 잠금 순서, 시간대 전환 알고리즘 | docs/design/T02_BUDGET_LEDGER.md |
| 6 | 관리자 CLI·인증 흐름 | CLI 실행 방식·비밀번호 규칙·동시 생성 방지, 필터 구성, 시퀀스, rate limit | docs/design/T02_AUTH.md |
| 7 | 의존성 호환성 | Security/Caffeine BOM, 계약 검증 라이브러리 스파이크, openapi-typescript 충돌 확인 | T02_PLAN 3절, docs/16_SOURCES.md |
| 8 | 보고서 | 본 문서 | — |

## 2. 검증 (설계 단계에서 실제 실행한 것)
| 대상 | 방법 | 결과 |
|---|---|---|
| DDL 초안 | **별도 임시 MariaDB 11.8.2 인스턴스**(127.0.0.1:3307, scratch 데이터 디렉터리, 기존 3306 서버·데이터 미사용, 검증 후 삭제)에 V1→V2→초안 적용 | 테이블 12, 트리거 12 생성. 문자열 컬럼 모두 unicode_ci(JSON 2개는 utf8mb4_bin 자동) |
| 제약·트리거 | 정상/위반 INSERT·UPDATE·DELETE 42건 | 42/42 기대대로(최종 DDL로 재검증) |
| 운영 권한 모델 | 앱 계정(원장 SELECT/INSERT) / migration 계정 생성 | 앱 계정 cost_log UPDATE·DELETE·CREATE 거부(1142), bucket UPDATE 허용. migration 계정 SUPER 없이 트리거 생성(binlog OFF) |
| OpenAPI | Redocly CLI 2.83.0 lint | 유효(경고 1: 공개 CSRF 엔드포인트 4xx 없음, 의도) |
| 계약 검증 라이브러리 | openapi-request-validator 3.0.0 + Boot 4.1.3 BOM 스파이크 | 3.1 키워드(type 배열, const, not, if/then, pattern, additionalProperties) 15건 + Spring 7 MockMvc matcher 2건 = 17/17 |
| TS 타입 생성 | openapi-typescript 7.13.0 | 프로젝트 설치는 ERESOLVE(peer TS ^5). TS 5.9.3 격리 디렉터리에서 생성(1,903줄) → 프로젝트 TS 6.0.3 strict 검사 통과, 금액을 number로 쓰면 컴파일 오류 확인 |

## 3. 설계 중 발견한 문제와 반영
1. **REGEXP 대소문자 무시**: unicode_ci 컬럼에서 `REGEXP '^[a-z]...'`가 `Admin2`를 통과(실측). 로그인·통화·fingerprint·reason 등 CHECK에 `(?-i)` 적용, 재검증. T02 이후 DDL 규칙으로 문서화.
2. **DECIMAL 초과 자릿수 무경고 반올림**: STRICT 모드에서도 Note 1265만 발생(실측). 앱이 API 입력 scale을 검증하고 반올림 규칙(예약 CEILING, 정산 HALF_UP)을 직접 적용하도록 명시. OpenAPI 패턴으로 자릿수 초과를 VALIDATION_FAILED.
3. **swagger-request-validator 좌표 이전**: 3.0.0부터 `openapi-request-validator-*`. 새 좌표로 고정 제안.
4. **openapi-typescript peer 충돌**: TS 6과 공식 비호환. 격리 도구 디렉터리 방식 제안(T2-Q2).
5. **차액 이벤트의 원본 통화 금액**: CONFIRM/PROVISIONAL을 차액으로 기록하려면 원본 통화 누적 인식액이 필요 → `recognized_cost_original` 컬럼 추가.
6. **JSON 컬럼 collation**: MariaDB JSON 별칭은 utf8mb4_bin 자동 → collation 규칙의 허용 예외로 문서화.

## 4. 승인 결정 대비 설계에서 추가·구체화한 부분 (검토 요청)
| 항목 | 내용 | 근거 |
|---|---|---|
| RELEASED → SETTLED | 해제 후 지연 과금 발견 시 CONFIRM 허용(reserve 0, 비용 기록) | ADR-028 "실제 비용 반드시 기록" (T2-Q10) |
| 전환 bucket | 시간대 변경 시 자투리 구간을 다음 완전한 기간에 합침 | ADR-038 중복·누락·우회 방지 (T2-Q11) |
| 정책 한도 변경 즉시 적용 | 열린 bucket의 limit_krw 갱신(시간대만 다음 기간) | ADR-038은 시간대만 명시 |
| admin_bootstrap 단일 행 | 최초 관리자 동시 생성 방지용 테이블 | ADR-031 |
| 세션 검증 필터 | password_changed_at 이후 기존 세션 무효 | 재설정 CLI가 별도 프로세스이므로 |
| 예약 생성 API 없음 | 예약은 내부 서비스(T03~T04 워커), 관리자는 확정·조정만 | 외부 호출 경로와 분리 |
| 사용량 API 경로 | `/budget/usage?channelId=`가 v0.1의 `/channels/{id}/budget` 대체 | 시스템·채널 공통 |
| 채널 설정 핵심 컬럼 | 기본 콘텐츠 유형, 쇼츠·롱폼 길이 범위, 합성 고지 기본값 | ADR-034 "핵심 설정 정규화" (T2-Q1) |

## 5. 결정 요청
T02_PLAN 5절 **T2-Q1~T2-Q14**. 구현 전 반드시 필요한 것: T2-Q1(채널 설정 컬럼), T2-Q2(타입 생성 방식), T2-Q3(계약 검증 라이브러리), T2-Q4(비활성 채널 예약), T2-Q5(격리 수준), T2-Q6(쿠키 이름), T2-Q10·Q11(설계 추가분 승인).

## 6. 미검증·알려진 한계
- 동시성(잠금 순서, bucket 동시 생성, 데드락 재시도)은 설계만, 구현 후 통합 테스트로 검증.
- DDL은 MariaDB 클라이언트로 적용 검증, Flyway 경유 적용·CI(11.8.8)는 구현 시.
- 운영 binlog ON 환경의 트리거 생성 권한 미검증(T2-Q12).
- 로그인 제한은 재기동 시 초기화, 단일 인스턴스 한정, 프록시 뒤 IP 판정 한계(ADR-036 알려진 한계).
- BCrypt cost 12 성능은 구현 시 측정.

## 7. T01 상태
커밋 `55e33ec` CI run 45440907314 전 job success(상세: T01_REPORT.md). 저장소 규칙상 T01 DONE은 승인 후 전환 — 현재 IMPLEMENTED(CI 통과, DONE 승인 대기).
