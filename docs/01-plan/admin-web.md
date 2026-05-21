# Admin 웹 + 통합 인증 설계

> Status: Plan
> Date: 2026-05-21
> Scope: vibe-coder v0.4.0 후보 기능
> Owner: server + android-app + docker

## 1. 배경 / 결정 사항

### 1.1 동기

- Docker 이미지 사용자가 첫 실행 직후 **브라우저로 서버 셋업**을 마칠 수 있어야 한다.
- 콘솔에 페어링 코드를 노출하는 현재 방식은 컨테이너 환경/원격 운영과 잘 맞지 않는다.
- 1인 LAN 도구라는 성격을 유지하면서 "username + password" 라는 일반적인 인증 UX로 일원화한다.

### 1.2 사용자 의사 결정 (2026-05-21)

| 항목 | 선택 |
|---|---|
| Admin 범위 | 초기 셋업 + 운영 설정 + 사용 상태 대시보드 |
| 인증 모델 | **통합** — ID/PW가 페어링도 대체 |
| 구현 시점 | Docker PoC와 함께 (이번 사이클) |

### 1.3 영향 범위

- **server**: User 엔티티 신설, AuthService 재작성, 페어링 deprecate, admin 웹 HTML 라우트 추가
- **shared**: `ApiPath`에 admin/auth 경로 추가, DTO 보강 (`LoginRequest`, `ChangePasswordRequest` 등)
- **android-app**: 페어링 코드 입력 → ID/PW 로그인 화면 교체
- **docker**: `.env.example`에 부트스트랩 admin 자격증명 옵션 추가

## 2. 인증 모델

### 2.1 단일 admin 사용자

> 1인 LAN 도구 전제. **다중 사용자 지원은 비범위(out of scope)**.

```
AdminUser
 ├─ id (UUID, PK)
 ├─ username (unique, 3~32 char)
 ├─ passwordHash (BCrypt, cost 12)
 ├─ createdAt
 ├─ lastLoginAt (nullable)
 └─ passwordChangedAt
```

### 2.2 토큰 / 세션 통합

- **단일 토큰 시스템**: 256-bit URL-safe Base64. DB에는 SHA-256 hash만 저장 (기존 `TokenService` 재사용).
- 같은 토큰을 **두 가지 운반 경로**로 사용:
  - 웹: `HttpOnly`, `Secure` (단, LAN HTTP면 `Secure` 끔), `SameSite=Strict` 쿠키
  - 앱: `Authorization: Bearer <token>` 헤더 (현행 그대로)
- 토큰 row 자체는 기존 `devices` 테이블에 저장 (`user_id` 컬럼 추가).
- 세션 만료: 기본 14일 (`lastSeenAt`이 14일 미경신 시 invalidate).

### 2.3 페어링 코드 처리

- `PairingCode` / `pairingRoutes`는 **deprecate**. v0.4.0에서 라우트는 유지하되 401 반환하도록 변경.
- v0.5.0에서 코드/스키마 완전 제거.

## 3. DB 스키마 변경

### 3.1 새 테이블

```kotlin
object AdminUsers : Table("admin_users") {
    val id = varchar("id", 64)
    val username = varchar("username", 32).uniqueIndex()
    val passwordHash = varchar("password_hash", 96)
    val createdAt = varchar("created_at", 64)
    val lastLoginAt = varchar("last_login_at", 64).nullable()
    val passwordChangedAt = varchar("password_changed_at", 64)
    override val primaryKey = PrimaryKey(id)
}
```

### 3.2 Devices 변경

```kotlin
object Devices : Table("devices") {
    // ... 기존 컬럼 ...
    val userId = varchar("user_id", 64).references(AdminUsers.id).nullable() // 신설
    val channel = varchar("channel", 16).default("app") // "web" | "app"
}
```

SQLite는 ALTER TABLE 제약이 있으므로, 첫 실행 시 `SchemaUtils.createMissingTablesAndColumns(...)` 로 처리.

## 4. 엔드포인트

### 4.1 신규 REST API (`/api/auth/*`)

| Method | Path | Auth | 설명 |
|---|---|---|---|
| POST | `/api/auth/login` | none | `{username, password}` → `{token, deviceId, expiresAt}` |
| POST | `/api/auth/logout` | bearer | 호출자 토큰 무효화 (기존 유지) |
| GET  | `/api/auth/me` | bearer | 사용자 정보 반환 (기존 유지) |
| POST | `/api/auth/password` | bearer | `{currentPassword, newPassword}` → 200/401 |
| POST | `/api/auth/setup` | none (admin 미존재 시) | 최초 admin 생성. admin 존재 시 409 |

