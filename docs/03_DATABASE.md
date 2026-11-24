# DB 논리 모델
공통: BIGINT PK, UTC DATETIME(6), FK/인덱스, utf8mb4 + `utf8mb4_unicode_ci`(ADR-014). JSON은 원본 AI 응답과 비핵심 설정만, 핵심 조회·검증 대상은 정규화(ADR-015). 금액 DECIMAL(19,6), ISO currency. 아래는 논리 계약이며 상세 컬럼은 각 작업 착수 시 확정한다.

Migration: T01은 `V1__initial_baseline.sql`(도메인 테이블 없음), `V2__fix_database_collation.sql`(ADR-014). 도메인 테이블은 T02부터 V3 이후 신규 migration. baselineOnMigrate 비활성, 적용된 migration 수정 금지(ADR-009, `MigrationConventionTest`가 SHA-256으로 검사).
DDL 규칙(ADR-014): 테이블/컬럼에 CHARSET/COLLATE를 모두 생략(DB 기본 상속)하거나 `COLLATE=utf8mb4_unicode_ci`를 명시한다. `DEFAULT CHARSET=utf8mb4`만 쓰면 MariaDB 11.2+는 `utf8mb4_uca1400_ai_ci`를 적용한다(2026-11-25 로컬 11.8.2 확인). `uca1400` 사용 금지.

| 테이블 | 주요 필드/제약 | 결정 / 구현 |
|---|---|---|
| channel | id,name,language,timezone,status(ACTIVE/PAUSED/ARCHIVED),version,archived_at. 예산 시간대는 정책 revision에 둔다 | ADR-034, T02 |
| channel_config | 불변 revision: channel_id,revision_no, 핵심 설정 정규화 컬럼, provider_options_json; unique(channel_id,revision_no) | ADR-034, T02 |
| channel_credential | channel_id,encrypted_token,key_version,scope,external_channel_id | T08 |
| topic | channel_id,title,normalized_hash; unique(channel_id,normalized_hash) | T03 |
| research_source | topic_id,url,retrieved_at,excerpt,license | T04 |
| content | channel_id,topic_id,status,current_revision_id(nullable),version. FK (id,current_revision_id)→content_revision(content_id,id)로 소속 강제 | ADR-020, T03 |
| content_revision | content_id,revision_no,config_version,raw_response_json; unique(content_id,revision_no), unique(content_id,id). 승인된 revision 수정 금지 | ADR-020, T03 |
| content_scene | revision_id,scene_no,narration,visual_prompt,duration_ms; unique(revision_id,scene_no) | T03~T04 |
| content_claim / claim_evidence / claim_verification | 대본 주장, 근거 출처 연결, 검증 결과(정규화) | ADR-015, 상세 설계 필요, T03~T04 |
| media_asset | 파일 단위: storage_key,sha256,type,size,duration_ms,license; unique(sha256) | ADR-016, T03~T05 |
| media_asset_usage | asset_id,revision_id,scene_id,role. 참조 중 asset 삭제 금지 | ADR-016, 상세 설계 필요 |
| ai_provider | code,enabled,secret_ref | T04 |
| ai_model | provider_id,model_code,capability,price_version | T04 |
| ai_request_log | job_id,request_key,external_id,status,usage_json,error_code; unique(request_key) | T04 |
| automation_job | revision_id,scene_id,stage,status,attempt,next_run_at,lease_until,lease_token,dedupe_key; unique(dedupe_key), index(status,next_run_at) | T03 |
| job_dependency | job_id,depends_on_id; composite PK | T03 |
| budget_policy / budget_policy_revision | scope(SYSTEM/CHANNEL/CONTENT_TYPE, T03에 CONTENT),period_type(DAY/MONTH/LIFETIME),status,current_revision_no / revision: limit_krw DECIMAL(24,6), timezone | ADR-017, 026, 029, T02 |
| budget_bucket | policy_id,(content_id T03),period_start,period_end(UTC),timezone,limit_krw,reserved_krw,spent_krw; unique(policy_id,subject,period_start) | ADR-017, 038, T02 |
| budget_reservation / budget_reservation_bucket | request_key(+fingerprint),환율 고정,estimate_krw,status(ACTIVE/RECONCILIATION_REQUIRED/SETTLED/RELEASED),reserved_remaining,recognized_cost / 예약↔bucket 불변 연결 | ADR-026, 028, T02 |
| cost_log | append-only(트리거+운영 권한). event_key(unique)+fingerprint, reservation_id, entry_type 6종, original_amount DECIMAL(24,12), fx_rate DECIMAL(24,10), amount_krw, reserve_delta_krw, cost_delta_krw | ADR-019, 026, 030, T02 |
| exchange_rate | base_currency,quote=KRW,rate DECIMAL(24,10),effective_from,valid_until(기본 72h),source=MANUAL, 불변 | ADR-027, T02 |
| render_output | revision_id,asset_id,manifest_hash | T05 |
| quality_check | revision_id,check_code,result,evidence | T06 |
| content_approval | revision_id,manifest_hash,reviewer_id,decision,created_at | T06 |
| publish_target | channel_id,platform,external_channel_id. 대상별 게시 중복 방지 | ADR-018, T08 |
| publish_job | revision_id,approval_id,target_id,status,scheduled_at. (기존 unique(revision_id) 대체) | ADR-018, T08 |
| publish_attempt | publish_job_id,attempt_no,upload_session_ref,external_video_id,outcome. 시도 이력 보존 | ADR-018, T08 |
| video_analytics | publish_id,date,metric,dimensions_hash,value(nullable); composite unique | T09 |
| admin_user / admin_bootstrap | login,password_hash(BCrypt cost 12),role=ADMIN,enabled,password_changed_at / 최초 생성 단일 행 | ADR-031, 037, T02 |
| audit_event | actor_type,actor_user_id,action,outcome,entity,trace_id,client_ip,details_json, append-only | T02 |
| QRTZ_* | Quartz JDBC JobStore. Flyway로 생성, 자동 초기화 금지 | ADR-022, T03 |
| competitor_channel / competitor_video / trend_keyword | external IDs, metric snapshots, observed_at (P1) | T10 |
관계: channel 1:N topic/content; content 1:N revision; revision 1:N scene/asset_usage/jobs/checks; approval → revision+manifest; publish_job → approval+target; job → cost/request; bucket locks enforce limits. 승인 revision immutable. 삭제는 참조 보존 및 정책상 삭제 의무를 함께 처리.

T02 상세(ERD·제약·인덱스·권한·검증 결과): [design/T02_DATA_MODEL.md](design/T02_DATA_MODEL.md), DDL 초안: [design/T02_DDL_DRAFT.sql](design/T02_DDL_DRAFT.sql).
