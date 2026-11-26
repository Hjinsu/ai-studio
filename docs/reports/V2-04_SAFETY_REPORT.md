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
