# DB 논리 모델
공통: BIGINT PK, UTC DATETIME(6), FK/인덱스, utf8mb4, JSON은 비핵심 설정만. 금액 DECIMAL(19,6), ISO currency. 아래는 논리 계약이며 migration은 T01~T03에서 작성.
| 테이블 | 주요 필드/제약 |
|---|---|
| channel | id,name,language,timezone,status |
| channel_config | channel_id,version,config_json; unique(channel_id,version) |
| channel_credential | channel_id,encrypted_token,key_version,scope,external_channel_id |
| topic | channel_id,title,normalized_hash; unique(channel_id,normalized_hash) |
| research_source | topic_id,url,retrieved_at,excerpt,license |
| content | channel_id,topic_id,status,current_revision_id,version |
| content_revision | content_id,revision_no,config_version,script_json; unique(content_id,revision_no) |
| content_scene | revision_id,scene_no,narration,prompt,duration_ms; unique(revision_id,scene_no) |
| media_asset | revision_id,scene_id,type,storage_key,sha256,license,duration_ms |
| ai_provider | code,enabled,secret_ref |
| ai_model | provider_id,model_code,capability,price_version |
| ai_request_log | job_id,request_key,external_id,status,usage_json,error_code; unique(request_key) |
| automation_job | revision_id,scene_id,stage,status,attempt,next_run_at,lease_until,lease_token,dedupe_key; unique(dedupe_key), index(status,next_run_at) |
| job_dependency | job_id,depends_on_id; composite PK |
| budget_policy | channel_id,period_type,limit_amount,currency |
| budget_bucket | policy_id,period_start,reserved,spent; unique(policy_id,period_start) |
| cost_log | job_id,request_key,entry_type,amount,currency,price_version; unique(request_key,entry_type) |
| render_output | revision_id,asset_id,manifest_hash |
| quality_check | revision_id,check_code,result,evidence |
| content_approval | revision_id,manifest_hash,reviewer_id,decision,created_at |
| publish_job | revision_id,approval_id,external_video_id,status,scheduled_at,upload_session_ref; unique(revision_id) |
| video_analytics | publish_id,date,metric,dimensions_hash,value; composite unique |
| admin_user | login,password_hash,role,enabled |
| audit_event | actor,action,entity_id,created_at,details |
| competitor_channel / competitor_video / trend_keyword | external IDs, metric snapshots, observed_at (P1) |
관계: channel 1:N topic/content; content 1:N revision; revision 1:N scene/assets/jobs/checks; approval → revision+manifest; publish → approval; job → cost/request; bucket locks enforce limits. 승인 revision immutable. 삭제는 참조 보존 및 정책상 삭제 의무를 함께 처리.
