# 마중

**차량에서 출발을 준비하고, 탑승자에게 만날 장소와 도착 예상 시간을 전하는 Pleos 픽업 어시스턴트입니다.**

현대자동차그룹 차량용 앱 공모전을 위한 개인 프로젝트입니다. 
탑승자와 목적지를 정하면 차량 내비게이션의 실제 경로를 확인하고, 도착 예상 시간이 반영된 초대 링크를 준비합니다. 탑승자는 웹에서 약속 내용을 확인하고 수락하거나 거절할 수 있습니다.

<img width="3200" height="1800" alt="majung-onepager" src="docs/assets/majung-onepager.png" />

[**시연 영상**](docs/demo.md) · [앱 화면 시안 이미지](docs/assets/majung-app-screens.png)

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
apk/                     공식 제출용 서명된 Release APK
```

실제 차량용 앱은 `pickup-android`에 있으며, `pickup-prototype`은 초기 웹 시안입니다.

`pickup-server`가 형제 폴더인 `pickup-web`의 파일을 제공하므로 두 폴더의 상대 위치를 유지해야 합니다. 제출 소스 ZIP에는 위 실행 소스와 테스트를 포함합니다.

**공식 제출 APK:** [오하나_마중_release.apk](apk/오하나_마중_release.apk) — 팀명 **오하나**, 앱 버전 **0.2.1** (`versionCode 10`). 제출 전용 키로 서명한 Release 빌드입니다. [공식 제출 Release](https://github.com/hana5111/Majung/releases/tag/submission-2026-11-15)에서도 내려받을 수 있습니다.

기존 `v0.2.1` 릴리스의 Debug APK와 구분해 위 Release APK를 사용합니다.

## 실행 환경

| 항목 | 구성 |
| --- | --- |
| 확인한 에뮬레이터 | Pleos Connect v2.1.0 (2), Android 14 / API 34 |
| 에뮬레이터 화면 | 2560 × 1440px · 가로 16:9 · 160dpi (`wm size` / `wm density` 실측) |
| Android 빌드 | Gradle 9.6.0 · Android Gradle Plugin 9.5.1 · Kotlin 2.2.10 |
| Java | Gradle daemon JDK 25 (`gradle-daemon-jvm.properties`) |
| Android SDK | compile 37 · target 34 · min 28 |
| 내비 SDK | Pleos NaviHelper 3.0.5 |
| 서버 | Node.js 22.12 이상, 외부 npm 의존성 없음 |

Android Studio와 Pleos 에뮬레이터·차량 내비 환경이 필요합니다. SDK·Gradle 의존성을 처음 내려받을 때 인터넷 연결이 필요합니다. 일반 스마트폰에서는 Pleos 내비 연동을 확인할 수 없습니다.

## 실행 방법

### 1. PC에서 약속 서버 시작

Node.js 22.12 이상을 설치한 뒤 저장소 루트에서 실행합니다. 외부 npm 의존성이 없어 별도 패키지 설치는 필요하지 않습니다.

```sh
cd pickup-server
npm start
```

기본 서버 주소는 `http://127.0.0.1:8787`입니다. `http://127.0.0.1:8787/api/health`로 상태를 확인할 수 있습니다. 앱을 사용하는 동안 서버 터미널을 열어 둡니다. 연결 정보와 약속은 `pickup-server/.data/appointments.json`에 저장하며 이 데이터는 제출 소스에 포함하지 않습니다.

### 2. 같은 PC의 Pleos 에뮬레이터에 설치

서버를 실행한 PC에서 위 테스트 환경의 Pleos 에뮬레이터를 켭니다. Android SDK의 `adb`를 사용할 수 있는 터미널에서, APK가 있는 폴더로 이동해 실행합니다.

```sh
adb install -r "오하나_마중_release.apk"
adb shell am start -n kr.ddakta.pickup/.MainActivity
```

앱 이름은 **마중**, 패키지는 `kr.ddakta.pickup`입니다. 다른 키로 서명한 Debug APK가 이미 설치되어 있으면 업데이트가 거부될 수 있으므로 새 AVD에서 실행하는 것을 권장합니다. 기존 앱을 삭제한 뒤 재설치할 수도 있으나, 삭제하면 해당 앱의 로컬 데이터도 지워집니다.

### 3. 소스에서 직접 빌드

Android Studio에서 `pickup-android` 폴더를 열고 Gradle JDK 25와 Android SDK 37을 설정합니다. 저장소 루트의 별도 터미널에서는 다음과 같이 빌드합니다.

```sh
cd pickup-android
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

Windows에서는 `./gradlew` 대신 `gradlew.bat`을 사용합니다. SDK 경로는 Android Studio 또는 로컬 `local.properties`에서 설정합니다.

- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- 서명 전 Release APK: `app/build/outputs/apk/release/app-release-unsigned.apk`

`assembleRelease` 결과는 서명 전 파일입니다. 제출 APK의 서명키는 저장소에 포함하지 않으므로, 직접 재빌드한 Release APK를 설치하려면 Android Studio의 **Generate Signed Bundle / APK** 등으로 본인 키를 사용해 서명해야 합니다.

## 테스트 설정

| 빌드 속성 | 기본값 | 용도 |
| --- | --- | --- |
| `ddaktaApiUrl` | `http://10.0.2.2:8787` | 에뮬레이터에서 PC의 약속 API에 접속 |
| `ddaktaInviteOrigin` | `http://127.0.0.1:8787` | 같은 PC 브라우저에서 여는 초대 링크 |
| `ddaktaGleoPackage` | `ai.umos.ambientai` | Gleo 응답 대상 패키지 |

제출 Release의 네트워크 설정은 Android API 28~34에서 에뮬레이터의 로컬 서버용 호스트 `10.0.2.2`에만 HTTP를 허용합니다. 외부 API 서버에는 HTTPS를 사용합니다.

기본 초대 링크의 `127.0.0.1`은 링크를 여는 기기 자신을 뜻하므로 다른 휴대폰에서는 PC 서버로 연결되지 않습니다. 실제 휴대폰에서 확인하려면 접근 가능한 서버를 준비하고 주소를 바꿔 다시 빌드합니다. 주소는 빌드 시 정해지므로, 실행 중 설정만으로 변경되지 않습니다.

```sh
# pickup-android 폴더에서 실제 서버 주소로 바꿔 실행
./gradlew :app:assembleRelease \
  -PddaktaApiUrl=https://your-server.example \
  -PddaktaInviteOrigin=https://your-server.example
```

위 예시 주소는 실제 주소가 아닙니다. 외부 서버의 HTTPS 제공 환경은 별도로 구성해야 하며, 재빌드한 Release APK는 본인 키로 서명합니다. 서버의 환경변수는 `HOST`(기본 `127.0.0.1`), `PORT`(기본 `8787`), `DDAKTA_DATA_FILE`(저장 파일 경로)입니다.

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
- Debug·Release APK 빌드 성공. 공식 제출용 서명 Release APK를 별도 Pleos 에뮬레이터에 설치·실행하고 로컬 서버 연결과 약속 목록 로딩을 확인했습니다.
- Android `lintRelease`: 오류 0개, 경고 24개
- 서버·웹 검사 **24개 통과**

```sh
# Android 기본 검사
cd pickup-android
./gradlew :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:lintRelease

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
