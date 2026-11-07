# 안부 배포

Java 21, Node.js 22.12 이상, PostgreSQL을 사용합니다. 웹과 API는 같은 HTTPS 주소에서 제공하고, 웹 서버가 `/api/v1/`을 Spring Boot로 전달하도록 구성합니다.

```sh
bash ./gradlew clean test bootJar
npm --prefix frontend ci
npm --prefix frontend run build
```

`build/libs/anbu-0.1.0.jar`는 API 서버에, `frontend/dist/`의 내용은 웹 서버의 문서 루트에 배치합니다. 웹 빌드의 기본 API 주소는 `/api/v1`입니다.

운영 환경의 비밀 저장소에서 다음 변수를 주입합니다.

| 변수 | 값 |
| --- | --- |
| `ANBU_DB_URL` | PostgreSQL JDBC 주소, 예를 들어 `jdbc:postgresql://db.example.com:5432/anbu` |
| `ANBU_DB_USERNAME` | 대상 데이터베이스 계정 |
| `ANBU_DB_PASSWORD` | 대상 데이터베이스 비밀번호 |
| `PORT` | API 서버 포트. 기본값 `8080` |
| `ANBU_CORS_ORIGINS` | 운영 웹 origin, 예를 들어 `https://anbu.example.com` |

```sh
java -jar build/libs/anbu-0.1.0.jar --spring.profiles.active=prod
```

`prod`는 PostgreSQL 연결 변수를 요구합니다. 첫 배포에서 안산 지도 기준 데이터를 적재하려면 `--spring.profiles.active=prod,ansan-map`으로 시작합니다. `demo`와 `void-preview`는 로컬용 프로필입니다.

기존 데이터베이스를 갱신하기 전에 백업하고 복구 경로를 확인합니다. 애플리케이션 시작 시 Flyway가 순서대로 스키마를 갱신하고 Hibernate가 매핑을 검증합니다.

운영 데이터베이스에 기관, BCrypt 비밀번호를 사용하는 직원 계정, 기관별 검사 권한을 등록합니다. 기관 지정 연계 요청의 수신 목록에는 해당 검사의 제공 권한과 계정이 모두 있는 기관이 표시됩니다.

웹과 API가 다른 origin에 있다면 웹 빌드 전에 `VITE_API_BASE`를 전체 API 주소로 지정합니다. 역방향 프록시를 사용하는 경우에도 `ANBU_CORS_ORIGINS`에는 사용자가 접속하는 웹 origin을 넣습니다.

배포 후 API 연결 확인은 로그인 없이 `/api/v1/dongs`를 호출해 `401`이 반환되는지 검사합니다. 웹에서 로그인한 뒤 안산 지도와 시설 상세가 표시되는지 확인합니다. 시험용 계정 두 개로 방문 예약, 방문 기록 저장, 기관 지정 연계 요청과 수신 계정의 앱 알림을 확인합니다.

## Vercel과 Supabase

Vercel 프로젝트에서 저장소 루트를 선택하고 Framework Preset을 **Services**로 설정합니다. `vercel.json`은 `frontend`의 Vite 빌드와 루트 `Dockerfile`의 Java 21 API를 같은 주소에서 제공하며, `/api/v1/` 요청을 API로 전달합니다. Vercel 프로젝트의 Node.js 버전은 22.12 이상으로 지정합니다.

Vercel 운영 환경에 위 표의 변수와 `PORT=8080`을 설정합니다. 운영 컨테이너는 `prod` 프로필로 실행합니다. `ANBU_CORS_ORIGINS`에는 경로 없이 실제 운영 웹 주소를 설정합니다. 예를 들어 운영 주소가 `https://anbu-example.vercel.app`이면 `ANBU_CORS_ORIGINS=https://anbu-example.vercel.app`을 설정합니다.

Supabase에서 전용 로그인 역할과 해당 역할이 소유하는 `anbu` 스키마를 생성하고 Data API를 끕니다. **Connect → Session pooler**에서 호스트와 프로젝트 참조 값을 확인합니다. JDBC URL은 `jdbc:postgresql://<pooler-host>:5432/postgres?currentSchema=anbu&sslmode=require` 형식입니다. `ANBU_DB_USERNAME`에 `<app-role>.<project-ref>`를, `ANBU_DB_PASSWORD`에 전용 역할의 비밀번호를 설정하고 `SPRING_FLYWAY_DEFAULT_SCHEMA=anbu`를 지정합니다.

Vercel의 첫 운영 배포 전에 접근이 제한된 실행 환경 한 곳에서 Supabase 연결 변수를 주입하고 아래 명령을 한 번 실행합니다. 로그에서 Flyway 마이그레이션과 안산 지도 적재가 완료된 것을 확인한 뒤 종료합니다.

```sh
SERVER_ADDRESS=127.0.0.1 java -jar build/libs/anbu-0.1.0.jar --spring.profiles.active=prod,ansan-map
```

초기 적재 후 운영 접속을 검증할 두 기관에 각각 `STAFF` 시험 계정을 생성합니다. `organizations`, `users`와 필요한 담당 동을 `organization_dongs`에 등록합니다. 비밀번호는 계정별로 발급하고 BCrypt 해시만 저장합니다. 검사를 `test_types`에 등록하고 수신 기관의 제공 권한을 `test_type_organizations`에 추가한 뒤 운영 배포를 진행합니다.
