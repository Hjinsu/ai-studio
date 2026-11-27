# V2-06 YouTube 원본 언어와 다국어 제공 요구사항

IFNOVA는 한국어 원본 메타데이터·음성·SRT를 기본으로 검토한다. 채널 언어를 사용하고 YouTube의 자동 번역과 자동 더빙을 다국어 제공의 우선 경로로 검토한다. 공급 채널 또는 언어별 지원과 더빙 결과 품질은 검증 항목으로 둔다. 자체 다국어 TTS는 초기 인수의 필수 조건에서 제외한다.

- 번역 자막, 자동 더빙 트랙, 번역 제목·설명은 각각 별도 기능이다. 글로벌 출력 후보는 번인 없는 영상과 별도 한국어 SRT다. 번인 글자는 번역으로 바뀌지 않는다. [YouTube 자막 도움말](https://support.google.com/youtube/answer/100078)
- Data API에서 원본 제목·설명 언어 `snippet.defaultLanguage`, 음성 언어 `snippet.defaultAudioLanguage`, 검수된 다국어 제목·설명 `localizations`, AI 콘텐츠 고지 `status.containsSyntheticMedia`, 한국어 캡션 트랙 등록을 검토한다. 실제 연동 직전에 각 메서드의 쓰기 가능 속성, 필요한 권한과 할당량을 다시 확인한다. [Videos](https://developers.google.com/youtube/v3/docs/videos) · [Captions insert](https://developers.google.com/youtube/v3/docs/captions/insert)
- 자동 더빙의 채널·영상별 자격과 대상 언어는 Studio에서 확인한다. 공개 전 수동 검수를 우선하고 농담, 과학 용어, 이름과 숫자를 검토한다. 문서화된 Studio 설정·검수·공개 절차를 기준으로 설계한다. 자동 더빙 API 제어 범위와 지원 여부는 착수 직전에 다시 확인한다. [YouTube 자동 더빙 도움말](https://support.google.com/youtube/answer/15569972)
- 승인된 revision과 파일 해시를 확인한 후에만 승인된 비공개 게시를 수행하고 원격 ID를 확인한다. 불명 결과는 중복 전송을 차단한다. 공개 전환과 더빙 공개는 각각 지원 정책과 사용자 승인 범위를 확인한다.

이번 변경은 요구사항 문서에 적용한다. V2-06 착수와 실제 게시에는 별도 승인을 적용한다.
