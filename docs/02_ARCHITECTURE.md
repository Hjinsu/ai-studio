# 시스템 아키텍처
backend/src/main/java/.../{channel,content,research,provider,media,render,job,budget,review,publish,analytics,security,shared}; frontend/src/{pages,features,api,components}; infra/; docs/.
각 모듈은 api/application/domain/infrastructure 분리, 타 모듈 mapper 직접 접근 금지. REST → application → DB; Worker는 DB 큐 claim 후 트랜잭션 밖 외부 호출, 결과 저장 및 후속 작업 등록은 원자적으로 처리. Quartz는 작업 발행 및 lease 회수만 담당. Web/worker 동일 코드의 다른 실행 프로필 가능.
파일 storage/{channelId}/{contentId}/{revisionId}/{assetId}.{ext}, DB에는 상대 키와 SHA256만 저장. 임시 파일 atomic rename, 경로 traversal 금지. DB/파일 백업 일관성 확보. 초기 Docker Compose DB+app+worker, 로컬 개발은 wrapper 및 npm lockfile. DB 큐 lease 상세는 08.
