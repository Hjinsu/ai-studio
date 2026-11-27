# V2-04-SAFETY 구현 및 인수인계

- 기준 `d7bec5f9aadcc423d7b2a987ac31d810367b4604`, `v2/04-safety`, `../ai-studio-v2-04-safety`. Claude 제품 worktree와 단계/wire 인터페이스, OpenAPI, 기존 migration은 동일하다.
- 저장소는 같은 attempt의 플러시된 임시 파일에 대해 단일 no-replace hard-link publication을 수행한다. 검증 후 임시 이름을 제거한다. 프로세스가 그 사이 종료되면 동일 바이트를 검증해 재실행할 수 있다. 기존 `ATOMIC_MOVE`는 대상이 경합 사이 생성됐을 때 플랫폼에 따라 교체할 수 있어 변경했다. 준비 단계에서 저장 볼륨의 hard link를 확인한다. 공유 루트와 상위 경로는 전용 서비스 계정이 관리한다.
- scratch 용량 확인과 attempt 생성은 같은 admission 잠금에서 수행한다. 정리는 실행 잠금과 admission 잠금 아래 수행한다. 오래된 디렉터리 안에 최근 변경 파일이 있으면 보존한다. 다중 Range 헤더 행은 전체 200으로 응답한다.
- Linux launcher는 Java가 systemd user service의 실제 MainPID이고 같은 cgroup에 속할 때만 사용한다. systemd transient service에 시작부터 격리하며 BindsTo/After, KillMode=control-group, ExitType=cgroup, MemoryMax=3 GiB, swap 0, 최대 task 수, stop 및 runtime 제한을 설정한다. Java는 준비되지 않은 supervisor에 직접 실행으로 우회하지 않는다. 종료 시 transient service를 정지시키고 단위 및 cgroup 상태를 확인한다. [systemd-run](https://www.freedesktop.org/software/systemd/man/latest/systemd-run.html) · [systemd.unit](https://www.freedesktop.org/software/systemd/man/latest/systemd.unit.html) · [systemd.kill](https://www.freedesktop.org/software/systemd/man/latest/systemd.kill.html)

## 검증 및 인수 범위

- Windows: Job 생성·파이프·timeout·취소·JVM 강제 종료와 별도 프로세스 생존을 런처 테스트에서 확인한다. 실제 FFmpeg 인수 자산의 렌더와 볼륨 준비는 제품 결합 테스트에서 검증한다.
- Linux: `v2/04-safety`의 `V2-04 Linux media isolation` 워크플로가 systemd user manager 아래 메모리 제약·프로세스 그룹 종료·JVM 강제 종료를 검사한다. Windows 개발 환경에서는 Linux 테스트가 건너뛰어진다. 현장 설치는 검증 전 `ai-studio.production.media.linux-protection-verified=false`를 유지한다. 검증된 배포 환경에서만 `linux-owner-service`에 JVM의 systemd user service 이름을 설정하고 플래그를 활성화한다. Windows와 공유하는 미디어 opt-in, 해시·폰트·볼륨 준비 조건도 필요하다.
- DB: V13만 포함한 공통 baseline의 기존 스키마를 격리 MariaDB에서 사용한다. 파일 공개 후 DB 확정 rollback, 응답 후 파일 저장 실패 및 렌더 비가용 중 취소, stale lease의 파일 등록 차단·replacement epoch 정산, 다른 제작 요청의 자산 ID·해시 훼손·Range 경계를 검증한다.
- 통합 담당자는 이 보고서와 안전 브랜치 원격 SHA를 STATE/TASKS에 반영한다. 제품 브랜치와 결합 후 독립 리뷰를 한 차례 진행한다.

## 실행 결과 (2026-11-27 KST)

| 검증 | 결과 | 근거 |
|---|---|---|
| Windows | PASS | Java 21·JNA Job 테스트 4건. 생성·출력, timeout, 취소 시 무관 프로세스 생존, JVM 강제 종료 시 소유 자식 종료를 확인했다. |
| Linux CI | PASS | Ubuntu 24.04 systemd user supervisor에서 실제 테스트 1건을 실행했다. cgroup 귀속과 memory.max, timeout·취소, JVM 강제 종료 후 자식·손자 종료를 확인했다. XML 검사는 건너뜀 0건을 강제한다. [CI 45855955374](https://github.com/Hjinsu/ai-studio/actions/runs/45855955374) |
| 단위 및 격리 MariaDB | PASS | `gradlew.bat :backend:test :backend:integrationTest :backend:bootJar` 단위 298건과 DB 통합 258건 통과. 별도 `:backend:integrationTest --tests '*PlanningExecutionIT.mediaCleanupPreservesLeasedAndRecentlyWrittenScratch'` 1건 통과. V13은 격리 MariaDB에만 적용했다. |
| Gitleaks·diff 검사 | PASS | staged 검사와 `git diff --cached --check` 통과. |
| 개발 머신의 Linux와 Live Edge | NOT_TESTED | Linux 런처는 위 CI에서 별도로 검증했다. 이번 격리 DB 회귀는 Live Edge 플래그를 사용하지 않았다. |
| 실제 배포와 렌더 인수 | NOT_TESTED | Windows 중첩 Job 거절·메모리 초과 및 실제 scratch 볼륨 압박, 배포별 Linux 보호, 권리 확인 자산으로 실제 두 편 렌더는 결합 인수에 남는다. |

FAIL 0건. 테스트 대상 코드의 SHA는 `6beeabada605c6a79ac06c95f57286212dbd4016`이다. 이후 검증 기록 커밋에는 문서 변경만 포함한다. 임시 MariaDB는 종료했다.

## Scratch 예약 수명주기 수정 (2026-11-27 KST)

- 작업 기준은 Safety `59f22c8ab026029fe2a6c2eb20e2666f6c0a51ec`, Common `d7bec5f9aadcc423d7b2a987ac31d810367b4604`, Product `1c526763d3b14b5f5721bb451e2bc09c6f669a2a`다. Claude 보고서 §0.5에서 24시간 보존 중인 attempt 디렉터리에도 계속 1 GiB를 예약해 후속 제작이 막힌 원인을 확인했다.
- 각 활성 scratch는 현재 JVM의 worker가 소유한다. 동일 단계의 다음 item으로 넘어가거나 동기 단계·영속화가 반환되면 소유 worker가 해제한다. 성공 item은 Workspace의 필수 파일 해시와 비용 실행 증거를 확인한다. 해시 검사는 DB 잠금 밖에서 하고, 삭제 직전 요청 잠금 아래 epoch·item·자산 참조를 재확인한다. lease 만료만으로 로컬 예약을 해제하지 않는다.
- 활성 예약의 1 GiB 차감과 실제 가용 공간 확인, 예약 반환을 같은 잠금에서 처리한다. 보존 또는 삭제 실패로 남은 바이트는 실제 가용 공간에서 이미 차감된다. 실패·취소·중단 파일은 24시간 보존 후 활성 lease·로컬 writer·복구 필요 여부를 재검사한다. 프로세스 종료 검증 실패는 실행기 실패 상태로 전환한다. [공통 계약](../v2/V2-04_COMMON_CONTRACT.md)
- Java 21과 격리 MariaDB 11.8.2에서 `gradlew.bat :backend:test :backend:integrationTest :backend:bootJar` 통과. 단위 298건, 통합 260건, 실패 0건. Windows에서 Linux 전용 1건, 통합에서 선택 실행하는 기존 Live Edge 1건이 건너뛰어졌다. 오래된 lease·최근 파일·로컬 writer·소유자 미확인 scratch 보존, Workspace 해시 불일치, Windows 삭제 공유 거부, 만료 epoch의 파일 채택 차단 및 취소 후 한 번 정산을 검증했다.
- 별도 검증 worktree에서 Safety와 지정된 Product SHA의 미디어 제품 소스만 조합해 bootJar를 만들었다. Vite·Edge, FAKE/로컬 fixture, 격리 MariaDB, 실제 FFmpeg Windows Job을 사용했다. 16 GiB 전용 볼륨 `S:/s` 아래 테스트 루트 `S:/s/v204-verify`에서 요청 13의 실제 렌더 중 JVM을 강제 종료했다. FFmpeg는 함께 종료됐으며 중단된 scratch를 유지했다. 재시작한 앱이 lease epoch 2→3에서 RENDER 2회차로 복구했고 MP4 재생·seek·다운로드가 성공했다. 발송·영수증·적용 기록 14건은 동일했고 중복 정산은 0건이었다.
- 최종 bootJar에서 요청 15·16·17·18·20을 3개 채널에 걸쳐 연속 제작했다. 소재 선택부터 완료까지 63~75초였으며, 각 요청의 11개 단계가 모두 첫 시도에 성공했다. Edge에서 영상 재생·seek, MP4 해시 대조와 자막 다운로드를 확인했다. 배치 경계의 가용 공간은 세 번 모두 16,959,602,688 B였다. 정상 attempt는 모두 정리됐고 마지막까지 중단 샘플 하나만 보존됐다. 다섯 편 사이 수동 scratch 삭제는 없었다.
- 압박 시험에서 10 GiB 테스트 파일로 실제 가용 공간을 6,222,184,448 B로 낮췄다. 요청 24는 TTS 3회의 시도에서 모두 `MEDIA_SCRATCH_UNAVAILABLE`로 차단됐으며 TTS 발송 0건으로 FAILED가 됐다. 5분 기본 대기 대신 격리 DB에서 이 요청의 retry 시각만 앞당겼다. 10 GiB 테스트 파일을 반환한 뒤 요청 30이 12개의 소형 잔존 디렉터리를 그대로 둔 상태에서 완료됐다.
- JVM 중단 샘플의 파일 변경 시각을 25시간 전으로 설정한 뒤 다음 준비 호출에서 자동 정리되는 것을 확인했다. 다른 기존 `S:/s` 디렉터리는 테스트 루트 밖에 있었다.
- 요청 31의 실제 렌더 도중 취소 API를 호출했다. FFmpeg가 종료됐으며 요청이 CANCELLED로 닫혔다. 활성 lease, 미확정·미적용 비용 효과 및 공개 MP4는 각각 0건이었다. 취소 scratch는 보존 조건에 따라 유지됐다.
- 신규 migration, OpenAPI 및 제품 브랜치 병합 없음. 격리 DB에만 V13을 적용했으며 테스트 앱과 DB를 종료했다. 실제 앱의 2-worker 동시 렌더, Linux에서의 전체 제품 렌더와 실행 중 5 GiB 하한 교차 시험은 미수행이다.
