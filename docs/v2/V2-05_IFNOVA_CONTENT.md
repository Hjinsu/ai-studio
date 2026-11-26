# V2-05 IFNOVA 콘텐츠 요구사항

AI Studio는 채널 캐릭터·세계관·콘텐츠 성격을 바탕으로 소재 선정, 사실 조사, 스토리와 장면 연출, 이미지·음성·음악·효과·자막, 렌더·품질 검수, 비용·자산 기록 및 게시 승인 대기까지 이어지는 제작 시스템이다. 실제 공급자 호출·지출과 게시는 각각 사용자 승인으로 제어한다.

IFNOVA는 영어권 대상 40~60초 Shorts다. 과학 코미디, 우주 탐험, What If, 극한 환경, 실험, 자연 현상을 등장인물이 겪는 사건으로 구성한다. 상황 코미디·시각적 개그·건조한 유머와 예상 밖 결과를 활용한다. 체험형 과학 상황극 구조를 참고하고 IFNOVA의 외형·이름·대사·음성·고유 연출은 독창적으로 제작한다. [참고 채널 지식코리야](https://www.youtube.com/@koreyaman)

## 채널과 캐릭터

기존 channel config 및 불변 PROFILE item을 사용한다. 콘텐츠 프로필은 humorStyle, humorIntensity, narrationTone, storytellingStyle, editingPace, characterProfiles, visualStyle, musicStyle, subtitleStyle을 버전화한다. IFNOVA 권장값은 DRY_AND_SITUATIONAL, 3/5, DEADPAN, CHARACTER_DRIVEN, DYNAMIC, CINEMATIC_COMEDY다. 2~4개의 자연스러운 농담을 권장하며 반복된 밈·플롯·결말은 품질 검수에서 판단한다.

characterProfiles 배열은 단일·복수 캐릭터를 지원한다. 각 요소는 characterId, characterVersion, characterName, role, personality, appearanceDescription, referenceAssetIds, voiceProfile, speakingStyle, humorStyle, humorIntensity, signatureExpressions, visualStyle, relationshipToOtherCharacters를 가진다. ID는 프로필 내 유일하고 관계는 다른 ID를 참조한다. referenceAssetIds는 고정 파일 hash와 권리 근거를 해석하며 voiceProfile은 공급자·모델·voice ID·언어·스타일을 고정한다. 제작 요청 시 프로필과 참조를 snapshot으로 동결한다. V2-04에서는 이 구조를 수용하며 기존 단일 화자 fixture로 실행한다. 관리 UI는 V2-07에 둔다.

캐릭터 후보 NOVA는 능청스러운 소형 탐사 로봇으로 사실 설명과 수습을 맡고, PIP는 호기심으로 사건을 일으키는 외계 생명체다. 이름·외형·성격·음성은 사용자 승인 전까지 후보다.

## 생성과 검수

- 작가·연출 프롬프트는 첫 1~3초에 사건을 제시하고 채널 인트로를 생략한다. 캐릭터 목적과 행동에서 사건·체험·반응·반전·재치 있는 결말을 이어간다. HOOK→SETUP→ESCALATION→REVEAL→PAYOFF는 선택 구조다. 표정·행동·소품과 대사에 유머를 분산한다. 과학 사실은 RESEARCH 근거와 연결하고 허구·비유·가정을 명시적으로 구분한다.
- 구조화 응답은 sceneId, storyBeat, characterIds, 순서 있는 dialogueSegments(speakerId, dialogue), characterAction, characterExpression, scientificFactRefs, fictionalSituation, visualGag, estimatedDuration을 수용한다. 각 화자의 PROFILE 음성으로 순차 발화를 만들고 실제 디코딩 샘플로 타임라인을 계산한다. 코미디 휴지는 대본/TTS에서 만든다. 기존 요청·해시·영수증·채택 결과를 재작성하지 않도록 prompt/schema/model 버전을 정규 입력에 포함한다.
- 장면 출력은 캐릭터·행동·표정·배경·소품·구도·카메라 preset·visual gag·SFX 타이밍·자막 강조·전환·이미지 프롬프트를 담는다. 장면 간 인과·외형·비율·색상·소품·위치 연속성을 검수한다. 공급자의 공식 레퍼런스 입력 기능을 연동 직전 확인한다. 미지원 공급자는 고정 시각 설명·스타일 및 일관성 표본 검수로 구분해 표시하고 요구 품질을 충족하지 못하면 후보에서 제외한다. 화면비와 해상도는 구조화 미디어 프로필로, 명령·경로는 코드의 허용 프리셋으로 제어한다.
- 실제 IFNOVA Shorts, 근거·권리·비용·manifest·QC 및 미리보기를 인수한다. [품질 정책](05_CONTENT_QUALITY_AND_MONETIZATION.md)의 6항목·100점, 75 이상 사용자 검수, 60~74 부분 개선 최대 2회, 0~59 재기획/폐기와 게시 차단을 유지한다. 캐릭터 일관성, 과학적 근거와 유머·완결성은 콘텐츠 검수에 포함하며 자동 검수 이후 사람 검토를 거친다. 부분 개선 호출도 같은 제작 한도와 기존 비용·복구 계약에 귀속한다.

V2-06은 사람 승인 기반 YouTube 게시, V2-07은 멀티채널·캐릭터/콘텐츠 프로필·소재·예약 관리다. 립싱크·전신 애니메이션·실시간 3D·AI 비디오·정교한 단어별 효과·성과 최적화는 후속 확장이다.