### 4.2 신규 Admin 웹 (`/admin/*`)

서버 사이드 렌더 HTML. SPA 아님. Ktor의 `respondText(html, ContentType.Text.Html)` + Kotlin 문자열 템플릿. 정적 CSS는 `/static/admin.css`.

| Method | Path | 인증 | 동작 |
|---|---|---|---|
| GET  | `/admin` | session | 대시보드. 미로그인 → `/admin/login`. admin 미존재 → `/admin/setup` |
| GET  | `/admin/setup` | admin 미존재 시 | 초기 비번 설정 폼 |
| POST | `/admin/setup` | admin 미존재 시 | 생성 후 자동 로그인 → `/admin` |
| GET  | `/admin/login` | none | 로그인 폼 |
| POST | `/admin/login` | none | 쿠키 set 후 redirect |
| POST | `/admin/logout` | session | 쿠키 unset + 토큰 무효화 |
| GET  | `/admin/password` | session | 비번 변경 폼 |
| POST | `/admin/password` | session | 변경 후 토스트 |
| GET  | `/admin/settings` | session | server.yml 항목 편집 폼 |
| POST | `/admin/settings` | session | 디스크에 저장 (백업 후) |
| GET  | `/admin/devices` | session | 토큰 목록 |
| POST | `/admin/devices/{id}/revoke` | session | 해당 토큰 즉시 무효화 |

### 4.3 Public

| GET | `/health` | none | `{"status":"ok","version":"0.4.0"}` — Docker HEALTHCHECK 용 |

## 5. 보안

- **BCrypt** cost 12 (~250ms / hash on modern CPU). 라이브러리: `org.mindrot:jbcrypt:0.4`.
- **CSRF**: 모든 admin POST는 hidden `_csrf` 필드. 토큰은 세션 쿠키와 1:1.
- **Rate limit**: `/admin/login` + `/api/auth/login`은 IP당 분당 5회. 초과 시 429.
- **Brute force lockout**: 같은 username 10회 실패 시 15분 잠금 (메모리 카운터 + lastFailedAt).
- **HTTP / HTTPS**: LAN 내부 HTTP가 기본. 운영자가 reverse proxy로 TLS 종단 처리. 쿠키 `Secure`는 `X-Forwarded-Proto=https` 감지 시 자동 활성.
- **비밀번호 정책**: 길이 ≥ 8, 영문+숫자 혼합 (정규식 `(?=.*[A-Za-z])(?=.*\d).{8,}`).
- **로그**: 비밀번호/토큰 **절대 로깅 금지**. username + 결과(success/fail) + IP만 기록.

## 6. 초기 셋업 플로우

```
컨테이너 부팅
        │
        ▼
DB에 admin_users 행 있나?
        │
   ┌────┴────┐
   No        Yes
   │          │
   ▼          ▼
.env에 VIBECODER_ADMIN_USER / PASSWORD 있나?
   │
┌──┴──┐
Yes   No
│     │
▼     ▼
자동  /admin/setup 으로 강제 redirect
생성  (사용자가 브라우저로 첫 비번 설정)
+ log
"admin created from env"
```

`.env` 부트스트랩이 가능한 이유: 자동화된 docker compose 배포에서 수동 단계 없이 시작하기 위함. 단, **plain text 비밀번호가 .env에 남는 점**을 README에서 경고하고, 부팅 직후 `/admin/password`로 변경할 것을 권장.

## 7. UI 스케치

### 7.1 로그인 (`/admin/login`)

```
+--------------------------------------+
|   Vibe Coder Server                  |
|   ------------------                 |
|                                      |
|   Username:  [_____________]         |
|   Password:  [_____________]         |
|                                      |
|   [  Sign in  ]                      |
|                                      |
|   v0.4.0 · workspace: /workspace     |
+--------------------------------------+
```

### 7.2 대시보드 (`/admin`)

좌측 사이드바: Dashboard / Settings / Devices / Password / Logout
메인:
- 서버 상태 카드 (uptime, version, workspace path)
- 환경 진단 카드 (JDK, Android SDK, Claude CLI — 각각 ✓/✗ + 버전 + "doctor 실행 안내")
- 최근 빌드 5개
- 페어링된 디바이스 N개

### 7.3 설정 (`/admin/settings`)

`server.yml` 필드를 카테고리별로 폼화:
- Server (name, port — read-only 표시, 변경은 재시작 필요 안내)
- Workspace (root, maxUploadSizeMb, artifactKeepCount)
- Claude (enabled, path, timeoutMinutes, autoBuildAfterTask)
- Build (timeoutMinutes, defaultDebugTask)
- Security (allowRawShell — 항상 false 강제)

