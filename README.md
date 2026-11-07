# ANBU Backend

기관 간 방문 기록과 검사 요청을 공유하는 Spring Boot REST API입니다. Java 21, Spring Boot 3.5.16, Spring Security, Spring Data JPA(Hibernate), Flyway를 사용합니다. 날짜 기준은 Asia/Seoul입니다.

구현 기준은 안부 API 명세서입니다. [Notion 명세서](https://www.notion.so/4112a8e848a480a0bcabc40cb7655b73)

## 실행

Java 21을 설치한 뒤 프로젝트 루트에서 실행합니다. Gradle Wrapper가 포함되어 있습니다.

```sh
bash ./gradlew test bootJar
export ANBU_DEMO_PASSWORD='replace-with-your-local-demo-password'
bash ./gradlew bootRun --args='--spring.profiles.active=demo'
```

기본 주소는 `http://localhost:8080/api/v1`입니다. 기본 H2 데이터베이스는 `data/anbu`에 저장되어 재시작 후에도 유지됩니다. `demo` 프로필은 비어 있는 데이터베이스에 가상의 기관·공공장소·검사·계정을 생성합니다. 시연용 지표는 null이며 실제 안산 통계가 아닙니다. 비밀번호는 12자 이상, UTF-8 72바이트 이하로 설정합니다.

| 로그인 ID | 권한 | 소속 |
| --- | --- | --- |
| health-demo | STAFF | 시연 보건소 |
| welfare-demo | STAFF | 시연 복지관 |
| manager-demo | ORG_MANAGER | 시연 보건소 |
| admin-demo | ADMIN | 시연 보건소 |

네 계정은 최초 시드 실행 시 `ANBU_DEMO_PASSWORD` 값으로 생성됩니다. 기존 데이터가 있는 경우 시드를 다시 넣거나 비밀번호를 바꾸지 않습니다. 일반 실행에서는 `demo` 프로필을 생략하고 운영 기관·계정·장소 기준 데이터를 데이터베이스에 적재합니다. 직원 계정 비밀번호는 BCrypt 해시로 저장합니다.

PostgreSQL을 연결하려면 아래 환경변수를 지정합니다. Flyway가 시작 시 스키마를 생성합니다. 웹과 API의 운영 실행은 [배포 안내](DEPLOY.md)를 참고하세요.

```sh
export ANBU_DB_URL='jdbc:postgresql://localhost:5432/anbu'
export ANBU_DB_USERNAME='anbu'
export ANBU_DB_PASSWORD='your-database-password'
java -jar build/libs/anbu-0.1.0.jar --spring.profiles.active=prod
```

`PORT`의 기본값은 8080입니다. `ANBU_CORS_ORIGINS`는 허용할 프런트엔드 origin을 쉼표로 구분합니다. 개발 기본값은 `http://localhost:3000,http://localhost:5173`이고, `prod`에서는 운영 웹 주소를 지정합니다.

## 코드 구조

컨트롤러는 HTTP 요청·응답을 처리하고, 서비스는 권한·업무 규칙·트랜잭션을 담당합니다. `kr.anbu.persistence.entity`는 Flyway 테이블에 대응하는 JPA 엔티티, `kr.anbu.persistence.repository`는 저장·조회·잠금과 JPQL/native 집계 쿼리를 모은 영속성 계층입니다. 서비스와 컨트롤러에는 쿼리를 작성하지 않습니다.

스키마 변경은 Flyway가 담당하며 Hibernate는 `ddl-auto=validate`로 매핑을 검증합니다. `open-in-view=false`이므로 데이터 접근은 서비스 트랜잭션 안에서 완료됩니다.

## 인증과 응답

`POST /api/v1/auth/login`에 아래 JSON을 전달합니다. 로그인 외 모든 API에는 `Authorization: Bearer {accessToken}`이 필요합니다.

```json
{"loginId":"health-demo","password":"your-local-demo-password"}
```

응답의 `data`에는 `accessToken`, `expiresIn`(3600초), `user`가 있습니다. 토큰은 암호학적 난수로 생성한 opaque bearer이며 서버에는 SHA-256 해시와 만료 시각만 저장합니다.

```json
{"success":true,"data":{},"error":null}
```

오류 응답은 같은 구조로 `success=false`, `data=null`, `error={code,message}`를 반환합니다. 일괄 등록 오류는 `error.details.itemIndex`에 실패한 0 기반 항목 번호를 포함합니다. 주요 오류 코드는 `INVALID_INPUT`(400), `UNAUTHORIZED`(401), `FORBIDDEN`(403), `NOT_FOUND`(404), `ALREADY_PLANNED`·`REQUEST_CONFLICT`·`LINKED_VISIT_CONFLICT`(409)입니다.

## 구현된 API

모든 경로는 `/api/v1` 기준입니다.

| Method | 경로 | 동작 |
| --- | --- | --- |
| POST | /auth/login | 기관 계정 로그인 |
| POST | /visits | 방문·제공 검사·신규 요청·기존 요청 처리 일괄 저장 |
| GET | /visits | 필터 및 페이지네이션 방문 조회 |
| PATCH | /visits/{visitId} | 자기 기관 방문 수정 |
| DELETE | /visits/{visitId} | 연결 실적 없는 자기 기관 방문 삭제 |
| POST | /visits/batch | 1~100개 방문을 한 트랜잭션으로 등록 |
| GET | /map/places | 전체 장소 마커·공백·미처리 요청 집계 |
| GET | /map/dongs/{dongId}/coverage | 선택한 동의 시설별 담당 구역 GeoJSON |
| GET | /places/{placeId}/summary | 서비스별 이력·검사 요청·처리 실적 |
| GET | /test-requests | 검사 요청·처리·잔여 건수 조회 |
| GET | /test-requests/unread-count | 계정의 읽지 않은 수신 알림 수 조회 |
| PATCH | /test-requests/{requestId}/read | 수신 계정의 알림 확인 |
| PATCH | /test-requests/{requestId}/plan | 방문 예정 등록·변경 |
| PATCH | /test-requests/{requestId}/complete | 실제 방문 검사 실적 연결 |
| PATCH | /test-requests/{requestId}/unplan | 방문 예정 취소 |
| DELETE | /test-requests/{requestId}/plan | 방문 예정 취소의 별칭 |
| GET | /organizations | 참여 기관 및 담당 동 조회 |
| GET | /organizations/me/visit-cycles | 소속 기관 서비스별 방문 주기 |
| PUT | /organizations/me/visit-cycles | ORG_MANAGER·ADMIN의 주기 설정 |
| GET | /test-types | 검사 종류 및 제공 가능 기관 |
| PUT | /test-types/{testTypeId}/organizations | ADMIN의 제공 기관 지정 |
| GET | /dongs | 행정동·지역 지표·출처 메타데이터 |
| GET | /dongs/priorities | 동의 1순위 시설을 기준으로 동별 검사 우선순위 조회 |
| GET | /dongs/{dongId}/places/priorities | 선택한 동의 경로당·마을회관 검사 우선순위 조회 |
| GET | /stats/organizations | month=YYYY-MM 기준 기관별 월 통계 |

`GET /visits`는 `placeId`, `dongId`, `organizationId`, `serviceCode`, `testTypeId`, `fromDate`, `toDate`를 지원합니다. `GET /test-requests`는 `assignable`(기본 true), `status`(기본 PENDING,PLANNED), `dongId`, `placeId`, `testTypeId`, `sort`(oldest/count)를 지원합니다. 두 목록의 `page`는 0부터, `size`는 기본 20이며 1~100입니다.

기관을 지정하려면 `requestedTests` 항목에 `recipientOrganizationId`와 선택 필드 `note`를 전달합니다. 기관별 목록은 `GET /test-requests?direction=incoming&assignable=false` 또는 `direction=outgoing&assignable=false`로 조회합니다. 요청 알림은 수신 기관 계정별로 확인 상태를 저장합니다.

지도 필터는 `dongId`, `serviceCode`, `gapLevel`, `hasPendingRequest`입니다. `hasPendingRequest=true`일 때 미처리 요청이 있는 장소로 제한합니다. 장소 상세의 `historyLimit`은 서비스별 기본 5, 최대 50입니다. 지도와 기준 목록은 전체 배열을 반환합니다.

시설 기준 데이터를 적재할 때 `places.place_type`에 `SENIOR_CENTER`(경로당), `VILLAGE_HALL`(마을회관), `OTHER`(그 외)를 지정합니다. 유형이 비어 있는 기존 장소는 이름에서 공백을 제거한 뒤 ‘경로당’ 또는 ‘마을회관’을 포함하면 우선순위 조회에 포함합니다.

### 안산시 지도 데이터

```sh
bash ./gradlew bootRun --args='--spring.profiles.active=ansan-map'
```

`ansan-map` 프로필로 시작하면 연결된 DB에 안산시 25개 행정동과 경로당 269곳, 마을회관 5곳의 이름·주소·좌표·유형을 적재합니다. 기존 행정동과 같은 동에 등록된 이름·주소가 일치하는 시설은 기존 ID를 사용합니다. 일치하는 시설은 유형이 비어 있을 때 유형을 채웁니다. 로그인 후 `GET /api/v1/dongs`에서 동 ID를 확인하고 `GET /api/v1/map/places?dongId={dongId}`에서 해당 동의 시설을 조회할 수 있습니다.

[지도 데이터 파일](src/main/resources/reference/ansan-map.json)의 `bounds`는 지도에서 동을 선택할 때 맞출 수 있는 경계 상자입니다. 순서는 서쪽 경도, 남쪽 위도, 동쪽 경도, 북쪽 위도입니다. 시설의 행정동은 주소의 법정동 이름과 별도로, WGS84 좌표가 포함되는 행정동 경계로 정했습니다. 행정동 경계는 2026-10-01 통계청 SGIS 자료의 [admdongkor 가공본](https://github.com/vuski/admdongkor/tree/master/ver20261001)을 사용했습니다.

`GET /api/v1/map/dongs/{dongId}/coverage`의 `data`는 WGS84 GeoJSON `FeatureCollection`입니다. 각 시설에 하나의 `Polygon` 또는 `MultiPolygon`을 반환합니다. 지도 마커의 `placeId`를 GeoJSON의 `properties.placeId`와 연결하면 시설 선택 시 해당 구역을 강조할 수 있습니다.

동의 모든 지점을 그 동 경계 안에 등록된 경로당·마을회관 중 직선거리가 가장 가까운 곳에 배정합니다. 같은 좌표에 있는 시설은 해당 좌표의 담당 구역을 면적이 같도록 서쪽부터 시설 ID 순으로 나눕니다. 대상 시설이 하나면 동 전체를, 대상 시설이 없으면 빈 `features`를 반환합니다. 동 또는 지도 경계가 없으면 `404 NOT_FOUND`입니다. [안산시 경계 GeoJSON](src/main/resources/reference/ansan-dong-boundaries.geojson)은 위 admdongkor 가공본에서 추출했습니다([CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)).

- 경로당은 2026-08-18 안산시 등록분의 영업 시설을 사용했습니다. 성모경로당은 같은 도로명주소의 건물 좌표로 보정했습니다. 방아머리경로당, 장상3경로당, 옹기마을경로당, 불탄도마을경로당은 해당 주소의 행정동 경계 안에 있는 좌표를 확인할 수 없어 제외했습니다. [공공데이터포털 전국마을회관및경로당표준데이터](https://www.data.go.kr/data/15114136/standard.do?recommendDataYn=Y), [성모경로당 건물 주소](https://findby.co.kr/details/15280-412714370172-st-652c0ae2f27008be2c51a07d)
- 마을회관은 주소와 건물 좌표가 확인된 선부동 1곳(행정동 선부2동)과 대부동 4곳을 포함합니다. 이 목록은 확인된 시설의 범위입니다. [선부동](https://findby.co.kr/details/15210-412734373062-st-652c0a1df27008be2c4f67a5), [대부동동](https://findby.co.kr/details/15644-412734857746-st-652c09f7f27008be2c4ef883), [대부북동](https://findby.co.kr/details/15645-412734373295-st-652c09f7f27008be2c4ef885), [선감동](https://findby.co.kr/details/15652-412734373412-st-652c0a0af27008be2c4f2eea), [풍도](https://findby.co.kr/details/15654-412734373423-st-652c0a68f27008be2c504155)

## 방문 등록 예시

ID는 기준 조회 API에서 선택합니다. 시연 데이터의 첫 검사 서비스 코드는 B입니다.

```json
{
  "visitDate": "2026-11-08",
  "placeId": 1,
  "serviceCodes": ["B"],
  "performedTests": [{"testTypeId": 1, "count": 10}],
  "requestedTests": [{"testTypeId": 1, "count": 4}],
  "fulfilledRequests": [],
  "memo": ""
}
```

서비스 코드는 A(건강 상태 모니터링), B(맞춤형 보건 교육), C(방문 진료 및 처치), D(복지 연계)입니다. 실제 제공 검사에는 기관의 제공 권한과 선택한 서비스 코드가 필요합니다. 신규 요청은 다른 기관이 제공하는 검사도 등록할 수 있습니다.

`performedTests`와 `requestedTests`는 같은 검사 종류가 반복되면 수량을 합산합니다. 요청 처리는 `fulfilledRequests=[{"requestId":1,"completedCount":2}]` 또는 별도 완료 API의 `{"visitId":2,"completedCount":2}`를 사용합니다. 두 경로는 같은 검증 로직을 호출합니다.

요청 행과 방문 행을 잠가 요청 잔여 건수와 실제 검사 제공량을 함께 검증합니다. 동일 요청·방문 연결은 한 번만 허용합니다. 일부 처리 후에는 PENDING 또는 PLANNED 상태를 유지하며 잔여 건수가 0일 때 DONE이 됩니다. 방문 예정만으로 실적이나 마지막 방문일이 바뀌지 않습니다.

신규 요청 또는 처리 실적이 연결된 방문은 메모만 수정할 수 있습니다. 일괄 등록은 한 항목이라도 실패하면 전체를 롤백합니다. 알 수 없는 JSON 필드, 소수 수량, 미래 방문일은 거절합니다. 주민 식별정보와 진단 결과를 메모에 입력하지 않아야 합니다.

## 명세의 빈 부분에 적용한 기준

- 방문 주기 설정은 제출한 서비스만 갱신하며 전체 주기를 반환합니다. `cycleDays=null`로 주기를 해제할 수 있습니다. 기본값은 A=30, B=90, C=60, D=null입니다.
- 상세 설명이 비어 있는 `DELETE /test-requests/{requestId}/plan`은 `/unplan`과 같은 권한·응답으로 구현했습니다.
- 통계는 상세 요청 계약에 있는 `month`를 사용합니다. `requestCreatedCount`는 해당 월 방문에서 생성된 요청 행 수이며, `requestCompletedCount`는 해당 월에 완료된 요청 중 그 기관이 그 달 처리에 참여한 요청 수입니다. 여러 기관이 처리한 요청은 기관별로 집계될 수 있습니다. 수량 합계와 고유 인원 수를 의미하지 않습니다.
- 로그아웃은 경로·메서드·본문이 비어 있어 후속 명세 대상입니다. 다음 달 방문 추천(`/recommendations`)은 후속 구현 대상입니다.

## 검증

```sh
bash ./gradlew clean test bootJar
```

테스트는 H2의 PostgreSQL 호환 모드에서 인증·만료, 기관 권한, 부분 처리, 중복 처리, 실제 제공량 초과, 동시 계획 선점, 동시 실적 배분, 일괄 롤백, 연결 방문 보호, 지도 공백 경계, 서비스별 이력, 기준 설정을 확인합니다. JPA 전환 회귀 테스트는 방문 자식 데이터의 복합키 교체·삭제, 잔여 건수 정렬·페이지 계산, 월 경계와 기관별 완료 통계도 검증합니다.

## 안부 웹 미리보기

로그인과 지도, 방문 예약·기록, 기관별 연락처는 [프런트엔드 실행 안내](frontend/README.md)를 참고하세요.

- `GET /api/v1/map/boundaries`는 안산 행정동 GeoJSON, `GET /api/v1/organization-contacts`는 출처를 포함한 공개 기관 연락처를 반환합니다. 두 API 모두 로그인 후 사용할 수 있습니다.
- 동·시설 검사 우선순위 API는 선택적으로 `testTypeId`를 받습니다. 선택하면 해당 검사의 실제 제공 이력으로 순위를 계산합니다.
- `POST /api/v1/visit-reservations`에 `placeId`, `visitDate`(YYYY-MM-DD), `visitTime`(HH:mm), `testTypeId`를 전달합니다. 소속 기관의 제공 검사만 미래 일시에 예약할 수 있습니다. 같은 시설·기관·일시의 중복 예약은 `409 ALREADY_RESERVED`입니다. `GET /api/v1/visit-reservations?placeId=…`로 시설별 예약을 조회합니다.
- 방문 기록은 기존 방문 API를 사용하며, 선택적으로 `visitTime`과 시설 검사 결과 `resultCode`(`GOOD`, `CAUTION`, `FOLLOW_UP`)를 저장합니다.
