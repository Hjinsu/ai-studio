# AI Studio 로컬 서버 실행 가이드

Windows / PowerShell 기준 · T02 기준 · 개발 환경 전용

## 1. 사전 준비 (PC별 최초 1회)

- Git, Java 17 이상(Gradle 실행용), Java 21(애플리케이션 실행용), Node.js 24 LTS, MariaDB 11.8 설치 또는 준비.
- MariaDB 서비스 실행 및 `ai_studio` 전용 DB와 사용자 생성 완료.
- 프로젝트 루트에 `.env` 생성 (`.env.example` 참고). 실제 비밀번호는 Git에 포함하지 않는다.

```dotenv
AI_STUDIO_DB_URL=jdbc:mariadb://localhost:3306/ai_studio
AI_STUDIO_DB_USER=ai_studio
AI_STUDIO_DB_PASSWORD=실제_DB_비밀번호
```

회사 PC의 기존 업무용 DB 및 환경변수는 변경하지 않는다. 집과 회사의 로컬 DB 데이터는 독립적이다.

## 2. 백엔드 실행 (터미널 1)

프로젝트 루트에서 실행한다. 아래 경로는 회사 PC 기준이며 집에서는 실제 경로로 변경한다.

```powershell
Set-Location 'C:\Users\PC-94\Desktop\ai-studio'
.\gradlew.bat :backend:bootRun
```

정상 실행 기준:
- Flyway 마이그레이션 성공 또는 `up to date` (T02 기준 V5)
- `Tomcat started on port 8080`
- `Started AiStudioApplication`

Gradle의 `EXECUTING` 표시는 서버가 계속 실행되는 동안 정상이다. 터미널을 닫지 않는다.

백엔드 확인: 브라우저에서 `http://127.0.0.1:8080/actuator/health` 접속 → `{"status":"UP"}`.

처음 실행하면 Flyway가 마이그레이션을 적용한다. 개발 DB가 맞는지 먼저 확인하며, 이미 적용된 migration은 수정하거나 삭제하지 않는다.

## 3. 최초 관리자 생성 (PC별 최초 1회, 별도 터미널)

이미 관리자 계정이 있으면 생략한다. 프로젝트 루트에서 실행한다.

```powershell
.\gradlew.bat :backend:bootJar
.\gradlew.bat -q javaToolchains
```

현재 터미널의 Java 버전이 17이면 Gradle이 내려받은 JDK 21을 찾아 사용한다.

```powershell
$jdk21 = Get-ChildItem "$env:USERPROFILE\.gradle\jdks" -Directory |
    Where-Object { $_.Name -like '*21*' -and (Test-Path "$($_.FullName)\bin\java.exe") } |
    Select-Object -First 1
if (-not $jdk21) { throw 'javaToolchains에서 JDK 21 설치 경로를 확인하세요.' }
$env:JAVA_HOME = $jdk21.FullName
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
java -version
```

Java 21인지 확인 후 실행한다. JAR 이름이 변경되었다면 `Get-ChildItem backend\build\libs\*.jar`로 실제 이름을 확인한다.

```powershell
java -jar backend/build/libs/backend-0.1.0-SNAPSHOT.jar admin create --login admin
```

비밀번호와 확인 비밀번호를 입력한다(최소 12자, UTF-8 최대 72바이트). 입력 내용이 화면에 나타나지 않는 것이 정상이다. 콘솔 없음 오류가 발생하면 일반 Windows PowerShell 또는 cmd에서 실행한다. 시스템 전체 JAVA_HOME은 변경하지 않는다.

## 4. 프론트엔드 실행 (터미널 2)

```powershell
Set-Location 'C:\Users\PC-94\Desktop\ai-studio\frontend'
npm.cmd ci
npm.cmd run dev
```

`npm.cmd ci`는 최초 실행 또는 package-lock.json 변경 시 수행한다. 평소에는 `npm.cmd run dev`만 실행하면 된다. PowerShell에서 npm.ps1 실행이 차단될 수 있으므로 npm.cmd를 사용한다. 보안 정책을 변경할 필요는 없다.

Vite의 `Local` 주소를 확인한 후 브라우저에서 접속한다. 회사 PC에서 확인된 주소는 **http://localhost:5173** 이다. `127.0.0.1:5173`은 바인딩에 따라 접속되지 않을 수 있다. 프론트 터미널도 계속 열어 둔다.

관리자 아이디와 비밀번호로 로그인한다. 실제 유료 API 연동 승인 전까지 지출 차단을 유지한다.

## 5. 매일 실행 요약

| 터미널 | 작업 디렉터리 | 명령 |
|---|---|---|
| 1 | 프로젝트 루트 | `.\gradlew.bat :backend:bootRun` |
| 2 | `frontend` | `npm.cmd run dev` |
| 브라우저 | — | `http://localhost:5173` (Vite 출력 우선) |

종료할 때 각 서버 터미널에서 `Ctrl+C`를 누른다. MariaDB 서비스는 그대로 유지해도 된다.

## 6. 오류 해결

| 증상 | 조치 |
|---|---|
| npm.ps1 / PSSecurityException | `npm.cmd` 사용 |
| UnsupportedClassVersionError (65.0 / 61.0) | Java 21로 JAR 실행 |
| ERR_CONNECTION_REFUSED | Vite Local 주소 및 서버 실행 여부 확인. localhost와 127.0.0.1 차이 확인 |
| 로그인 또는 API 연결 실패 | 백엔드 health 및 Vite 프록시 확인 |
| DB Access denied | 전용 DB 계정 비밀번호 및 `.env` 확인 |
| Flyway checksum 오류 | 기존 migration 수정 여부 확인. DB 삭제 또는 repair를 임의 실행하지 않음 |
| 관리자 CLI BeanCreationException | 최신 CLI 수정 코드 확인 후 bootJar 재빌드 |

포트 확인:

```powershell
Get-NetTCPConnection -State Listen -LocalPort 8080,5173 -ErrorAction SilentlyContinue |
    Format-Table LocalAddress, LocalPort, OwningProcess
```

## 7. 다른 PC에서 이어서 개발

현재 작업 브랜치를 확인하고 변경사항이 없는 상태에서 `git pull --ff-only`를 수행한다. `.env`, 관리자 계정, 채널, 예산, 환율은 Git으로 동기화되지 않는다. 신규 PC에서는 전용 DB와 관리자 계정을 별도로 준비한다.

이 문서는 로컬 실행용이며 운영 배포 및 통합 테스트용 임시 DB 실행은 별도 절차다. 통합 테스트는 `docs/guides/LOCAL_TEST_GUIDE.md`를 참고한다.