저장 시 디스크 `server.yml`을 백업(`.bak.<timestamp>`)하고 원자적 교체.

### 7.4 디바이스 (`/admin/devices`)

```
| Name             | Channel | Created       | Last seen     |          |
|------------------|---------|---------------|---------------|----------|
| Galaxy S24       | app     | 5월 18 14:22  | 1분 전        | [revoke] |
| Firefox (Linux)  | web     | 5월 21 16:45  | now           | (현재)   |
```

## 8. Android 앱 변경

### 8.1 화면

- `PairingScreen` → `LoginScreen` 으로 교체
- 입력: 서버 URL, Username, Password
- POST `/api/auth/login` → `{token}` 저장 → 기존 토큰 저장소 그대로 사용

### 8.2 영향

- `AuthRepository.pair(...)` → `AuthRepository.login(username, password)` 로 시그니처 변경
- DataStore 키 변경 없음 (토큰만 저장)
- 토큰 만료 시 401 → 로그인 화면으로 복귀

## 9. Docker 통합

### 9.1 .env.example 추가 필드

```bash
# (선택) 첫 실행 시 admin 계정 자동 생성. 미설정 시 /admin/setup 화면.
# VIBECODER_ADMIN_USERNAME=admin
# VIBECODER_ADMIN_PASSWORD=ChangeMe123
```

### 9.2 README 추가 단계

```
docker compose up -d
docker logs vibe-coder          # /admin URL 확인
브라우저 → http://<PC IP>:17880/admin
초기 비번 설정 (또는 .env 자동 생성 확인)
admin > Doctor 실행 → Android SDK 설치
admin > Devices → 휴대폰 앱 로그인
```

## 10. 마이그레이션 / 롤백

### 10.1 마이그레이션

- DB는 SQLite. 첫 실행 시 `SchemaUtils.createMissingTablesAndColumns(AllTables)` 가 자동으로:
  - `admin_users` 테이블 생성
  - `devices`에 `user_id`, `channel` 컬럼 추가
- 기존 `devices` 행: `user_id = null`, `channel = 'app'` (default).
- 기존 페어링 토큰: 별도 마이그레이션 없이 그대로 유효. v0.4.0에서 admin 로그인 후 admin이 직접 revoke/재발급.

### 10.2 롤백

- `admin_users` 테이블 drop, `devices.user_id` / `channel` 컬럼 drop으로 원복 가능.
- 페어링 라우트는 deprecate 상태로만 두므로, 재활성화는 라우트 한 줄 enable.

## 11. 비범위 (Out of scope)

- 다중 사용자 / 권한 분리 (admin 1개)
- OAuth / SSO
- 2FA / TOTP
- SAML / LDAP
- 비밀번호 복구(이메일 등) — 1인 도구이므로 분실 시 DB 직접 조작 (`reset-admin` CLI 서브커맨드는 v0.5.0+)
- 외부 노출 / 공개 배포 가드 (현재 룰: HOST `0.0.0.0` 바인딩이지만 LAN 내부 사용 전제)

## 12. 단계별 작업 분해 (TaskList 매핑)

| Task | 산출물 |
|---|---|
| [A] 본 문서 | `docs/01-plan/admin-web.md` |
| [B] Auth 기반 | `db/Schemas.kt` 갱신, `repo/AdminUserRepository.kt`, `auth/PasswordHasher.kt`, `auth/AuthService.kt` |
| [C] REST API | `auth/AuthRoutes.kt` 갱신, `shared/ApiPath.kt` 갱신, DTO |
| [D] Admin 웹 | `admin/AdminRoutes.kt`, `admin/AdminTemplates.kt`, `resources/static/admin.css`, `auth/SessionCookiePlugin.kt` |
| [E] Android | `LoginScreen.kt`, `AuthRepository.kt` 변경 |
| [F] Docker | `docker/settings-docker.gradle.kts`, `Module.kt`에 `/health`, `.env.example` 보강 |

## 13. 검증

- 단위: `AuthServiceTest` (login success/fail/lockout, password change, setup once)
- 통합: `AdminRoutesTest` (Ktor `testApplication`, 세션 쿠키 흐름)
- 수동: Docker 컨테이너 부팅 → `/admin/setup` → 로그인 → 비번 변경 → Android 앱 로그인 → 빌드 1회 성공

---

> 이 문서가 합의안이다. 구현 중 충돌 발생 시 본 문서를 갱신한 후 코드 수정.
