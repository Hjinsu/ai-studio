# V2-04 공통 구현 계약

[V2-04 D01~D10](V2-04_IMPLEMENTATION_PLAN.md) · 담당 Codex · 구현 기준 `715893fa47a955be399492ddc362b169b528dae3`, 승인 설계 `18c9b9484d6bc6637252732adb0063e7808ca960`, migration `V13__create_production_media.sql`.

## 저장소

모두 InnoDB, signed BIGINT 식별자, DATETIME(6) UTC, 삭제는 RESTRICT다. 아래 열은 별도 표시가 없으면 NOT NULL이다. SHA-256은 소문자 64자리 ASCII binary collation, 키·경로는 대소문자를 구분한다. JSON 해시는 버전화된 canonical UTF-8 바이트로 계산하며 원본 문자열을 보존한다.

| 테이블 | 컬럼과 제약 |
|---|---|
| production_media_item | id BIGINT AUTO_INCREMENT PK, production_request_id BIGINT, production_step_id BIGINT, target_key VARCHAR(96), generation INT ≥1, ordinal INT ≥0, kind VARCHAR(24), input_json JSON, input_sha256 CHAR(64), state VARCHAR(24), lease_epoch BIGINT ≥0, attempt_no INT ≥0 DEFAULT 0, operation_id BIGINT NULL, execution_proof_json JSON NULL, execution_proof_sha256 CHAR(64) NULL, recovery_deadline_at DATETIME(6) NULL, failure_code VARCHAR(48) NULL, created_at/updated_at DATETIME(6). 복합 FK (request,step) → production_step(request,id), operation FK → provider_operation(id), UNIQUE(request,step,target,generation), UNIQUE(operation_id), INDEX(request,step,state,ordinal,id), INDEX(state,recovery_deadline_at). |
| production_media_asset | id BIGINT AUTO_INCREMENT PK, item_id BIGINT FK → item(id), role VARCHAR(24), attempt_no INT ≥1, writer_epoch BIGINT ≥1, relative_path VARCHAR(255), mime_type VARCHAR(96), byte_length BIGINT >0, sha256 CHAR(64), lineage_json JSON, rights_json JSON, state VARCHAR(16), created_at DATETIME(6), finalized_at DATETIME(6) NULL. UNIQUE(relative_path), UNIQUE(item_id,attempt_no,role), INDEX(item_id,state,role). FINAL은 역할별 item당 하나를 보장하도록 state=FINAL일 때만 role을 반환하는 nullable generated column과 UNIQUE(item_id,final_role)을 사용한다. FINAL이면 finalized_at 필수다. |

PROFILE은 생성 트랜잭션에서 IMAGE root step의 generation 1, target_key PROFILE, ordinal 0으로 고정한다. 초기 상태 APPLIED, epoch/attempt 0, operation·증거·기한은 NULL이다. PROFILE 예약 키와 kind는 서로만 허용한다. 비-PROFILE ordinal은 1부터 시작하며 단계의 기대 항목 전체를 같은 트랜잭션에 삽입한다. 행의 소유자는 request→step으로 결정하며 파일을 run/revision에 연결하기 전에 request의 연결 및 해시를 확인한다. operation 연결은 기존 비용 트랜잭션에서 request·step·target·generation·input hash 대조 후 설정한다. 새 migration의 trigger로 operation 소유자 불일치, 입력/기존 operation/기존 실행 증거의 수정, 최종 파일 경로·해시·크기·권리의 수정을 거부한다. proof JSON과 hash는 함께 NULL 또는 함께 존재한다. epoch·attempt·상태는 조건부 업데이트와 영향 행 수 확인으로 보호한다.

