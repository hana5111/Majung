# 마중 (Majung)

**차량에서 출발을 준비하고, 탑승자에게 만날 장소와 도착 예상 시간을 전하는 Pleos 픽업 어시스턴트입니다.**

현대자동차그룹 차량용 앱 공모전을 위한 개인 프로젝트입니다. 
탑승자와 목적지를 정하면 차량 내비게이션의 실제 경로를 확인하고, 도착 예상 시간이 반영된 초대 링크를 준비합니다. 탑승자는 웹에서 약속 내용을 확인하고 수락하거나 거절할 수 있습니다.

<img width="3200" height="1800" alt="majung-onepager" src="docs/assets/majung-onepager.png" />

[**48초 시연 영상 보기**](docs/assets/majung-demo.mp4) · [앱 화면 시안 이미지](docs/assets/majung-app-screens.png)

## 주요 기능

- **즉시 픽업:** 탑승자 이름 입력 → 차량 내비에서 목적지 선택 → 장소 확인 후 출발
- **도착 시간 공유:** 저장된 픽업 위치와 일치하는 실제 안내 경로의 남은 시간을 서버·초대 웹에 반영
- **초대·수락:** 초대 링크 복사, 탑승자 웹의 수락·거절, 차량 화면의 상태 갱신
- **연락처·문자 준비:** 연락처 검색 또는 직접 선택, 받는 사람과 본문 확인 후 기본 문자 앱 열기
- **Gleo 연동:** 구조화된 요청으로 이름·목적지 수집, 최종 확인 후 약속 생성과 내비 검색 연결
- **탑승자 위치 시연:** 같은 약속 화면에서 가상 위치·직선거리를 실제 지도 위에 표시

## 구현 범위

| 구분 | 현재 상태 |
| --- | --- |
| 약속 생성·초대 링크·수락/거절 | Android 앱·서버·웹 연동 구현 |
| 차량 내비 목적지 선택·경로 요청·ETA 전달 | Pleos NaviHelper 연동 구현 |
| 연락처 검색·문자 작성 화면 | 구현. 최종 발송은 사용자가 문자 앱에서 확인 |
| Gleo Action 요청·승인·콜백 | 구현 및 선택 연동 검사 완료. 실제 음성 왕복은 별도 확인 필요 |
| 탑승자 위치·접근 안내 | 시연용 가상 좌표와 안내 문구. 실제 GPS 공유는 미구현 |
| 정차 가능 구역 추천·자동 정차 | 미구현 |

Gleo 음성 연동은 Gleo(`ai.umos.ambientai`)가 설치되어 마중의 `assets/schema.json` 기능을 등록한 환경이 필요합니다.

입력과 장소 확인은 정차 중 이용하는 흐름으로 구성했습니다. 이 앱은 차량 제어나 자동 문자 발송을 수행하지 않습니다.

## 제출본 구성

```text
README.md                소개·실행 방법·검증 범위
.gitignore               로컬 설정·데이터·빌드 결과 제외
pickup-android/          Kotlin·Jetpack Compose 차량 앱
pickup-server/           Node.js 약속 API 서버
pickup-web/              탑승자 초대·수락 웹
```

`pickup-server`가 형제 폴더인 `pickup-web`의 파일을 제공하므로 두 폴더의 상대 위치를 유지해야 합니다. 제출 소스 ZIP에는 위 실행 소스와 테스트를 포함합니다.

**설치 파일:** GitHub Releases에 첨부하는 `Majung-v0.2.1-pleos-debug.apk`를 사용합니다. 같은 버전의 소스 ZIP은 `Majung-v0.2.1-source.zip`입니다.

## 실행 환경

| 항목 | 구성 |
| --- | --- |
| 확인한 에뮬레이터 | Pleos Connect v2.1.0 (2), Android 14 / API 34 |
| Android 빌드 | Gradle 9.6.0 · Android Gradle Plugin 9.5.1 · Kotlin 2.2.10 |
| Java | Gradle daemon JDK 25 (`gradle-daemon-jvm.properties`) |
| Android SDK | compile 37 · target 34 · min 28 |
| 내비 SDK | Pleos NaviHelper 3.0.5 |
| 서버 | Node.js 22.12 이상, 외부 npm 의존성 없음 |

Android Studio와 Pleos 에뮬레이터·차량 내비 환경이 필요합니다. SDK·Gradle 의존성을 처음 내려받을 때 인터넷 연결이 필요합니다. 일반 스마트폰에서는 Pleos 내비 연동을 확인할 수 없습니다.


### 핵심 흐름 

1. 홈 오른쪽 픽업 생성 카드를 누르거나 **예정된 약속 → 약속 만들기**를 선택하고 탑승자 이름을 입력합니다.
2. **만날 장소**를 눌러 안내를 확인한 뒤 차량 내비로 이동합니다.
3. 내비에서 장소를 목적지로 정하고 마중으로 돌아와 **이 장소로 지정**을 누릅니다.
4. **출발하고 초대 준비**를 누릅니다. 경로와 도착 예상 시간이 확인되면 초대 내용을 준비합니다.
5. 받는 사람과 본문을 확인하거나, 상세 화면에서 **초대 링크 복사** 후 PC 브라우저에 붙여 넣습니다.
6. 탑승자 웹에서 수락하면 차량 화면에도 약속 확정 상태가 반영됩니다.

연락처 이름 검색에는 연락처 읽기 권한이 필요합니다. 문자 앱을 연 사실은 발송 완료를 뜻하지 않습니다. 실제 전송에는 연결 휴대폰과 문자 앱에서의 최종 확인이 필요합니다.

**초대 수락 시연**은 문자 발송 없이 가상 위치 화면을 체험하는 기능입니다. 실제 웹 수락·휴대폰 GPS 공유와 구분됩니다.


## 검증 결과

2026-11-15 기준:

- Android 단위 테스트 **57개 통과**
- Pleos 에뮬레이터 선택 검사 **12개 통과**: 종료 대기·연락처/문자·Gleo 스키마/상태/콜백
- Debug·Release APK 빌드 성공. 제출 파일은 Debug APK
- Android `lintDebug`: 오류 0개, 기존 경고 24개
- 서버·웹 검사 **24개 통과**

```sh
# Android 기본 검사
cd pickup-android
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug

# 공유 종료 회귀 검사 (Pleos 에뮬레이터 연결 필요)
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=kr.ddakta.pickup.PickupTripServiceTest

# 서버·탑승자 웹 검사 (저장소 루트의 별도 터미널)
cd pickup-server
npm test
```

전체 Android 연동 검사 중에는 실제 개발 서버에 시험 약속을 만들고 내비로 전환하는 항목이 있습니다. 위 명령은 서버 데이터를 만들지 않는 공유 종료 검사만 선택합니다.

## 외부 리소스

위치 시연 지도는 [Leaflet](https://leafletjs.com/)과 [OpenStreetMap](https://www.openstreetmap.org/copyright)을 사용합니다. 지도 타일 표시에는 인터넷 연결이 필요합니다. Leaflet 라이선스는 [vendor/LICENSE](pickup-android/app/src/main/assets/passenger-map/vendor/LICENSE)에 포함했습니다.
