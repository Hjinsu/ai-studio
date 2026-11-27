# V2-04 최종 통합 및 검증

## 기준 및 충돌

- 통합 브랜치 `v2/04-integration`, worktree `../ai-studio-v2-04-integration`. 기존 Integration `715893fa47a955be399492ddc362b169b528dae3`, Common `d7bec5f9aadcc423d7b2a987ac31d810367b4604`, Safety `5c303a2aebe87222df6d2b5b8aad182cf70a0716`, 최신 원격 Product `925d736225828c720f11bc95569f84a6a17165f3`를 대조했다.
- 지정 Product `d4dc073659617c3ab3aaf6903309359a4ef274ee` 이후 변경은 `STATE.md` 및 제품 보고서의 청취 판정 기록이다. 제품 구현과 테스트 소스는 동일하다. 유일한 merge 충돌 `STATE.md`에는 양쪽 기술 결과를 보존하고 이번 사용자 지시의 청취 대기 상태를 적용했다. Safety는 IMPLEMENTED, Product는 IN_PROGRESS다. 병합 커밋 `c187b76e68e655a66278b6f2212cce928336c1c6`.
- Common의 V13, OpenAPI, 생성 타입은 바뀌지 않았다. 실제 앱 fixture 설정 두 가지와 non-interactive CLI 처리 및 실환경 E2E를 문서화했다. `frontend/e2e/live-media.mjs`는 통합 트리의 추적 파일이다. [공통 계약](../v2/V2-04_COMMON_CONTRACT.md) · [실행 절차](../guides/LOCAL_TEST_GUIDE.md#v2-04-실제-앱-검증)
- 한국어 기본 제작, 채널 언어, 사건 중심 과학 코미디, 움직임·AI 클립·절차 애니메이션의 검토, 초기 2,000원 목표와 캐릭터 BACKLOG를 명시했다. YouTube 자동 번역·더빙과 메타데이터·SRT·채널/게시 승인 경계를 요구사항으로 기록했다. [V2-05](../v2/V2-05_IFNOVA_CONTENT.md) · [V2-06](../v2/V2-06_YOUTUBE_LOCALIZATION.md)

## 통합 검증

- Windows Java 21과 격리 MariaDB 11.8.2에서 `gradlew.bat :backend:test :backend:integrationTest :backend:bootJar`를 실행했다. 통합 262건과 bootJar가 통과했다. Windows 집중 테스트 추가 후 실제 fixture를 유지한 최종 단위 전체 재실행은 328건 통과다. Windows에서 Linux 전용 단위 1건과 선택형 Live Edge 통합 1건이 건너뛰어졌다. fixture 경로 환경변수를 지정해 `ProductMediaIT` 2건과 실제 FFmpeg 검증이 실행됐다. 비용 원장, 단일 정산, 취소·lease 만료·복구 및 V13 격리 마이그레이션 회귀가 포함된다. `AdminCliJarIT`에서 실제 jar의 비대화형 관리자 CLI 종료 코드 2를 확인했다. 앱 E2E 계정은 격리 DB에 직접 생성했다.
- 프런트는 lint·typecheck·build 통과. 백엔드 fixture 렌더와 동시에 돌린 첫 Vitest에서 계획 화면 대기 1건이 실패했다. 전체 `npm test -- --maxWorkers=2` 재실행은 102건 통과. OpenAPI lint와 생성 타입 검사 통과. 기존 경고는 CSRF GET 4XX와 미사용 PageMeta 두 건이다.
- 실제 bootJar·Vite·Edge로 16 GiB `S:/s` 볼륨의 전용 테스트 하위 경로에서 요청 3·7·8·9·13을 순서대로 제작했다. 3개 채널, 5편, 55단계 모두 첫 시도에 성공했다. MP4 재생·seek·다운로드 SHA, 자막 다운로드, 라이트·다크·모바일을 확인했다. 수동 scratch 삭제 없이 각 배치 종료 후 scratch 폴더는 0개이고 가용 공간은 동일했다.
- Windows Job 집중 테스트 6건 통과. 외부 Job이 테스트 JVM 한 개만 허용할 때 자식 생성이 거절됐다. Job의 3 GiB 제한을 조회한 뒤 그보다 큰 단일 메모리 commit이 거절되는 것을 확인했다. 페이지에 데이터를 쓰지 않았다. 운영 보호 코드 변경 없이 테스트를 추가했다.
- 실제 앱에만 worker 2개를 설정했다. 요청 14와 16이 서로 다른 유효 lease 및 scratch를 동시에 보유했다. 첫 완료 뒤 다음 요청의 활성 scratch가 유지됐고 libx264 인코더는 최대 1개였다. 동시 로그인 시 같은 계정의 요청 하나가 429로 거절돼 별도의 격리 계정을 사용했다. 두 요청의 브라우저 검증이 통과했다.
- 요청 17의 활성 FFmpeg 렌더 중 16 GiB 동적 VHD의 초기화하지 않은 NTFS 예약으로 가용 공간을 5,301,600,256 B까지 줄였다. VHDX 백킹 파일 증가는 0 B였다. FFmpeg를 앱이 종료했고 RENDER는 1회차 `MEDIA_PROCESS_FAILED`로 RETRY_WAIT가 됐다. 테스트 파일 해제 후 가용 공간이 원래 값으로 돌아왔다. 재시도 대기를 줄이기 위해 이 요청의 격리 DB retry 시각만 앞당겼다.
- 요청 17은 RENDER 2회차에서 성공했고 브라우저 재생·seek·다운로드를 통과했다. 1회차 scratch는 보존했고 2회차의 임시 파일은 정리했다. 재시도 중 기존 발송·영수증·적용 기록 14건은 유지됐다. 정상 5편, 2-worker 2편과 압박 복구 1편에서 중복 정산 0건을 확인했다.

## 리뷰와 남은 인수

- V13은 이번 격리 DB에만 적용했다. 로컬 앱·Vite·MariaDB를 종료했다. 사용자 DB, 유료 API, YouTube 게시, 운영 지출 또는 기존 브랜치 병합은 작업에 포함되지 않았다.
- 실제 Linux 배포의 전체 제품 렌더는 후속 배포 검증에 남긴다. 미검증 Linux 배포의 미디어 실행 차단을 유지한다. Windows E2E 증거는 Git 밖 `%TEMP%/aistudio-final-v204`에 있다.
- 첫 일반 CI에서 OS별 논리 SansSerif 측정 차이로 Linux 자막 줄바꿈 테스트가 실패했다. 테스트 픽스처 폭을 실제 글꼴 측정으로 설정하고 두 줄 모두 제한 폭을 만족하는지 검사하도록 수정했다. 제품 코드와 자막 제한은 그대로다. CI는 통합 브랜치에 활성화했다. [일반 CI](https://github.com/Hjinsu/ai-studio/actions/workflows/ci.yml?query=branch%3Av2%2F04-integration) · [Linux 격리 CI](https://github.com/Hjinsu/ai-studio/actions/workflows/v2-04-linux-media.yml?query=branch%3Av2%2F04-integration)
- CI 통과 SHA에서 별도 세션의 독립 리뷰를 한 차례 수행한다. 데이터 손실, 중복 과금, 보안·소유권, 장애 복구, 핵심 기능 또는 필수 인수 계약 위반이 차단 대상이다. 최종 청취, 독립 리뷰 APPROVE 및 사용자 병합 승인이 남아 있다.