IMAGE는 SOURCE_IMAGE/NORMALIZED_IMAGE, TTS는 SOURCE_AUDIO/PCM_AUDIO/ALIGNMENT, SUBTITLE은 ASS/SRT, RENDER는 MP4, METADATA는 MEDIA_METADATA, QC는 QC_REPORT/MANIFEST를 사용한다. 파생 권리는 원본 권리와 부모 ID·해시에 연결한다. 권리에는 원본 파일 해시, 출처, 공급자, 라이선스와 근거 참조, 상업 이용·게시·재배포 가능 여부, 표시 조건, 확인자·일시가 필요하다. 적용된 행과 경로는 보존한다. 원본 파일이 사라지면 같은 해시의 복원만 허용하며 다른 해시로 현재 참조를 교체하지 않는다.

## 상태와 장애 복구

- Item은 PENDING에서 ACTIVE로 claim한다. IMAGE/TTS는 기존 비용 엔진의 RESERVED/DISPATCHED/UNKNOWN/SETTLED 상태를 그대로 사용한다. 확정 실행 증거가 들어오면 PROVEN, 파일 묶음 확정 뒤 FINALIZED, 영수증 적용 뒤 APPLIED로 진행한다. 로컬 item은 PROVEN을 건너뛴다. 실패는 FAILED, 사용자 취소는 정산 의무를 처리한 후 CANCELLED다. asset은 STAGED에서 FINAL 또는 ABANDONED로 진행한다.
- 응답의 확정 실행 증거는 operation 소유 item에 먼저 커밋한다. proof에는 effect key·request hash·provider request/result ID·실행 여부·기존 DECIMAL(24,6) 처리 기준의 금액 문자열과 통화·출처 증명을 넣는다. 최종 기존 evidence_json과 receipt는 기존 비용 엔진이 확정한다. 증거 커밋 전 종료는 조회 전용 reconcile로 조사하며 불명 상태와 예약을 유지한다. 이미 취소되었거나 lease가 만료된 경우에도 해당 발송에 귀속되는 늦은 확정 증거를 수용할 수 있다.
- 파일은 같은 볼륨의 고유 attempt 경로에 제한 스트림으로 쓰고 flush·검증 후 STAGED에 해시·경로·크기·권리를 기록한다. 원자 이동의 대상은 attempt별로 분리한다. DB lock 밖에서 move/재검증하고, 현재 epoch·step·staging을 재확인한 트랜잭션에서 전체 묶음을 FINAL 처리한다. FFmpeg 출력 scratch가 다른 볼륨이면 Workspace 내 staging으로 복사 후 같은 볼륨 내에서 원자 이동한다. 덮어쓰기와 비원자 이동은 차단한다.
- 비용 효과 파일의 정상 확정과 RESULT_ADOPTED 최종 증거를 같은 트랜잭션에 연결한다. 복구자는 동일 staging/final을 해시로 찾는다. 형식 불량·권리 불충족·동일 응답으로 복구 불가가 확인되면 즉시 EXECUTED_FAILED로 정산한다. 일시 I/O 실패에는 proof 커밋부터 24시간의 최초 고정 기한을 적용한다. 기한 만료 후 확정 실행 증거로 실패 정산하고 비용·결과 불명은 기존 조사 경로에 둔다. 유료 효과는 파일 복구 시 다시 발송하지 않는다.
- 정산과 적용은 별도 트랜잭션이다. 적용은 현재 epoch에서 기존 receipt와 검증된 묶음을 대조해 item APPLIED 및 receipt applied를 함께 커밋한다. 마지막 item을 적용한 경우에만 상위 step 성공을 허용한다. 취소·stale lease는 신규 dispatch와 채택을 차단하며, 미발송/발송된 비용 처리는 기존 취소·영수증 경로에 남긴다. 취소 시 아직 최종 증거가 없는 확정 실행은 실패 정산하며, 이미 확정된 결과 증거는 보존하고 영수증만 기존 취소 경로에서 처리한다. FFmpeg 미준비 상태에서도 복구 전용 claim은 허용한다. 렌더·로컬 단계의 일시 오류는 새 attempt로 최대 3회 시도한다.
- 기본 24시간 뒤 참조·활성 lease·프로세스가 없는 abandoned scratch와 DB 의도 없는 파일만 정리 목록에 넣는다. 삭제 직전 lock 아래 참조와 lease를 다시 확인하고 대상 root·경로를 검증한다. 검증 불가 경로는 격리한다. RENDER는 MP4를 STAGED 내부 파일로 기록한다. QC는 해당 파일의 해시와 전체 기술 검사를 확인한 뒤 MP4, QC_REPORT, MANIFEST 확정과 QC 성공을 현재 lease에서 한 트랜잭션으로 커밋한다. QC 전 MP4는 목록과 스트리밍에서 숨긴다. 로컬 재계산은 기존 적용 결과를 덮어쓰지 않는다.

