# 안부 웹

Figma의 VOID해커톤 `wireframe` 화면을 바탕으로 만든 안산시 방문형 보건 서비스입니다. 로그인, 안산 전체 우선지역 지도, 동별 시설 담당 구역, 방문 예약과 기록, 기관별 연락처와 설정 화면을 포함합니다.

## 로컬 시연

Java 21과 Node.js 22.12 이상을 사용합니다. 저장소 루트에서 백엔드를 시작합니다.

```sh
bash ./gradlew bootJar
ANBU_VOID_PREVIEW_PASSWORD=1111 java -jar build/libs/anbu-0.1.0.jar \
  --spring.profiles.active=ansan-map,void-preview --server.port=18081
```

별도 터미널에서:

```sh
cd frontend
npm ci
ANBU_API_PROXY=http://127.0.0.1:18081 VITE_VOID_PREVIEW=true npm run dev
```

[http://127.0.0.1:5173](http://127.0.0.1:5173)에서 아이디 `대부남동보건진료소`, 비밀번호 `1111`로 로그인합니다. 안산 지도에서 대부동동을 선택하고, 대부동 상세 지도에서 흥성마을경로당의 담당 구역 또는 위치 표시를 선택합니다. 예약과 방문 기록은 저장소의 별도 로컬 DB `data/void-preview`에 보관됩니다.

`void-preview`는 로컬 시연 계정, 검사 항목과 흥성마을경로당의 가상 방문 기록 4건을 생성합니다. 알림 선호는 브라우저에 저장하며 발송 기능은 연결되어 있지 않습니다.

방문을 마친 시설의 `새 기록 작성` 화면에서 `새 연계 요청`을 선택해 지원 항목과 받을 기관을 지정할 수 있습니다. 시연에는 8개 검사 항목이 있고, 항목에 따라 `단원보건소`, `상록수보건소`, `대부보건지소` 중 지원 권한이 있는 기관을 선택할 수 있습니다. 수신 기관은 기관명을 아이디로 사용하고 비밀번호 `1111`로 로그인합니다. `연계 요청`의 받은 요청에서 알림을 확인하고 일정을 지정한 뒤 처리 기록을 저장할 수 있습니다. 보낸 기관은 같은 메뉴의 보낸 요청에서 진행 상태를 확인합니다.

## 배포 준비

```sh
cd frontend
npm ci
npm run build
```

`frontend/dist`를 정적 웹 서버에서 제공하고 `/api/v1`을 Spring Boot API로 전달합니다. API가 다른 origin이면 빌드 시 `VITE_API_BASE`를 지정하고 서버의 `ANBU_CORS_ORIGINS`에 실제 프런트엔드 origin을 설정합니다. 배포에는 `void-preview`를 사용하지 않고, 운영 DB에서 기관·계정·검사 종류·기관별 검사 권한을 구성합니다. 지도 기준 데이터가 없는 DB에는 `ansan-map` 프로필을 적용합니다.

## 데이터와 검증

- 안산시 행정동·시설 위치·구역은 백엔드의 [지도 기준 데이터](../src/main/resources/reference/)와 시설 구역 API를 사용합니다. 대부도의 법정동 경계는 [국가공간정보포털 법정동 경계 변환본](https://github.com/hoinnovation/Korea-area-map)에서 대부동동·대부북동·대부남동·선감동·풍도동을 추출했습니다. 법정동 경계는 상세 이동과 이름 표시에 사용하며 우선순위는 행정동·시설 기준입니다.
- [기관 연락처](../src/main/resources/reference/ansan-health-contacts.json)는 안산시 공식 안내로 확인한 10곳입니다. 화면에서 각 연락처의 출처를 열 수 있습니다.
- 전체 지도에서 대부남동은 1순위로 표시합니다. 나머지 행정동은 API의 검사일 순서에 따라 2~4단계로 나누고, 검사일이 같아 여러 단계에 걸칠 때는 색을 번갈아 배정합니다. 상세 지도에서는 시설 순위를 1위, 2~4위, 5~8위, 9위 이상 기준으로 1~4단계에 배정합니다. 우선 검사 필요 필터는 1~3단계를 강조합니다. 예약은 방문 또는 검사 실적으로 집계하지 않습니다.
- `bash ./gradlew test bootJar`로 백엔드를 확인한 뒤 프런트엔드 디렉터리에서 `npx playwright install chromium`, `npm run test:e2e`를 실행합니다. Chrome이 설치되어 있으면 `PLAYWRIGHT_CHANNEL=chrome npm run test:e2e`로 실행할 수 있습니다. E2E는 별도 메모리 DB를 사용합니다.