## 프로세스와 자원

Windows는 Java 21과 JNA 5.19.1의 jna/jna-platform을 같은 버전으로 고정하는 기준을 사용한다. 공통 계약 착수 시 Java 21·지원 Windows 빌드의 의존성 해석과 호출 ABI를 확인한다. FFM preview는 사용하지 않는다. STARTUPINFOEX + EXTENDED_STARTUPINFO_PRESENT를 사용해 CreateProcessW의 PROC_THREAD_ATTRIBUTE_JOB_LIST로 단일 익명 Job에 생성 시 귀속한다. CREATE_SUSPENDED 상태에서 IsProcessInJob 확인 뒤 ResumeThread한다. Job 핸들은 상속 금지이며 HANDLE_LIST로 표준 스트림 핸들만 허용한다. stdin은 NUL, 출력은 계속 소비한다. KILL_ON_JOB_CLOSE와 JOB_MEMORY를 생성 전에 적용한다. 검증 실패 시 대상 Job 및 해당 프로세스를 종료하고 준비 실패로 전환한다. 취소·timeout·lease 상실 때 TerminateJobObject, bounded wait와 QueryInformationJobObject로 활성 0을 확인한다. 정상 반환에도 활성 0 확인 후 핸들을 닫는다. 사용자 프로세스 이름/경로 기반 sweep은 사용하지 않는다. [Win32 프로세스 생성 속성](https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-updateprocthreadattribute), [JNA 릴리스 변경 기록](https://github.com/java-native-access/jna/blob/master/CHANGES.md)

Linux는 호스트가 제공하는 전용 cgroup v2 경계에서 생성 시부터 실행과 메모리 제한을 적용하고 JVM 종료 때 외부 supervisor가 해당 경계 전체를 정리하는 방식을 사용한다. CI에서 실제 귀속·메모리 상한·timeout·cancel·lease loss·JVM kill·active zero를 검증할 때까지 Linux 미디어 실행은 준비 실패로 처리한다. Windows에서도 생성 경합 구간의 JVM kill·중첩 Job 거절·핸들 누수·독립 프로세스 생존을 확인한다. 스파이크는 FFM Job 종료 모델을 검증했으며 위 JNA 구현과 Linux 시험은 미수행이다.

- 외부 ffmpeg/ffprobe의 절대경로·SHA-256·version·인코더/필터와 encode/decode 및 고정 폰트 smoke를 확인한다. PATH 검색·셸·제품 바이너리 동봉을 금지한다. scratch는 짧은 ASCII 절대경로, attempt 경로 최대 200자. 필터 파일·자산은 고정 상대 별칭이다.
- 렌더 600초, ffprobe 30초, 전체 디코딩 QC 120초, loudnorm 분석 60초. 설정 허용 범위는 렌더 1~600초, ffprobe 1~30초, 디코딩 QC 1~120초, loudnorm 1~60초다. 동시 렌더 초기 1, 최대 2 및 기존 유효 lease 최대 2. 렌더당 Job 메모리 기본/최대 3 GiB, 로그 꼬리 기본/최대 64 KiB. 필수 구조화 probe 출력은 로그 꼬리와 분리해 별도 파싱 상한을 적용한다.
- 매체별 scratch 시작 예약은 최대값 기준으로 1 GiB와 동시 잔존 attempt 포함 산정치 중 큰 값이다. 원본 및 정규화 이미지, PCM·믹스, 자막, QC 임시 공간, 100 MiB MP4와 여유를 포함한다. 원본은 장면당 16 MiB/16 MP, 20장까지, 정규화 입력을 같은 byte 상한으로 제한한다. 전역 예약 합을 원자 계산해 볼륨별 5 GiB 이상 여유를 남긴다. 스트림 쓰기 상한 및 외부 프로세스 출력에 적용되는 볼륨/디렉터리 quota 또는 격리된 제한 볼륨을 준비 조건으로 검증한다. -fs와 쓰기 중 디렉터리 열거 크기에 의존하지 않는다.

## 포트 및 HTTP

| 포트 | 계약 |
|---|---|
| MediaStageWork | 기존 실행 진입에서 stage, request/step/run/revision, epoch, 취소 신호 및 동결 입력으로 작업한다. Claude 구현은 preset·asset ID·시간표로 처리하고 비용·lease 갱신은 공통 포트로 위임한다. |
| MediaStore / MediaEffectPort | Codex 구현. 조회된 immutable item과 expected roles로 bounded staging/finalize/read를 제공한다. 효과 포트는 기존 reserve/dispatch/reconcile/settle/apply를 호출하고 replay 시 provider 호출 없이 같은 결과를 반환한다. |
| MediaProcessRunner | Codex 구현. tool enum, 용도 enum, 개별 argv, 서버 scratch handle, 취소 신호만 수용하고 종료 결과 및 제한된 진단을 반환한다. Claude는 렌더 graph와 ffprobe/QC parser를 소유한다. |

공통 포트의 stage 결과는 기존 실행 성공/재시도/차단/복구 판정으로 매핑한다. product 작업은 실제 산출물 metadata와 검사 결과를 포트에 전달한다. IMAGE/TTS 하위 키는 각각 서버 장면 ID와 종류에서 만들고 ordinal 순서로 처리한다. V2-04 TTS는 장면당 단일 음성이다. 캐릭터 기능과 다중 화자는 BACKLOG이며 빈 characterProfiles를 정상으로 처리한다. 검증 실패는 기존 오류 envelope 및 기술 QC의 대상·코드·측정값으로 전달한다.

목록 GET /api/v1/production-requests/{id}/media는 mode, qc.status 및 공개 asset의 id, stage, role, contentType, byteLength, sha256을 제공한다. 파일 GET/HEAD /api/v1/production-requests/{id}/media/{assetId}는 동일 소유권·run·공개 가능 상태를 검증한다. 기본 inline, download=true면 서버 생성 이름의 attachment. 401 UNAUTHORIZED, 타 소유·미확정·다른 run·부재는 404 RESOURCE_NOT_FOUND. 저장 무결성 실패는 접근 차단 후 동일 404와 내부 진단을 사용한다. 응답은 private, no-store 및 nosniff, Accept-Ranges: bytes를 적용한다.

GET은 단일 closed/open/suffix 범위를 206으로 보내고 파일 끝을 초과한 end는 제한한다. 문법이 유효하지만 만족할 수 없는 단일 bytes 범위는 416과 Content-Range: bytes */length다. 미지원 단위·잘못된 문법·다중 범위·If-Range가 있으면 전체 200이다. HEAD는 Range를 무시하고 같은 인증과 전체 길이·MIME을 반환한다. 파일을 보호된 루트에서 열어 위치 기반 제한 스트림으로 전송한다. 확정·복원 때 전체 SHA-256을 검증하고 serve open에서 크기와 파일 식별·변경 정보를 확인한다. 최초 open 또는 변경 감지 시 전체 검증하고 일치하지 않으면 제공을 막는다. 공개 API 응답에 Workspace 경로·프로세스 로그를 포함하지 않는다.
