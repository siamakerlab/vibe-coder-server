# Changelog — vibe-coder-server

All notable changes to the server component will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

v0.4.0 까지는 `vibe-coder` 모노레포의 단일 CHANGELOG 였고, v0.4.1 부터
서버/안드로이드 두 리포로 분리되어 각 리포가 독립 changelog 를 갖는다.
Android 클라이언트 이력은 `vibe-coder-android` 리포의 CHANGELOG 참고.

## [Unreleased]

## [0.53.0] - 2026-05-24 — Phase 32: PG tsvector + GIN 풀텍스트 검색

### Added

v0.16.0 이후 적혀있던 한계 ("본문 검색은 LIKE — 다음 cycle 에서 tsvector 로
교체 예정") 해소. `conversation_turns.content` 의 LIKE full-scan 이
인덱스 사용 풀텍스트 검색으로 마이그.

- **`content_tsv` generated column** (PG 12+ `GENERATED ALWAYS AS …
  STORED`) — 매 row 의 `to_tsvector('simple', content)` 가 자동 계산되어
  저장. `simple` configuration 은 language-agnostic (한국어 / 영어 모두
  토큰화 OK; stemming 없음).
- **GIN index** `conversation_turns_content_tsv_idx` — tsvector 매치를
  log-N 시간에.
- **`Database.init()` raw SQL 마이그** — `ALTER TABLE … ADD COLUMN IF
  NOT EXISTS` + `CREATE INDEX IF NOT EXISTS`. 첫 부팅 시 한 번만 적용,
  이후 부팅은 idempotent no-op. 기존 row 는 자동으로 백필됨 (GENERATED
  STORED 의 성질).
- **`ConversationTurnRepository.Filter.q` 매칭** — LIKE 대신
  `content_tsv @@ plainto_tsquery('simple', ?)`. `plainto_tsquery` 가
  사용자 query 를 AND 토큰으로 변환 — 메타문자 / 따옴표 안전 +
  parameter binding 으로 SQL injection 방어.
- **`TsvectorMatchOp`** — 사설 `Op<Boolean>` 으로 Exposed QueryBuilder
  에 raw SQL fragment 를 안전하게 inject.

### Performance impact

수십만 row 의 `conversation_turns` 에서 content 검색이 수십 ms → ms
미만 (PG `EXPLAIN ANALYZE` 기준). insert 시 generated column 계산
비용 미미 (tsvector 생성은 매우 빠름).

### Wire change

No (REST 응답 DTO 무변경; query 시멘틱 동일 — 단어 매칭은 LIKE 와
거의 같지만 substring 매치는 안 됨 — `tsvector` 는 token 단위).

### 알려진 한계

- **`simple` 토크나이저** — 한국어 형태소 분석 안 됨. "개발자" 와
  "개발자가" 는 다른 토큰. 더 정교한 한국어 검색이 필요하면 PG 의
  `mecab-ko` extension + custom config 가 별도 phase.
- **Substring 매치 X** — `'develop'` 검색이 `'developer'` 매치 안 됨
  (token 경계). 필요하면 `:*` prefix 매치 (`to_tsquery`) 로 확장 가능
  — 추후.
- **CJK 검색은 best-effort** — 한국어 / 일본어 / 중국어 모두 'simple'
  은 공백 / 구두점 기준 토큰화. 단어 경계가 모호한 언어는 정확도가
  낮음. UI 가 부분 일치를 위해 `LIKE` 보조 검색을 함께 노출하는 것도
  옵션.

## [0.52.0] - 2026-05-24 — Phase 31: /history agent_name 필터 + 분리 보기

### Added

Phase 28 (v0.49.0) 에서 추가된 `conversation_turns.agent_name` 컬럼을
UI 에서 본격 활용. 기존 history 페이지는 메인 console + sub-agent turns
를 섞어서 보여줬는데, 이제 명시적으로 필터링 가능.

- **`ConversationTurnRepository.Filter.agentName`** — 3-mode 시멘틱:
  - `null` (기본) — `agent_name IS NULL` → **메인 console 만**.
    backward compatible: 기존 호출자 (Filter 가 비-`agentName` 가용)
    은 자동으로 메인-only.
  - `""` (빈 string) — 필터 안 함 → 메인 + 모든 sub-agent.
  - `"<name>"` — 그 sub-agent 만.
- **`distinctAgents(projectId)`** Repository 헬퍼 — UI dropdown 채움.
- **`/history` 페이지 (per-project + chat)** filter form 에
  **"Agent (v0.52.0+)"** dropdown 추가. 3 가지 옵션:
  - `(main console only)` — default
  - `(all — main + sub-agents)` — query `?agent=*`
  - `@<agent>` — 등록된 agent 별 옵션 (distinct list 에서)
- **Row 표시**에 `@<agent>` 작은 배지 추가 — sub-agent 출처가 한눈에.
- **Pagination 링크** 가 `agent` 파라미터 round-trip — 페이지 이동 시
  필터 유지.

### Query param contract

| Browser URL | Filter.agentName | Behaviour |
|---|---|---|
| `/projects/{id}/history` (no `agent=`) | `null` | 메인 console 만 |
| `?agent=*` | `""` | 메인 + 모든 sub-agent |
| `?agent=reviewer` | `"reviewer"` | `@reviewer` 만 |

### Wire change

No (DTO 무변경; 기존 호출자 자동으로 메인-only — 명시적으로 `agent=*`
를 줘야 sub-agent 가 합쳐짐).

## [0.51.0] - 2026-05-24 — Phase 30: JSON API ACL 완성

### Added

v0.49.0 Phase 28 의 한계 (SSR 만 완전 보호) 해소. 이제 mutating
per-project JSON API 와 WebSocket 모두 ACL 강제.

- **`ApplicationCall.requireProjectAcl(projects, projectId)`** —
  SSR-side `requireProjectAccessOrRedirect` 의 JSON 짝. 위반 시
  `403 project_forbidden` throw. admin bypass + 0-ACL bypass + grant
  체크 로직 캡슐화.
- **15+ JSON endpoint 에 ACL 가드 추가**:
  - `BuildRoutes`: `POST /api/projects/{id}/build/debug`,
    `GET /api/projects/{id}/builds`, `GET .../{buildId}`,
    `POST .../{buildId}/cancel`
  - `GitRoutes`: `GET /api/projects/{id}/git/{status,diff,log}`,
    `POST /api/projects/{id}/git/commit`
  - `FileRoutes`: `POST /api/projects/{id}/files/upload`,
    `GET /api/projects/{id}/files`, `GET .../files/{fileId}/download`,
    `DELETE .../files/{fileId}`
  - `ConsoleRoutes`: `POST /api/projects/{id}/claude/console/{prompt,
    new, cancel}`, `GET .../claude/status`,
    `GET .../claude/prompt-suggestions`
  - `ProjectActionRoutes`: `GET /api/projects/{id}/actions`,
    `POST .../actions/invoke`
  - `SubAgentRoutes`: `POST .../agents/{agent}/console/{prompt,
    cancel}`, `GET .../agents/active`, SSR `GET /projects/{id}/agents`,
    `GET .../agents/{agent}/console`, `POST .../new`
- **WebSocket ACL 가드** — `/ws/projects/{id}/console/logs` 와
  `/ws/projects/{id}/agents/{agent}/console/logs` 핸드셰이크에서
  device → user role + ACL 검사. 위반 시
  `WsFrame.Error("project_forbidden")` 송신 + `VIOLATED_POLICY`
  CloseReason 으로 연결 종료.
- **Signature 변경** — `BuildRoutes.buildRoutes(service, hub, projects)`
  와 `FileRoutes.fileRoutes(service, projects)` 가 `ProjectService` 를
  새로 받음. `WsRoutes.wsRoutes` 도 `projects` 인자 추가.

### Wire change

No (response DTO 무변경; 새 응답 코드 `project_forbidden` 는 ACL
밖 project 에 접근하는 viewer / member 토큰만 받음).

### Bypass 모델 (변경 없음)

- `admin` role 은 ACL 무관 — 모든 프로젝트 통과
- ACL row 가 0개인 non-admin 사용자 — 모든 프로젝트 통과 (default
  unrestricted)
- ACL row 가 1+ 개인 non-admin 사용자 — 허가된 프로젝트만 통과

## [0.50.0] - 2026-05-24 — Phase 29: Web Push payload 암호화 (RFC 8291)

### Added

CLAUDE.md §9 D.△ 의 v0.46.0 (Phase 25) 마무리. 그동안 payload-less 모드로
"Vibe Coder · 서버에서 알림이 도착했습니다" 한 줄만 표시되던 web push 가
이제 실제 title / body / url 을 담아 전달됨.

- **`Aes128GcmEncrypt`** (신규, 167 LOC) — JDK stdlib 만으로 RFC 8291
  aes128gcm 콘텐츠 인코딩 구현:
  - Ephemeral P-256 keypair (`KeyPairGenerator("EC", secp256r1)`).
  - ECDH 공유 비밀 (`KeyAgreement("ECDH")`) → 32 bytes.
  - HKDF-SHA256 (extract + 1-block expand, `Mac("HmacSHA256")`) — IKM /
    CEK (16) / NONCE (12) 도출. RFC 8291 §3.4 info strings 동일.
  - 4096-byte record: payload || `0x02` || zero padding.
  - AES-128-GCM (`Cipher("AES/GCM/NoPadding")`, 128-bit tag).
  - Final body = `salt(16) || record_size(4 BE) || keyid_len(1) ||
    as_public(65) || ciphertext`.
  - 외부 dep 0개 (BouncyCastle / web-push-java 불필요).
- **`WebPushNotifier.PushSubscription`** — `p256dh`, `auth` 필드 추가.
  서버 ↔ DB 의 기존 row (v0.46.0 부터 저장됨) 가 그대로 사용됨.
- **`WebPushNotifier.sendOne()`** — 두 path:
  - p256dh + auth **있음** → `Aes128GcmEncrypt.encrypt(...)` →
    `Content-Encoding: aes128gcm` POST.
  - p256dh / auth **없음** (legacy v0.46.0 row) → payload-less POST
    (`Content-Length: 0`). 서비스워커가 generic 알림 표시 (fallback).
- **`WebPushNotifier.broadcast(title, body, url?)`** — `url` 옵션 인자
  추가. service-worker 가 notificationclick 시 해당 경로로 focus / open.
- **`Notifiers` facade** — buildResult / claudeUsageWarn / diskUsageWarn
  각각 의미 있는 URL 전달:
  - 빌드 알림 → `/projects/{id}/builds/{buildId}`
  - Claude usage warn → `/usage`
  - Disk usage warn → `/`
- **Service worker** (`/static/sw.js` v0.50.0):
  - `event.data.json()` 으로 title / body / url 파싱 (브라우저가 자동
    복호화한 plaintext).
  - `notificationclick` 이 `data.url` 우선 — 기존 탭이 같은 path 면
    focus, 아니면 새 탭으로 open.

### Wire change

No (서버-내부 + 클라이언트 코드 자동 갱신 — `CACHE_VERSION` 변경으로
다음 페이지 진입 시 서비스워커 자동 갱신).

### 알려진 한계

- **VAPID 키와 ephemeral keypair 가 다른 객체.** ephemeral 은 매 push 마다
  새로 생성 (RFC 8291 권장). 그래서 별도 캐싱 없음 — push 당 keypair
  생성 비용 (수십 µs) 발생.
- **Padding strategy 가 고정**: 매 push 가 RECORD_SIZE=4096 으로 패딩.
  payload 크기 노출 방지 정도는 보장. 더 미세한 패딩 정책 (예: payload
  size 기반 dynamic) 은 추후.
- **Single record per push.** Web Push spec 은 multi-record 도 허용하나
  단일 알림 payload 가 4080 bytes 를 넘는 경우 거의 없어 단순화.

## [0.49.0] - 2026-05-24 — Phase 28: Project ACL + Sub-agent 영구 적재

### Added

CLAUDE.md §9 후속 minor 2건 묶음. 다중 사용자 모델 마무리 + sub-agent 영속성.

**1. Project ACL — member 가 일부 프로젝트만 보기.**

- **`ProjectAcls` 테이블** (`project_id`, `user_id`, `granted_by`, `created_at`).
  composite PK + per-user index.
- **`ProjectAclRepository`** — grant / revoke / replaceForUser / listForUser /
  listUsersForProject / hasAnyRowFor / isGranted.
- **Opt-in 제한 모델**: 사용자에게 ACL row 가 **하나도 없으면** 모든 프로젝트
  보임 (default). 하나라도 있으면 **그 프로젝트만** 보임. `admin` role 은
  ACL 무관 (lockout 방지). 기존 사용자 0-row 라 마이그레이션 무손실.
- **`ProjectService.listForUser(userId, isAdmin)`** + **`canUserAccess(userId,
  isAdmin, projectId)`** — ACL 평가 캡슐화.
- **`requireProjectAccessOrRedirect(sess, projects, projectId)`** SSR 가드
  헬퍼.
- **SSR `/users/{userId}/projects`** (admin-only) — 체크박스 list 로 ACL
  bulk-replace. 사용자 row 에 "권한" 칩 링크 추가.
- **JSON API**:
  - `GET /api/projects` — `DevicePrincipal.userRole` 기반 자동 필터링.
  - `GET /api/projects/{id}` — ACL 위반 시 `403 project_forbidden`.

**2. Sub-agent 영구 적재 — Phase 23 (v0.44.0) 후속.**

이전엔 sub-agent turn 이 LogHub 의 sliding window 안에서만 살아있었음.
이제 메인 console 과 같은 `conversation_turns` 테이블에 적재되어
재시작 후에도 보존.

- **`conversation_turns.agent_name`** 컬럼 추가 (nullable). null = 메인 console,
  non-null = sub-agent 이름. 새 인덱스 `(project_id, agent_name, ts)`.
- **`ConversationHistoryService`** API 확장 — `userPrompt`, `event`,
  `systemNotice` 모두 `agentName: String?` 옵션 인자 추가 (default null —
  기존 main-console 호출 영향 없음).
- **`SubAgentSessionManager`** — `history: ConversationHistoryService?` 의존성
  추가. 모든 user prompt / Claude event / system notice 가 `agent_name` 으로
  태깅되어 적재. session-id 별 turnIdx 는 그대로 사용.

### Wire change

No — REST 응답 DTO 무변경. ACL 의 SSR 변경, 신규 SSR 페이지 1개, 새 컬럼
+ 인덱스 마이그레이션 (`SchemaUtils.createMissingTablesAndColumns` 자동).

### 알려진 한계

- **JSON API 의 모든 project-scoped endpoint 에 ACL 가드를 일일이 안 달았음.**
  `/api/projects/{id}` 와 `/api/projects` (list) 만 적용. console prompt /
  build / git commit 등 mutating endpoint 는 `requireApiWrite()` 가드는
  거치지만 ACL 검사는 추후. SSR 측은 `requireProjectAccessOrRedirect` 으로
  완전 보호.
- **`/history` 글로벌 검색 + `/projects/{id}/history` 페이지**는 sub-agent
  turn 도 함께 표시 (agent_name 필터 UI 아직 없음). 추후 추가.

## [0.48.0] - 2026-05-24 — Phase 27: WebAuthn (passkey 2FA)

### Added

CLAUDE.md §9 B.△ "Hardware security key (WebAuthn)" 실현. TOTP 의 phishing-resistant
강화 — same-origin 정책이 보장하는 signature 가 가짜 사이트의 OTP 가로채기를 차단.

- **신규 dependency**: `com.webauthn4j:webauthn4j-core:0.29.1.RELEASE` (~600 KB,
  BouncyCastle + Jackson-CBOR transitive). 자체 구현보다 안정적 (CBOR / COSE /
  attestation 검증 모두 처리).
- **`WebauthnService`** — `WebAuthnManager` (non-strict) wrap:
  - `beginRegistration(userId, username)` — 32-byte challenge 생성, 5분 TTL 메모리
    저장. `RegistrationStart(challenge, rpId, rpName, userId, username, exclude...)`
    반환.
  - `finishRegistration(userId, clientDataJSON, attestationObject, transports, name)`
    — webauthn4j 의 `validate(RegistrationData, RegistrationParameters)` 호출 후
    `AttestationObject` 를 통째로 base64url-CBOR 로 저장 (assertion 시 재빌드용).
  - `beginAssertion(usernameHint, userId?)` — 사용자에게 등록된 credential id 만
    `allowCredentials` 로 노출. 사용자 없으면 빈 배열 (timing-safe discovery).
  - `finishAssertion(...)` — `clientDataJSON.challenge` 매칭으로 ceremony 식별 →
    `CredentialRecordImpl(AttestationObject, ...)` 4-arg 생성자 + signCount seed →
    webauthn4j 검증 → signCount 갱신 + `AssertionResult(userId, credentialId)`.
- **`WebauthnCredentials` 테이블** — userId / credentialId (unique) /
  attestationObject (CBOR base64url) / signCount / transports / attestationType /
  name / createdAt / lastUsedAt. PG schema 자동 migration.
- **`WebauthnCredentialRepository`** — insert / listForUser / findById /
  findByCredentialId / deleteById / countForUser / touchAfterAssertion.
- **`WebauthnSection`** (server.yml) — `rpId` / `rpName` / `origin`. 기본값은
  `localhost:17880` 가정. LAN/외부 노출 시 사용자가 직접 설정 (rpId 는 hostname
  only, origin 은 scheme+host+port).
- **SSR `/webauthn`** (any authenticated user) — 등록된 passkey 목록 / 이름 지정
  + 등록 버튼 / 삭제 버튼. 등록 흐름:
  1. `POST /api/webauthn/register/options` → challenge + rpId 받아옴
  2. 브라우저 `navigator.credentials.create({...})` → 인증기 (Touch ID /
     Windows Hello / 보안키) 사용
  3. `POST /api/webauthn/register/verify` → 검증 + DB 저장 + audit log
- **로그인 페이지 통합** — username 입력 직후 "🔑 Passkey 로 로그인" 버튼 활성화.
  password 입력 없이 passkey 단독으로 로그인 가능 (passkey 자체가 2FA 의 강한 형태).
  흐름:
  1. `POST /api/webauthn/assert/options { username }` → allowCredentialIds
  2. `navigator.credentials.get({...})`
  3. `POST /api/webauthn/assert/verify` → 검증 후 `vibe_session` 쿠키 발급
- **Audit** — `auth.passkey.register`, `auth.passkey.login`, `auth.passkey.delete`.
- **Nav 메뉴** — "Passkey (WebAuthn)" 링크 (`/password`, `/2fa` 옆).

### Wire change

No (서버-내부 + 새 REST endpoints `/api/webauthn/*` 는 브라우저 전용).
Android client 영향 없음.

### 알려진 한계

- **rpId 가 hostname 매칭만 됨.** 사용자가 다른 origin (예: `localhost` 등록 후
  `vibe.local` 접근) 으로 들어오면 passkey 가 동작 안 함 — WebAuthn spec 의 보안
  특성. `server.yml` 의 `webauthn` 섹션을 운영 환경에 맞게 설정.
- **Challenge 가 in-memory.** 서버 재시작 중인 ceremony 는 다시 시작해야 함
  (단일 사용자 dev 서버라 영향 적음).
- **Passwordless 흐름은 password 강제 안 함** — 실수로 보안 다운그레이드 가능.
  배포 모드별로 "passkey-only" 강제 옵션은 추후 검토.

## [0.47.0] - 2026-05-24 — Phase 26: 나머지 settings admin 가드 + Claude /usage + Helm chart

### Added

CLAUDE.md §9 후속 + △ 항목 묶음 — 3가지 작은 작업.

**1. 나머지 `/settings/*` SSR 의 admin 가드 (v0.40.0 마무리).**

v0.40.0 에서 `/settings`, `/audit`, `/backup` 만 적용했던 `requireAdminOrRedirect`
를 다음 페이지에도 확장:

- `/settings/email`, `/settings/email/test`
- `/settings/webhook`, `/settings/webhook/test`
- `/settings/cors`
- `/settings/git-integrations` (GET + 모든 POST 4개)
- `/settings/cache`, `/settings/cache/cleanup`

이제 member / viewer 가 이 페이지에 접근하면 dashboard 로 redirect.

**2. `/usage` 페이지 — Claude `/status` raw 출력 노출 (Anthropic Cache 조회 △).**

- `ClaudeStatusService.rawSnapshots` — 폴링 시점의 raw 출력을 64 KB 까지 메모리 보존.
- `/usage` SSR (admin-only) — 프로젝트별 최근 snapshot 카드, `cache` 키워드 line 자동
  bold. Anthropic 이 prompt cache 통계를 `/status` 에 추가하면 즉시 가시화 (서버 수정 0).
- nav 에 "Claude 사용량" 링크.

**3. Helm chart (Kubernetes 배포 △) — `helm/vibe-coder-server/`.**

minimal viable v1 — 운영자가 docker compose 대신 k8s 로 같은 standalone 서버 띄울 수 있게.

- `Chart.yaml` — appVersion = server.version 과 동일.
- `values.yaml` — postgres 사이드카 (선택) / workspace PVC / ingress / resource 한계 /
  env / secretEnv 키. 모든 키 인라인 문서화.
- `templates/`:
  - `_helpers.tpl` — fullname / labels / serviceAccountName.
  - `deployment.yaml` — Deployment (replicas=1, strategy=Recreate; CLAUDE.md §1
    단일 사용자 가정). readiness/liveness probe `/api/health`.
  - `service.yaml` — ClusterIP.
  - `ingress.yaml` — 옵션 (TLS + WebSocket-친화적 nginx annotation).
  - `postgres.yaml` — StatefulSet + Service (옵션 enable). 외부 PG 사용 시 끄기.
  - `pvc.yaml` — workspace + (옵션) postgres RWO PVC.
  - `secret.yaml` — PG 비밀번호 + 임의 secret env.
  - `serviceaccount.yaml`.
- `helm/vibe-coder-server/README.md` — 빠른 설치 / 외부 ingress / 외부 PG / 한계 명시.

**Helm chart 한계 (의식적):**
- Single replica (workspace RWO PVC). HA 불가능.
- `:full` 이미지 (emulator + noVNC) 미지원 — KVM 패스스루 + privileged 컨테이너 필요.

### 보류

- **AST 기반 정의 jump** (△) — Kotlin LSP 통합이 너무 큰 작업이라 별도 phase 로 분리.
- **WebAuthn** (△) — webauthn4j 추가 검토 후 별도 phase.

### Wire change

No (server-internal only).

## [0.46.0] - 2026-05-24 — Phase 25: Web Push (VAPID, payload-less)

### Added

CLAUDE.md §9 △ "Push notification (Web Push)" 항목 실현 — 외부 의존성 없이
JDK 11+ stdlib (`java.security` + `java.net.http`) 만으로 구현.

- **VAPID 키 자동 생성/영속화** — P-256 ECDSA. 워크스페이스 안
  `<workspace.root>/.vibecoder/vapid-keys.json` (atomic write). 컨테이너
  재시작 후에도 동일 키 유지.
- **`WebPushNotifier`**:
  - `publicKeyBase64Url()` — 65-byte uncompressed point (04||X||Y) → base64url
    (브라우저의 `applicationServerKey` 로 그대로 전달).
  - `buildVapidJwt(endpoint, ttlSec)` — RFC 8292 VAPID JWT.
    JOSE ES256 (ECDSA DER → R||S 64 byte raw 변환 포함).
  - `broadcast(title, body)` — 등록된 모든 subscription 에 POST.
    payload-less push (Content-Length 0 — TTL 60s). 410/404 응답 시
    subscription DB 자동 삭제.
- **`PushSubscriptions` 테이블** (PostgreSQL) — endpoint unique, userId
  ref AdminUsers. `PushSubscriptionRepository` 의 upsert / list / deleteById.
- **JSON API**:
  - `GET /api/push/vapid-public-key` — base64url public key.
  - `POST /api/push/subscribe` { endpoint, p256dh, auth, userAgent } —
    upsert subscription. `requireApiWrite` 가드.
  - `DELETE /api/push/subscriptions/{id}` — 본인 정리.
- **SSR 페이지** `/settings/push`:
  - VAPID public key 노출.
  - 현재 브라우저 구독 상태 + "이 브라우저에서 구독" / "구독 해제" 버튼.
  - 등록된 모든 subscription 목록 (사용자 / 등록 시각 / endpoint / 삭제).
  - "테스트 알림 전송" 폼.
- **Service worker 확장** (`/static/sw.js` v0.46.0):
  - `push` event → `showNotification(title, { body, icon, badge, tag })`.
  - `notificationclick` → 기존 탭 focus 또는 `/` 새 탭.
- **Notifiers facade 통합** — build 결과 / Claude usage warn / disk usage
  warn 트리거 시 email / webhook 와 함께 web push 도 자동 broadcast.

### 알려진 한계 (의식적 trade-off)

- **payload-less 모드만 지원.** 알림은 generic 제목으로만 표시.
  진짜 사용자별 메시지를 보내려면 ECDH(VAPID, sub.p256dh) → HKDF →
  AES-128-GCM 암호화 (RFC 8291) 가 필요. 이는 in-house crypto 가
  복잡해 WebAuthn (Phase 27 예정) 와 함께 외부 라이브러리 도입 검토.
- WebAuthn (passkey 2FA) 는 별도 phase 로 분리 — webauthn4j 추가 검토 필요.

### Wire change

No (server-internal — 새 endpoint 들은 모두 web 브라우저 전용; Android
client 가 사용하지 않음).

## [0.45.0] - 2026-05-24 — Phase 24: JSON API + WebSocket role 가드

### Added

CLAUDE.md §9 후속 minor (v0.40.0 의 viewer role 모델 확장) — SSR 외에
**JSON API 와 WebSocket** 에서도 role 검사가 강제되도록 보완.

- **`DevicePrincipal.userRole`** — `installAuth(... userRepo = adminUserRepo)`
  로 device → user lookup 추가. `isAdmin` / `canWrite` 헬퍼 노출.
  Legacy (사용자 미바인딩) 토큰은 안전하게 admin 으로 fallback.
- **`ApplicationCall.requireApiWrite()`** — viewer 거절 (403 `viewer_readonly`).
  적용 endpoint:
  - `POST /api/projects` (register)
  - `DELETE /api/projects/{id}`
  - `POST /api/projects/{id}/build/debug`
  - `POST /api/projects/{id}/builds/{buildId}/cancel`
  - `POST /api/projects/{id}/git/commit`
  - `POST /api/projects/{id}/files/upload`, `DELETE .../files/{fileId}`
  - `POST /api/projects/{id}/claude/console/prompt`
  - `POST /api/projects/{id}/claude/console/new`
  - `POST /api/projects/{id}/claude/console/cancel`
  - `POST /api/projects/{id}/actions/invoke`
- **`ApplicationCall.requireApiAdmin()`** — admin-only (403 `admin_only`).
  적용 endpoint (server-level 설정):
  - `POST /api/env-setup/install-all`, `POST /api/env-setup/install/{id}`
  - `POST /api/claude/auth/upload`, `POST/DELETE /api/claude/auth/api-key`
  - `POST /api/claude/login/{start,submit,cancel}`
  - `POST /api/mcp/install`, `POST /api/mcp/unregister`, `POST /api/mcp/upload/...`
  - `POST /api/git/integrations` (등록 / 삭제 / SSH 키 생성)
- **WebSocket role 가드** — `/ws/projects/{id}/console/logs` 의 `UserPrompt`
  와 `ActionInvoke` 프레임 처리 직전에 viewer role 확인. 차단 시
  `WsFrame.Error("viewer_readonly", ...)` 응답 (연결은 끊지 않고 유지 —
  read 는 계속 가능).

### Wire change

No (server-internal only; 새 응답 코드 `viewer_readonly` / `admin_only` 추가는
viewer 토큰만 받음).

### 보류 (다음 후속)

- **Project ACL** (`project_acls(project_id, user_id)` 테이블) — member 가
  허가된 프로젝트만 보기. 별도 phase 로 분리.
- 나머지 `/settings/{email,webhook,cache,cors,git-integrations}` SSR
  admin 가드 — Phase 26 에서 묶음.

## [0.44.0] - 2026-05-24 — Phase 23: Real multi-agent (sub-agent process pool)

### Added

CLAUDE.md §9 F.○ "Multi-agent orchestration" 항목의 실제 process-pool 구현
(v0.36.0 / v0.41.0 의 dispatch UX 는 단일 child 안에서 sub-agent 호출이라
**병렬 실행이 안 됐음**).

- **`SubAgentSessionManager`** — `(projectId, agentName)` 키로 별도
  Claude child process pool 을 관리. 메인 `ClaudeSessionManager` 와
  완전 독립 (상태/세션ID/topic 분리). 같은 프로젝트 워크스페이스 안에서
  reviewer / frontend / backend 등 여러 agent 가 **병렬 실행**.
  - Session id 파일: `<workspace>/<projectId>/.vibecoder/agent-sessions/<agentName>.id`
  - Idle 30 분 후 SIGTERM (resume 보존 — 다음 prompt 시 같은 sessionId)
  - 첫 prompt 에 자동으로 `Use the <agent> sub-agent to ...` prefix 주입
    → Claude Code 의 표준 sub-agent dispatch 메커니즘 활용
- **SSR 페이지**:
  - `GET /projects/{id}/agents` — 등록된 agent 목록 + 활성 sub-agent
    상태 (`running` / `idle`) + 각각 "콘솔 열기" 링크.
  - `GET /projects/{id}/agents/{agent}/console` — 개별 sub-agent 콘솔.
    메인 콘솔의 트림 버전 (slash chip / template picker 없음).
- **JSON API**:
  - `POST /api/projects/{id}/agents/{agent}/console/prompt` (body `{text}`).
  - `POST /api/projects/{id}/agents/{agent}/console/cancel` — SIGTERM,
    session-id 보존.
  - `POST /projects/{id}/agents/{agent}/new` (form) — 세션 리셋.
  - `GET /api/projects/{id}/agents/active` — 활성 agent 이름 배열.
- **WebSocket** `/ws/projects/{id}/agents/{agent}/console/logs` —
  메인 콘솔과 동일한 replay + live merge 프로토콜.
- **메인 콘솔 헤더**에 `@ sub-agents →` 링크 칩 추가
  (`/projects/{id}/agents` 로 이동).
- **`requireWriteAccessOrRedirect` 적용** — viewer role 은 prompt/cancel/new
  거부 (v0.40.0 의 role 모델 확장).

### Wire change

No (server-internal only; 기존 wire `shared/` 모듈 무변경).

### 갱신 안 한 곳

- Sub-agent 의 turn 결과는 `conversation_turns` 테이블에 적재하지 **않음**
  (history persistence 는 main console 전용으로 유지 — sub-agent 가
  rotation policy 를 흔들 가능성). 휘발성 / 60s 간격 audit log 만 남음.
- vibe-coder-android `shared/` 동기 불필요 (wire 미변경).

## [0.43.0] - 2026-05-24 — Phase 22: VS Code extension full (v0.2.0)

### Added (in `vscode-extension/`)

v0.39.0 의 단일파일 scaffold (5 commands) 에 본격 기능 추가:

- **WebSocket subscribe** — `Vibe Coder: Follow project console` 명령이
  `/ws/projects/{id}/console/logs` 구독. assistant / tool_use /
  tool_result / done / session_started 등 모든 프레임을 VS Code Output
  Channel 에 stream. 같은 프로젝트에서 명령 재실행 시 toggle off.
- **Projects TreeView** — activity-bar 의 새 "Vibe Coder" 컨테이너
  (icon $(rocket)) 안의 "Projects" view. `GET /api/projects` 결과 +
  각 프로젝트 expand 시 `GET /api/projects/{id}/builds` (최근 20개).
  Right-click 메뉴 — Send prompt / Trigger debug build. Inline action —
  Follow console ($(eye) 아이콘).
- **Status bar item** — `$(rocket) <host> (vX.Y.Z)`. 60s 간격 자동
  refresh + 설정 변경 시 즉시 refresh. 클릭하면 `vibeCoder.status` 명령.
- **`onStartupFinished` activation** — VS Code 시작 시 자동 활성화
  (status bar 표시 위해).
- **Config 추가** — `vibeCoder.statusBar` (default true). 끄려면 false.
- **Marketplace 준비** — `icon.png` (현재 서버 아이콘 재사용),
  `keywords`, `categories`, `repository` 필드. `npm run package` 스크립트
  (`@vscode/vsce package`).

### 코드 구조 변경

기존 단일 `src/extension.ts` (~150 LOC) → 4 파일로 분리:

- `src/api.ts` — REST 클라이언트, 설정 헬퍼
- `src/ws.ts` — `ws` npm 패키지로 WebSocket subscribe
- `src/treeview.ts` — `ProjectsTreeProvider`
- `src/extension.ts` — activation entry, 7 commands

새 dep: `ws` (runtime) + `@types/ws` (dev). Node 내장만 사용하던 v0.1
정책에서 한 발 양보 — VS Code WebSocket 폴리필이 없어 가장 표준적인
선택.

### 서버 측 변경 없음

`server.yml` 의 version 만 v0.42.0 → v0.43.0 으로 올림. 새 서버 endpoint
나 wire 변경 없음. VS Code extension 자체 버전은 v0.1.0 → v0.2.0.

### Wire change: No

### 알려진 한계

- **Marketplace publish 안 함** — `vsce publish` 는 PAT 필요, 메인테이너
  수동 단계.
- **Webview 미사용** — Output Channel + TreeView 만. 콘솔을 fully
  interactive 하게 만드려면 Webview API 가 필요한데 본 cycle scope 밖.
- **Icon 미최적화** — 1.6 MB. Marketplace 가 받아주지만 128×128 PNG 로
  최적화 권장.

## [0.42.0] - 2026-05-24 — Phase 21: /emulator/vnc reverse proxy (admin auth)

### Added — `/emulator/vnc/*` HTTP + WebSocket proxy

신규 `server/emulator/VncProxyRoutes.kt`. vibe-coder admin 인증 boundary
안으로 noVNC 를 끌어들임. **외부 의존성 0** — JDK 11+ 표준
`java.net.http.HttpClient` + `WebSocket` 만 사용.

- `GET /emulator/vnc/{path...}` — `http://127.0.0.1:6080/{path}` 로 HTTP
  forward. byte-array body + Content-Type 보존. `application/octet-stream`
  fallback. 15s timeout.
- `WS /emulator/vnc/websockify` — Ktor server WS 가 client 와 연결, 동시에
  JDK `HttpClient.newWebSocketBuilder()` 로 backend `ws://127.0.0.1:6080/websockify`
  열어 양방향 binary/text frame forward.
- WS subprotocol `binary` 명시 (noVNC 표준).
- 인증 가드 — 모든 endpoint 에 `requireAdminOrRedirect`. WS handshake 도
  `vibe_session` cookie + `device→user.isAdmin` 검사. viewer/member 거절.

### Added — `/emulator` 페이지에 inline noVNC iframe

`:full` 이미지 사용자가 별도 SSH 터널 없이 같은 origin 으로 emulator
화면 직접 view 가능. iframe `src="/emulator/vnc/vnc.html?path=emulator/vnc/websockify&autoconnect=true&resize=remote"`.

빈 화면일 때 디버깅 가이드 (`:full` 이미지 / emulator launch / KVM
passthrough 확인) inline hint.

### 보안 향상 (vs v0.25.0)

| | v0.25.0 | v0.42.0 |
|---|---|---|
| noVNC 접근 | 호스트 6080 직접 노출 (no auth) | `/emulator/vnc/` admin 인증 통과 후만 |
| 권장 사용 | LAN 격리 또는 SSH 터널 | reverse proxy 뒤에서 같은 origin |
| CORS / iframe | 다른 호스트라 SOP block | 같은 origin embed 가능 |

`docker/compose.full.yml` 의 `ports: ["6080:6080"]` 는 이제 **불필요**
(주석으로 안내). 컨테이너 내부에서만 6080 노출되고 admin 인증된
vibe-coder 가 proxy.

### Wire change: No (SSR + 새 endpoint family `/emulator/vnc/*`)

### 알려진 한계

- HTTP 응답 streaming 안 함 — 전체 body 를 byte-array 로 받아 한 번에
  serve. 작은 정적 자원이라 OK 지만 큰 파일 (없겠지만) 면 memory ↑.
- Backend WS connect 5s timeout. 첫 페이지 로드 후 emulator 가 booting
  중이면 다시 새로고침.
- WebSocket subprotocol negotiation 만 `binary` 지원. noVNC 의 다른
  subprotocol (`base64`) 은 미지원 — 현대 브라우저는 binary 만 써서 무관.

## [0.41.0] - 2026-05-24 — Phase 20: Multi-agent dispatch UX (1단계)

### 디자인 결정

Real multi-agent process pool ((projectId, agentId) 별 별도 child
process + 탭 UI) 은 매우 큰 변경 — ClaudeSessionManager 의 키 구조 변경,
LogHub topic 분리, conversation_turns 의 agent 컬럼 추가, UI tabbed
console 까지 모두 필요. v0.41.0 은 더 작은 단계로 **사용자가 표준
sub-agent dispatch 를 1-click 으로 사용** 할 수 있도록 콘솔 UI 만 강화.

### Added — `@ Agent dispatch` 드롭다운 (콘솔 페이지)

콘솔 페이지의 prompt 입력 영역 위 picker 줄에 새 셀렉트 박스 추가.

- 페이지 로드 시 `GET /api/agents` (v0.36.0+) 호출 → 등록된 sub-agent
  목록을 채움.
- 선택 시 prompt 입력란에 `Use the <agent-name> sub-agent to ` prefix
  자동 삽입. 기존 입력 보존 (prefix 만 prepend).
- 이미 `Use the X sub-agent to` 가 있으면 agent 이름만 교체 (중복 prefix
  방지).
- 등록된 agent 가 없으면 `(등록된 agent 없음 — /agents)` placeholder.

Claude Code 의 표준 sub-agent dispatch 메커니즘을 그대로 활용 — 서버 측
프로토콜 변경 없이 UX 만 개선.

### Wire change: No (콘솔 JS 만)

### Real multi-agent — Roadmap

진짜 process pool 은 다음 cycle 후보 (v0.41.x 또는 v0.42+):

- `SubAgentSessionManager` 가 (projectId, agentName) → 별도 `claude`
  child process spawn.
- LogHub topic 분리 (`projectId/agentName`).
- 콘솔 UI 에 agent tab (메인 + sub-agent 별).
- `conversation_turns.agent_name` 컬럼 추가.

이번 단계는 가장 가치 있는 부분 (사용자가 agent 를 쉽게 호출하는 UX) 만
선제 도입. 진짜 병렬 process pool 이 필요해질 때 위 작업 진행.

## [0.40.0] - 2026-05-24 — Phase 19: admin 가드 강화 + viewer role

### Added — `viewer` role (read-only)

CLAUDE.md §9.G 의 멀티 사용자 2단계. `admin_users.role` 의 허용 값에 `viewer`
추가:

- **admin** — 모든 권한 (관리 페이지 + write 작업)
- **member** — 작업 페이지 + 모든 write
- **viewer** — **read-only**. 콘솔 prompt / 빌드 큐 / git commit / 파일
  업로드 등 차단

### Added — `requireWriteAccessOrRedirect(sess)` helper

`requireSessionOrRedirect` 와 chain 으로 사용. viewer 세션이 write
endpoint 에 도달하면 dashboard 로 redirect (msg: "viewer 권한으로는
변경할 수 없습니다.").

`WebSession.canWrite` derived flag (`admin` 또는 `member`).

### 적용된 write 가드 (SSR)

- `POST /projects` (프로젝트 생성)
- `POST /projects/{id}/delete`
- `POST /projects/{id}/console/new`
- `POST /projects/{id}/console/slash`
- `POST /projects/{id}/builds` (debug 빌드 큐 등록)
- `POST /projects/{id}/files/upload`
- `POST /projects/{id}/edit` (파일 편집 저장)
- `POST /projects/{id}/git/commit`
- `POST /agents/save`, `POST /agents/{name}/delete`

다른 write endpoint (settings, build cancel, multi-console 등) 은 v0.40.x
에서 점진 적용.

### 적용된 admin 가드 (SSR)

`requireAdminOrRedirect` 가 다음 페이지에 추가됨:

- `/audit`
- `/settings` (GET + POST)
- `/backup` + `/backup/download`

기존부터 admin 가드가 있던 `/users` 는 변경 없음. `/2fa` 는 의도적으로
admin 가드 안 함 (개인 보안 설정이라 본인 관리 필요).

### `/users` UI 갱신

- role 옵션에 `viewer (read-only)` 추가.
- 역할 토글 버튼이 cycle (admin → member → viewer → admin) 로 동작.
- role 정책 hint 갱신.

### Wire change: No (SSR + 서버 내부 가드 만)

### 알려진 한계

- **JSON API** (`/api/*`) 는 여전히 role 가드 미적용. Bearer 토큰이 admin /
  member / viewer 어느 사용자 것이든 같은 권한으로 동작. 후속 minor 에서
  AuthPlugin 단계에서 가드 추가 검토.
- **WebSocket** 도 동일 — viewer 의 토큰으로도 console / build log
  stream 구독 가능. read-only 행동이라 의도된 대로 동작.
- **프로젝트별 ACL** (member 가 일부 프로젝트만 보기) 는 별도 minor.
  현재는 워크스페이스 전체 share.
- 일부 write endpoint (build cancel / multi-console / 의존성 audit /
  wrapper update 등) 는 가드 미적용. v0.40.x 에서 완성.

## [0.39.0] - 2026-05-24 — Phase 18: △ 묶음 (PWA + VSCode extension scaffold)

### Added — PWA (manifest + service worker)

브라우저가 admin UI 를 native-like 앱으로 install 할 수 있도록:

- 신규 `static/admin/manifest.json` — name / start_url / display=standalone /
  theme_color #0b0d12 / icon 512x512 maskable.
- 신규 `static/admin/sw.js` — minimal service worker:
  - install: STATIC_ASSETS (CSS / JS / icon / manifest) precache.
  - activate: 이전 버전 cache 정리, `clients.claim()`.
  - fetch: `/static/*` cache-first (opportunistic fill 포함), `/api/*` /
    `/ws/*` / `/admin/*` 는 network passthrough (실시간 상태 stale 방지).
  - `CACHE_VERSION` 을 매 minor 업데이트 시 invalidate.
- `AdminTemplates.shell` 의 `<head>` 에 manifest link + theme-color +
  service worker 등록 (`navigator.serviceWorker.register('/static/sw.js')`).

이제 모바일 / 데스크톱 브라우저에서 "홈 화면에 추가" 또는 "앱으로 설치"
가능. 오프라인 stale 페이지 표시는 안 함 (SSR 컨텐츠는 실시간성 우선).

### Added — VS Code extension scaffold (`vscode-extension/`)

별도 리포로 분리할 수도 있지만 본 리포 안에 같이 둠 (의존성 / wire 가
같이 진화).

- `package.json` + `tsconfig.json` + `src/extension.ts` (단일 파일, ~150 LOC).
- 외부 npm 의존성 0 — `@types/vscode` + `typescript` 만.
- HTTP via Node 내장 `http`/`https`.
- 5 commands:
  - **Vibe Coder: Login** (interactive — server URL + username + password +
    optional TOTP, `totp_required` 자동 처리)
  - **Vibe Coder: Server status**
  - **Vibe Coder: List projects** (quick-pick)
  - **Vibe Coder: Send prompt to project console**
  - **Vibe Coder: Trigger debug build**
- 설정: `vibeCoder.serverUrl` + `vibeCoder.token` (Global persist).
- WS subscribe / TreeView / status bar 는 후속 minor.

VS Code Marketplace publish 는 별도 단계 (vsce package + login). 본
cycle 은 scaffold + 로컬 dev 가능 (F5 → Extension Development Host).

### Web Push / WebAuthn — Roadmap 갱신만

CLAUDE.md §9 의 △ 항목 중 두 항목은 큰 작업이라 본 cycle 에선 미진행:

- **Web Push** — VAPID 키 + subscription endpoint + 알림 트리거 통합.
  EmailNotifier / WebhookNotifier 옆에 PushNotifier 추가 형태. v0.40+.
- **WebAuthn** — passkey 라이브러리 (예: `webauthn4j`) 의존성 추가 +
  credential register/verify 흐름. 2FA TOTP 의 phishing-resistant 강화
  버전. v0.40+.

### Wire change: No (static asset + 외부 ide 확장 만)

### 호환성

- 기존 사용자: PWA 는 progressive — 지원 안 하는 브라우저는 무시.
- VS Code extension 은 별도 install 필요 (현재는 dev mode 만).

## [0.38.0] - 2026-05-24 — Phase 17: Ubuntu 26.04 LTS rebase

### Changed — Base image: `noble` → `resolute`

eclipse-temurin 의 `17-jdk-resolute` / `17-jre-resolute` 태그가 Ubuntu
26.04 LTS (Resolute Raccoon) 매핑 확정:

```
PRETTY_NAME="Ubuntu 26.04 LTS"
VERSION_ID="26.04"
VERSION_CODENAME=resolute
```

JDK 17.0.19 동일.

`docker/Dockerfile` 의 builder + runtime 양쪽 stage 모두 rebase. `Dockerfile.full`
(emulator + noVNC) 도 동일하게 변경.

### 영향

- **API / 기능**: 동일. 코드 변경 없음.
- **이미지 크기**: noble (24.04) ↔ resolute (26.04) base 차이는 무시할 수
  있음 (수십 MB 수준).
- **빌드**: `apt-get install -y ...` 의 패키지 목록 그대로 동작 — 모든
  의존 패키지가 Resolute repo 에 존재.
- **호환성**: 사용자 입장에선 `docker compose pull && up -d` 한 줄로 완료.
  `vibe-coder-data` 의 모든 영구 데이터 유지.

### Wire change: No (Docker base image rebase 만)

### Known follow-up

- LTS 지원 기간 (Ubuntu 26.04 LTS = 2031년 4월까지) 동안 base 고정.
- Eclipse Temurin JDK 21 LTS 전환은 Ktor 4.x 출시와 묶어 별도 minor.

## [0.37.0] - 2026-05-24 — Phase 16: 멀티 사용자 / 팀 (1단계 — role 만)

### Schema — `admin_users.role` 컬럼 추가

`varchar("role", 16).default("admin")` — Exposed 의 nullable 없는 default
로 기존 row 도 안전하게 자동 마이그 (모든 기존 사용자 = admin).

값: `admin` / `member`. `viewer` (read-only) 는 후속 minor 에서 추가.

### Added — `/users` SSR (admin 만)

신규 `server/admin/UsersRoutes.kt`.

- `GET /users` — 전체 사용자 + role badge + 가입 / 마지막 로그인 + 동작 버튼.
- `POST /users` — 신규 사용자 (username + password + role 선택).
  PasswordPolicy / UsernamePolicy 그대로 적용.
- `POST /users/{id}/role` — admin ↔ member 토글. 마지막 admin 강등 차단.
- `POST /users/{id}/delete` — 사용자 + 모든 device row cascade. 자기 자신 /
  마지막 admin 삭제 차단.
- 모든 endpoint 가 admin role 만 (member 가 접근하면 dashboard redirect).
- Nav 메뉴 "사용자" + 키보드 단축키 `g u` 추가.
- Audit: `user.create` / `user.role.change` / `user.delete`.

### Added — `requireAdminOrRedirect(sess)` helper

`requireSessionOrRedirect` 와 chain 으로 사용. 본 cycle 에선 `/users` 만
가드. 다른 관리 페이지 (`/audit`, `/settings`, `/backup`, `/2fa`, `/agents`)
는 v0.37.x 에서 점진적으로 admin-only 로 강화.

### `WebSession.role` + `isAdmin` 추가

`requireSessionOrRedirect` 가 user.role 을 채워 session 에 노출. 템플릿에서
role 별 분기에 사용 가능.

### Wire change: No (SSR + DB schema 만)

### 호환성

- 기존 admin 사용자: schema migration 으로 자동 `role='admin'`. 영향 없음.
- 신규 사용자 default role: `member`. 명시적 admin 으로 만들려면 `/users`
  폼에서 role 변경 + 생성.
- 첫 admin (setup) 은 코드가 직접 `role="admin"` 으로 insert.

### 알려진 한계

- 본 cycle 의 가드는 `/users` 만. 다른 관리 페이지는 후속 minor 에서
  `requireAdminOrRedirect` chain 적용.
- 프로젝트별 ACL (member 가 일부 프로젝트만 보기) 은 v0.38+ scope.
- viewer role (read-only) 미구현 — admin / member 만.

## [0.36.0] - 2026-05-24 — Phase 15: Multi-agent orchestration (단순화)

### 디자인 결정

여러 sub-agent 가 같은 프로젝트 안에서 동시에 돌아가는 full orchestration
은 `ClaudeSessionManager` 가 (projectId, agentId) pair 별로 독립 child
process 를 spawn 해야 해서 큰 작업. **두 단계 단순화** 로 v0.36.0 의 가치
대부분을 더 작은 변경으로 확보:

1. **Agent dispatch API + UI hook** — Claude Code 의 표준 "Use the
   `<agent-name>` sub-agent to ..." prompt 패턴을 콘솔 dropdown 으로 안내.
   사용자가 등록된 agents 를 한눈에 보고 prompt 에 prefix 넣어주는 도우미.
2. **Multi-console** — N개 프로젝트의 콘솔을 iframe grid 로 동시 view.
   별도 sub-agent spawn 없이 multi-project orchestration use case 해결.

Full sub-agent process pool 은 v0.36.x 후속에서 별도 minor.

### Added — `GET /api/agents` (Bearer)

`AgentRoutes` 에 JSON endpoint 추가 — 등록된 agent 목록 (name + sizeBytes +
preview 200자) 반환. 콘솔 UI 가 ▼ 드롭다운 채우는 데 사용 (안드로이드
클라이언트도 같은 endpoint 활용 가능).

```bash
curl -H "Authorization: Bearer $TOKEN" http://localhost:17880/api/agents
# → { "agents": [ {"name":"code-reviewer", "sizeBytes":1234, "preview":"..."}, ... ] }
```

### Added — `/multi-console?projects=id1,id2,...`

신규 `server/admin/MultiConsoleRoutes.kt`.

- 최대 6개 프로젝트 콘솔을 iframe grid 로 동시 노출.
- 같은 origin 라 cookie 인증이 그대로 흘러감 (추가 auth 호출 불요).
- 1개 → full-width / 2개 → 50:50 / 3+ → `auto-fit minmax(420px, 1fr)`.
- 프로젝트 id 화이트리스트 (`[a-zA-Z0-9._-]`) — URL 조작 차단.
- 각 pane 우상단에 ↗ 새 탭 링크.
- Nav 메뉴 "Multi-console" + 키보드 단축키 `g m` 추가.

### Wire change: No

### 알려진 한계

- iframe 6개가 각각 WebSocket 을 열어 reverse-proxy 의 연결 한도에 주의.
- Full multi-agent (한 프로젝트에서 여러 sub-agent 병렬 spawn) 은 v0.36.x
  후속 — `ClaudeSessionManager` 의 (projectId, agentId) 분기 + 별도 tab UI
  까지 큰 작업이라 별도 cycle.

## [0.35.0] - 2026-05-24 — Phase 14: 코드 분석 묶음

### Added — `/projects/{id}/wrapper` Gradle wrapper 관리

신규 `server/build/GradleWrapperService.kt`.

- `inspect(projectId)` — `gradle/wrapper/gradle-wrapper.properties` 파싱.
  현재 버전 + distributionType + raw URL 추출.
- `setVersion(projectId, newVersion, distributionType="bin")` — `distributionUrl`
  만 atomic write 로 교체. 다른 properties 보존.
- 버전 정규식 `^[0-9]+(\.[0-9]+)*(-rc-[0-9]+)?$`, distributionType `bin`/`all`
  화이트리스트 (path injection 차단).
- SSR `/projects/{id}/wrapper` 페이지 — 현재 상태 + 변경 폼 + gradle.org/releases 링크.
- Audit: `wrapper.update`.
- 프로젝트 detail 액션 카드에 "📦 Gradle wrapper" 링크.

### Added — `/projects/{id}/stats` 코드 통계

신규 `server/projects/CodeStatsService.kt`.

- 프로젝트 source 트리를 in-process walk + 확장자 기반 언어 분류
  (Kotlin / Java / Swift / Go / Rust / TS / ... 35+ 언어).
- 단순 line count (cloc 처럼 주석/공백 구분 안 함). 외부 도구 의존 없음.
- 제외: `.git`, `build`, `.gradle`, `node_modules`, `.idea`, 5 MB 초과 파일,
  바이너리 확장자 (30+).
- SSR 페이지 — 총 파일/라인/크기 카드 + 언어별 lines DESC 표 + 컬러 바.
- 프로젝트 detail 액션 카드에 "📊 코드 통계" 링크.

### Added — `/code-search` 워크스페이스 grep

신규 `server/projects/CodeSearchService.kt` + `/code-search` SSR.

- 모든 프로젝트의 source 트리를 line-by-line scan.
- 200 매치 hard cap, 5 MB 초과 파일 / 바이너리 skip.
- case-sensitive 토글, 프로젝트 필터.
- 매치 위치 `<mark>` 하이라이트, file:line 클릭 → 기존 `/projects/{id}/view` 파일 뷰어로 jump.
- Nav 메뉴 "코드 검색" + 키보드 단축키 `g f` 추가.

### Wire change: No (SSR + 서버 내부 wiring 만)

### 알려진 한계

- 코드 통계가 cloc 수준 정확도 아님 (주석/공백 구분 안 함). 외부 도구 통합은 후속.
- 워크스페이스 grep 이 in-process scan — 수만 파일 규모에선 ripgrep 대비 느림.
  ripgrep binary 가 컨테이너에 있으면 자동 fallback 검토 (다음 minor).
- Wrapper 변경 후 빌드 재실행은 사용자 책임 — 자동 invalidate 없음.

## [0.34.0] - 2026-05-24 — Phase 13: Backup/restore + CLI MVP

### Added — `/backup` SSR + tar.gz stream

신규 `server/admin/BackupRoutes.kt` + Apache Commons Compress 의존성.

- `GET /backup` — 워크스페이스 서브디렉토리 별 size + 다운로드 안내.
- `GET /backup/download` — tar.gz stream (Content-Disposition).
- 제외 패턴: `postgres/` (pg_dump 별도 권장), `dev-tools/gradle/caches`,
  `gradle/daemon`, `npm-cache`, `playwright`, 빌드 logs/. 일반 백업이 수십 MB.
- PostgreSQL 은 page-tear 위험으로 raw tar 안 함 — 페이지에 `pg_dump -F c`
  명령 가이드 inline.
- 복원 절차도 페이지 안에 inline (tar xzf + compose up -d).
- Nav 메뉴 "백업" 추가.

### Added — `cli/vibe` — bash + curl MVP

신규 `cli/vibe` 단일 파일 (no compile, ~150 LOC).

- `vibe login` — 대화형, 2FA 자동 감지 (totp_required → 코드 prompt).
- `vibe whoami` / `vibe logout` — token lifecycle.
- `vibe projects` / `vibe status` — JSON dump.
- `vibe console <id> <prompt...>` — 1회 prompt 발송 (WS log 는 별도).
- `vibe build <id>` — debug 빌드 큐.
- 토큰 저장: `$XDG_CONFIG_HOME/vibe-coder/config` (0600 권한).
- jq optional (있으면 pretty print).
- Go/Rust 정식 CLI + WS subscribe / 파일 업로드 는 후속 minor / 별도 리포.

### Ubuntu 26.04 LTS 베이스 이미지 — 검토만

`eclipse-temurin:17-jdk-resolute` 태그 확정 후 전환 예정. v0.34.0 에선
slim/full 모두 `noble` (24.04 LTS) 유지. 단순 base 변경이라 별도 minor
(v0.34.x) 에서 image 만 push.

### Dependency

- `org.apache.commons:commons-compress:1.27.1` (tar/gzip streaming).

### Wire change: No

### 알려진 한계

- tar.gz 가 streaming 이긴 하나 큰 워크스페이스 (수십 GB) 면 다운로드가 길어짐.
  진행률 표시 없음.
- CLI 가 WS 미지원 — 콘솔 stream 보려면 브라우저 사용 필요.

## [0.33.0] - 2026-05-24 — Phase 12: 자동화 (cron / webhook / archive)

### Added — Cron 빌드 schedule

신규 `build_schedules` 테이블 + `BuildScheduleRepository` + `BuildScheduler`.

- 매 60 s tick. cronExpr `HH:MM` (고정 시각) / `*:MM` (매시간 MM 분) / `*:*` (테스트용 매 분).
- 분 단위 dedupe (`lastFiredAt.startsWith(minute)`) — 같은 분 중복 발사 차단.
- Full vixie-cron expression 은 후속 minor.
- `/projects/{id}/automation` 페이지에 생성/활성/삭제 폼.
- Audit: `schedule.create` / `schedule.delete`.

### Added — Build webhook (외부 트리거)

신규 `build_webhook_secrets` 테이블 + `BuildWebhookSecretRepository`.

- 사용자가 `/projects/{id}/automation` 에서 "+ 새 secret" 클릭 → 32-byte
  URL-safe random secret 1회 노출. SHA-256 hex 만 DB 저장.
- 외부 호출: `POST /api/webhooks/build/{projectId}` (admin auth 없음).
  필수 헤더:
  - `X-Vibe-Secret-Id: <id>` — DB lookup 용.
  - `X-Vibe-Secret: <plaintext>` — TLS 의존, server 가 sha256 비교.
  - `X-Vibe-Signature: <hex>` — optional HMAC-SHA256(secret, body), body
    integrity 추가 보장.
- 본 cycle 의 단순화: GitHub-style HMAC 만으로 verify 하면 sender 가
  알고 있는 plaintext secret 을 server 가 모르므로 X-Vibe-Secret 도
  헤더로 받음. HTTPS reverse-proxy 필수.
- Audit: `webhook.secret.create`, `webhook.secret.delete`, `webhook.build.trigger`.

### Added — Conversation 자동 archive

신규 `ConversationArchiver` — 매 24h tick.

- `archiveAfterDays` (기본 30) 이상 inactive 한 (projectId, sessionId)
  pair 찾기.
- 해당 session 의 turn 들을 `ConversationExportService.ExportEnvelope` JSON
  으로 dump → `<workspace>/.vibecoder/<projectId>/archive/session-<sid>.json`.
- dump 성공 시 `conversation_turns` 에서 해당 row 삭제 (DB 부담 ↓).
- 복원: `ConversationExportService.importToProject(json)` 그대로 사용.
- 별도 archive 테이블 안 만듦 — 파일 시스템에 두면 backup / 외부 export 단순.

### Wire change: No (SSR + 외부 webhook endpoint 만)

### 알려진 한계

- Webhook 인증이 X-Vibe-Secret 헤더 plaintext 전송 — TLS 필수. GitHub-style
  HMAC-only 검증으로 강화는 후속 minor (sender 가 보낸 candidate-plaintext
  를 서버가 lookup-then-verify 하는 방식).
- Cron expression 이 HH:MM / *:MM / *:* 만 지원. 요일/월/특정 날짜는 후속.
- Archive 의 dump-then-delete 가 atomic 하지 않음 — server crash 시 dump 성공
  + 삭제 실패면 다음 tick 에서 idempotent re-run (`Files.exists(target)` skip).

## [0.32.0] - 2026-05-24 — Phase 11: 운영 도구 (deps audit / env files / log search)

### Added — `/projects/{id}/deps` — Gradle 의존성 audit

- 신규 `server/build/DependencyAudit.kt` + `DependencyAuditRoutes.kt`.
- `./gradlew :{module}:dependencies --configuration <cfg>` 실행 → raw output +
  `group:name:version` 좌표 추출 (정규식, version conflict resolution `->`
  처리).
- 모듈명 / configuration 파라미터화 (기본 `releaseRuntimeClasspath`).
- 90 s timeout, raw output 200 KB cap.
- 알려진 CVE 매칭은 후속 minor (OWASP dependencyCheckAnalyze / osv-scanner
  통합 검토).
- 프로젝트 detail 페이지에 "🧩 의존성 audit" 액션 링크.

### Added — `/projects/{id}/env-files` — Env / Build 파일 빠른 편집

- 신규 `server/projects/EnvFilesRoutes.kt`.
- 화이트리스트 7개 파일 (`local.properties`, `gradle.properties`, `.env`,
  `.env.local`, `app/build.gradle.kts`, `build.gradle.kts`,
  `settings.gradle.kts`) 만 노출 — path traversal 차단.
- Atomic move 저장 (빌드 중 race 안전), 256 KB cap.
- 비밀 파일 (`.env` / `.properties`) 에 노출 경고 hint.
- 프로젝트 detail 페이지에 "⚙ Env / Build 파일" 액션 링크.

### Added — `/logs` — 빌드 로그 가로질러 grep

- 신규 `server/admin/LogSearchRoutes.kt`.
- 워크스페이스의 모든 `.vibecoder/<projectId>/logs/<buildId>.log` 를 검색.
- 각 파일 마지막 2 MB 만 scan (큰 빌드 로그 성능 보호).
- 200 매치 hard cap, ts 자동, project 필터 옵션.
- 매치 라인 `<mark>` 하이라이트 (case-insensitive).
- Nav 메뉴 "빌드 로그 검색" + 키보드 단축키 `g l` 추가.

### Wire change: No (SSR + 서버 내부 wiring 만)

### 알려진 한계

- 의존성 audit 의 CVE 매칭이 빠져 있음 — 현재는 단순 트리 + 좌표 추출.
- env-files 의 화이트리스트는 hard-coded — 사용자 정의 패턴 미지원.
- log search 가 server stdout 로그는 못 봄 (Docker logs 로 확인 필요).

## [0.31.0] - 2026-05-24 — Phase 10: Claude 통합 강화

### Added — `/agents` 디렉토리 관리

신규 `server/env/AgentRegistry.kt` + `AgentRoutes.kt` — `~/.claude/agents/*.md`
custom sub-agent 파일을 UI 에서 CRUD.

- 이름 sanitize (`[A-Za-z0-9._-]{1,64}`, 숨김파일 차단).
- 본문 64 KB cap, atomic write (`tmp` → `move REPLACE_EXISTING`).
- 목록 페이지에 미리보기 (첫 600자) + 마지막 수정 시각 + 삭제 버튼.
- Edit 페이지에서 본문 수정.
- Nav 메뉴 "Agents" 추가.
- Audit: `agent.save` / `agent.delete` 액션 신규.

### Added — 대화 export / import

신규 `server/claude/ConversationExportService.kt` — 프로젝트 단위
`conversation_turns` JSON envelope export + import.

- `GET /projects/{id}/history/export` — `application/json` + Content-Disposition
  으로 즉시 다운로드. 페이지네이션 loop (1000 개씩, 100 K turn safety cap).
- `POST /projects/{id}/history/import` (multipart, `?_csrf=` query) —
  envelope 검증 + sessionId 단위 idempotency. `?dryRun=true` (기본) 으로
  미리보기, `?dryRun=false` 면 실제 INSERT.
- 5 MB 업로드 cap, schema `version 1` 명시.
- History 페이지 헤더에 📥 다운로드 + 📤 가져오기 토글 폼.

### Added — Prompt 자동완성 API

신규 `server/claude/PromptSuggestionService.kt` + JSON endpoint.

- `GET /api/projects/{projectId}/claude/prompt-suggestions?prefix=<text>` →
  `{ "suggestions": [...] }`.
- 같은 프로젝트의 `user` role turn 중 prefix 매치 (LIKE) → 최근 사용 우선
  + 짧은 prompt (<10자) 제외 + 첫 줄만 (최대 200자).
- In-memory cache 60 s — 사용자가 매 키스트로크마다 DB hit 안 함.
- 안드로이드 클라이언트 / 콘솔 JS 양쪽이 활용 가능 (Bearer 인증).

### Wire change: No (server-side only — 클라이언트가 새 endpoint 호출 시 자동 사용)

### 알려진 한계

- export/import 의 idempotency 가 sessionId 단위라, 같은 세션을 부분 import
  못 함 (전체 skip). row 단위 merge 가 필요해지면 다음 cycle.
- prompt suggestion 은 prefix only (n-gram / fuzzy 미지원). 큰 history
  에서 LIKE 가 느려질 수 있음. v0.32+ 에서 tsvector 검토.

## [0.30.0] - 2026-05-24 — Phase 9: UX 묶음 (history chart / global search / keyboard)

### Added — 빌드 history 차트

빌드 목록 페이지 (`/projects/{id}/builds`) 상단에 최근 30개 빌드의 inline
SVG line chart 표시. 외부 라이브러리 없음 (서버 사이드 SVG 생성).

- X 축: 시간 순 (oldest → newest).
- Y 축: duration (s). 초록 line = SUCCESS 연결, 빨강 점 = FAILED / TIMEOUT,
  회색 = CANCELED, 노란 사각 = APK 크기 (보조 축).
- 점 hover 시 빌드 id + 상태 + duration tooltip (`<title>`).

### Added — 글로벌 대화 검색 (`/history`)

기존 프로젝트별 `/projects/{id}/history` + `/chat/history` 외에 모든
프로젝트의 `conversation_turns` 를 가로질러 grep 하는 페이지.

- 신규 `server/claude/GlobalHistorySearchRoutes.kt` — `q` + `role` 필터.
- LIKE escape (`\` / `%` / `_`) → SQL injection 차단.
- 매치 위치 ±100자 발췌 + `<mark>` 하이라이트 (case-insensitive).
- 200개 hard cap, ts DESC 정렬.
- 빈 검색어 = 빈 결과 (전체 dump 방지).
- Nav 메뉴 "대화 검색" 항목 추가.

### Added — 키보드 단축키

신규 `static/admin/keyboard.js` — `defer` 로드, 모든 SSR 페이지 적용.
입력 필드 focus 시 / 모디파이어 키 동반 시 무시.

| 단축키 | 동작 |
|---|---|
| `g p` | /projects |
| `g c` | /chat |
| `g h` | /history (글로벌 검색) |
| `g e` | /env-setup |
| `g s` | /settings |
| `g a` | /audit |
| `g d` | / (대시보드) |
| `?` | 단축키 도움말 오버레이 토글 |
| `Esc` | 오버레이 닫기 |

2키 시퀀스는 800 ms timeout. 도움말 오버레이는 inline HTML (외부 자원 없음).

### Wire change: No (SSR + 정적 자원 만)

### 알려진 한계

- 글로벌 검색은 LIKE 기반 — 수만 turn 누적 시 느려질 수 있음. PostgreSQL
  `tsvector` 인덱스 도입은 다음 cycle (별도 minor) 검토.
- 빌드 history 차트는 최근 30개 hard-coded; 사용자가 페이지 단위 / 기간
  필터링 못 함. 큰 작업이라 v0.30.x 후속.

## [0.29.0] - 2026-05-24 — Phase 8: 프로젝트 zip 다운로드 + 디스크 사용량 모니터링

### Added — `ProjectArchiver` + `GET /projects/{id}/zip`

- 신규 `server/projects/ProjectArchiver.kt`:
  - 프로젝트 source 트리를 `java.util.zip.ZipOutputStream` 으로 직접 stream
    (메모리 폭발 없음).
  - 제외 패턴: `.git`, `build`, `.gradle`, `node_modules`, `.idea`, `*.apk`,
    `*.aab` — source 본질만 추출.
- `GET /projects/{id}/zip` SSR 라우트 — `Content-Disposition` 으로 브라우저
  zip 다운로드 트리거, 파일명 `<projectId>-source-<yyyyMMdd-HHmm>.zip`.
- 프로젝트 detail 페이지에 "Source zip 다운로드" 액션 링크.

### Added — `DiskMonitor` + 대시보드 카드

- 신규 `server/disk/DiskMonitor.kt`:
  - `measureNow()` — `Files.getFileStore(root)` 의 total/usable 로 사용 % 계산.
  - `start()` — 10분 주기 폴링. `usedPercent >= warnThresholdPercent` (기본 85%)
    로 transition 시 1회 `Notifiers.diskUsageWarn` (이메일 + webhook). 회복 시
    상태 reset → 재발송 가능. 30분 cooldown.
  - `warnThresholdPercentProvider = { config.email.diskUsageWarnPercent }` —
    기존 이메일 임계치 값 재사용 (별도 webhook 임계치 분리는 v0.30+).
- 대시보드에 디스크 사용량 카드 — 사용 % / 총 / 가용 GB + 컬러 바 (85%↑ 노랑,
  95%↑ 빨강).
- 캐시 정리 페이지 (`/settings/cache`) 로의 link 표시.

### Wiring

- ServerContext: `projectArchiver` + `diskMonitor` 추가.
- ServerMain: 두 서비스 생성 + `diskMonitor.start()` + shutdown hook 에 양쪽 등록.
- AdminRoutesDeps: `diskMonitor` 추가 → dashboard 카드 렌더링.
- WebProjectRoutes: `projectArchiver` 파라미터 추가.

### Wire change: No (SSR + 서버 내부 wiring 만)

## [0.28.0] - 2026-05-24 — Phase 7: APK 시그너처 검증 + 빌드 캐시 관리

### Added — `ApkSignerInspector` (apksigner verify wrapper)

- 신규 `server/artifacts/ApkSignerInspector.kt`:
  - `$ANDROID_HOME/build-tools/<latest>/apksigner verify --verbose --print-certs <apk>`
    실행 후 출력을 정규식으로 파싱.
  - 추출 항목: 활성 schemes (v1/v2/v3/v4), Signer #N (Subject DN + SHA-256 fingerprint),
    verified 여부 (exit 0 + scheme 1개 이상 + "DOES NOT VERIFY" 없음).
  - SDK / build-tools 미설치 시 graceful error 메시지.
  - 30s timeout, raw output 4000자 cap.
- 빌드 상세 페이지의 APK 카드 안에 검사 결과 inline 표시 — verified 배지 +
  활성 schemes + Signer 별 DN/SHA-256.

### Added — `BuildCacheService` + `/settings/cache` SSR

- 신규 `server/build/BuildCacheService.kt`:
  - `measure()` — `~/.gradle/caches`, `~/.gradle/daemon`, `~/.android/cache`,
    `~/.npm/_cacache` 디렉토리 크기 합산 (Files.walk + size).
  - `cleanup(target)` — bottom-up walk + Files.delete, 디렉토리 자체는 보존
    (graceful skip on permission errors).
  - Target enum: `GRADLE_CACHES`, `GRADLE_DAEMON`, `ANDROID_CACHE`, `NPM_CACHE`.
- 신규 `server/build/BuildCacheRoutes.kt` (`/settings/cache`) — 4개 target 의
  현재 크기 표 + per-target "정리" 버튼 (CSRF + confirm 다이얼로그). 빌드
  진행 중 cleanup race 안내.
- Nav 메뉴에 "빌드 캐시" 추가.

### Wire change: No (SSR + 서버 내부 wiring 만)

### 호환성

- SDK 미설치 환경: APK 카드의 서명 검사 섹션이 "ANDROID_HOME 미설정" graceful
  안내. 빌드 디테일 페이지 자체는 영향 없음.
- 빌드 캐시 cleanup 은 다음 빌드의 의존성 재다운로드를 유발 — 사용자가 명시
  click 한 경우만. confirm 다이얼로그 + 빌드 0개일 때만 권장.

## [0.27.0] - 2026-05-24 — Phase 6: Slack / Discord / Telegram webhook

### Added — `WebhookNotifier` + `/settings/webhook` SSR

기존 `EmailNotifier` 와 같은 트리거 (빌드 결과 / Claude 사용량 / 디스크 임계치)
에 병렬 발송. JDK 11+ 표준 `java.net.http.HttpClient` 만 사용 (외부 의존성 없음).

- 신규 `server/notify/WebhookNotifier.kt`:
  - Provider 별 payload 빌더: Slack (`text` + 코드블록), Discord (`content` cap 2000),
    Telegram (`text` + Markdown).
  - **SSRF 방어 화이트리스트**: Slack=`hooks.slack.com`, Discord=`discord.com` /
    `discordapp.com`, Telegram bot token 정규식 `^\d+:[A-Za-z0-9_-]+$`.
  - Fire-and-forget `send()` + 동기 `sendNow()` (테스트용, provider 별 결과 맵 반환).
  - 10s connect/request timeout, `Redirect.NEVER` (cross-origin SSRF 차단).
- 신규 `server/notify/Notifiers.kt` — Email + Webhook 통합 facade. 호출처
  (`BuildService`, `ClaudeUsageMonitor`) 는 facade 하나만 알면 됨.
- 신규 `server/notify/WebhookSettingsRoutes.kt` — `/settings/webhook` SSR.
  현재 설정 (값은 가려서 "set/empty" 만) + provider 별 setup 가이드 (Slack
  Incoming Webhook / Discord Webhook / Telegram BotFather) + 테스트 메시지
  전송 폼.

### Added — `WebhookSection` 설정

```yaml
webhook:
  enabled: false
  slackUrl: ""
  discordUrl: ""
  telegramBotToken: ""
  telegramChatId: ""
```

env override (`VIBECODER_WEBHOOK_ENABLED` / `_SLACK_URL` / `_DISCORD_URL` /
`_TELEGRAM_BOT_TOKEN` / `_TELEGRAM_CHAT_ID`) 는 server.yml 의 같은 키와 매칭.
부분 활성 가능 (예: Slack 만).

### Refactored — `BuildService.notifier` 타입 변경

- 기존 `EmailNotifier?` → `Notifiers?` 로 교체. 외부 호출 시그니처 동일.
- `ClaudeUsageMonitor.emailNotifier` 파라미터도 `notifiers: Notifiers` 로 교체.
  WebhookNotifier 가 같은 임계치 알림을 받음.

### Wire change: No (SSR + 서버 내부 wiring 만)

### Nav 메뉴

좌측 nav 에 "Webhook 알림" 항목 추가 (이메일 알림 옆).

## [0.26.0] - 2026-05-24 — Phase 5: 2FA TOTP + Session timeout

### Wire change: **Yes** (additive only)

- `LoginRequestDto.totpCode: String?` — 2FA 활성 사용자는 password 통과 후
  6자리 코드 동봉으로 같은 endpoint 재호출. 비활성 사용자는 영향 없음.
- 신규 에러 코드: `totp_required` (401), `invalid_totp` (401).

### Added — TOTP (RFC 6238) self-contained 구현

- 신규 `server/auth/Totp.kt` — `generateSecret()` / `otpauthUri()` / `verify()` +
  `Base32` encoder/decoder. JDK `javax.crypto.Mac` (HmacSHA1) 만 사용, 외부 의존성
  없음 (~150 LOC). Google Authenticator / 1Password / Authy 호환.
- `AdminUsers` 스키마: `totp_secret`, `totp_enabled_at` nullable 컬럼 추가.
- `AdminUserRow.totpEnabled` derived flag + `enableTotp(id, secret)` /
  `disableTotp(id)` 메소드.
- `AuthService.login(... totpCode: String? = null)` — totpEnabled 사용자 흐름:
  password 통과 → 코드 null 이면 `totp_required` → 클라이언트가 사용자 코드
  받아 재시도 → 검증 후 토큰 발급. window=1 (±30s drift 보정).

### Added — `/2fa` SSR 페이지

- 신규 `server/admin/TwoFactorRoutes.kt`:
  - GET `/2fa` — 비활성 시 pending secret 표시 (otpauth URI + 4글자씩 끊은
    Base32) + 6자리 코드 검증 폼. 활성 시 활성화 시각 + 비활성화 폼.
  - POST `/2fa/enable` / `/2fa/disable` — CSRF + audit.
- 좌측 nav 메뉴에 "2단계 인증" 추가.
- SSR `/login` 폼이 2단계 진입 시 username/password hidden 보존 + 코드 입력
  필드만 노출.

### Added — Session idle timeout

- `SecuritySection.sessionIdleTimeoutMinutes: Int = 30` (0 = 무제한).
- `AuthPlugin` Bearer authenticate 콜백 + SSR `requireSessionOrRedirect` 양쪽이
  같은 정책 적용 — `device.lastSeenAt` 가 N 분 이전이면 자동 폐기 (`deleteById`)
  + 401 / `/login?err=session_timeout` redirect.
- AuditLogger: `sessionTimeout(userId, deviceId, ip)` + `auth.session.timeout` 액션.

### Config (`server.yml`)

```yaml
security:
  sessionIdleTimeoutMinutes: 30
```

### Android sync 권고

- `LoginRequestDto.totpCode` 가 default null 이라 wire-compatible. 안드로이드
  클라이언트는 후속 minor (v0.7.3) 에서 `totp_required` 응답 시 별도 코드 입력
  화면을 노출하면 됨.

## [0.25.0] - 2026-05-24 — Phase 4 (2/2): `:full` 이미지 publish + compose 가이드

### Added — `docker/compose.full.yml` example

- 슬림 `compose.yml` 과 함께 (`-f compose.yml -f compose.full.yml`) 또는 단독
  override 로 사용. 차이점:
  - `image:` 가 `siamakerlab/vibe-coder-server:full` 로 교체 (~3-4GB).
  - `devices: ["/dev/kvm:/dev/kvm"]` — KVM passthrough.
  - `group_add: ["${KVM_GID:-104}"]` — host kvm 그룹 GID 매칭.
  - `ports: ["6080:6080"]` — noVNC HTTP+WS 게이트웨이 노출.
- `.env` 에 추가할 변수 3개 가이드: `VIBECODER_IMAGE_FULL`, `KVM_GID`,
  `VIBE_NOVNC_PORT`. 모두 default 값 보유.

### Added — `/emulator` 페이지의 `:full` 가이드 카드

기존 "Roadmap" placeholder 카드를 v0.25.0 의 실제 setup 가이드로 교체:
1. compose override + .env 환경 변수 셋업.
2. AVD 생성 + headless 시작 (v0.24.0 lifecycle 폼 재사용).
3. 브라우저 noVNC 접근 + SSH 터널 권장 명령어.

### Docker Hub — `siamakerlab/vibe-coder-server:full` 첫 publish

- amd64-only (KVM 은 host arch 의존이므로 cross-build 무의미).
- 본 cycle 부터 정식 publish. 이전엔 Dockerfile.full scaffold 만 존재했고
  실제 image 는 없었음.

### Security note (LAN-only)

- noVNC 6080 은 **인증 없는 raw VNC 게이트웨이**. LAN 격리 또는 SSH 터널
  (`ssh -L 6080:localhost:6080 user@host`) 가정. 외부 IP 직접 노출 시 vibe-coder
  admin 인증과 무관하게 emulator 화면이 그대로 보임 → security risk.
- 인증된 reverse-proxy 통합 (vibe-coder admin 세션 + iframe 임베드) 은 v0.26+
  scope. Ktor 서버 측 WebSocket proxy 추가 + noVNC 정적 자원 same-origin 제공
  형태로 검토 중 (의존성 부담 vs 보안 이득 trade-off).

### KVM passthrough = 컨테이너 격리 약화

`devices: [/dev/kvm]` 는 host kernel 인터페이스 직접 접근. 신뢰된 admin
단일 사용자 환경 (CLAUDE.md §1) 에서만 사용. 멀티테넌트 환경에선 권장 안 함.

### Wire change: No (Docker + SSR 가이드만)

## [0.24.0] - 2026-05-24 — Phase 4 (1/2): Emulator AVD lifecycle + :full entrypoint

### Added — `EmulatorService` 확장 + SSR lifecycle 폼

- `EmulatorService.createDefaultAvd(name, apiLevel)` — `vibe-default` 자동 생성.
  `avdmanager` 호출 + hardware-profile prompt 자동 "no" 응답 + 화이트리스트 이름
  검증 (shell injection 차단).
- `EmulatorService.launchAvd(name, noWindow=true)` — 백그라운드 emulator 실행.
  `-no-window` `-no-audio` `-no-boot-anim` 옵션 + 부모 종료와 분리되는 detached
  방식. 슬림 이미지에선 KVM 없어 software 모드로 떨어짐 (10× 느림 안내).
- `EmulatorService.stopAvd(serial)` — `adb -s <serial> emu kill` 호출 + 10s timeout.
- `/emulator` 페이지에 AVD lifecycle 카드: "디폴트 AVD 생성" / "headless 시작" /
  실행 중인 emulator 마다 "■ 종료" 버튼. flash banner (ok/err) + CSRF.
- POST 라우트 3개 — `/emulator/avd/create-default`, `/emulator/avd/launch`,
  `/emulator/avd/stop`. 각 audit log 항목 (`emulator.avd.create / .launch / .stop`).

### Added — Docker `Dockerfile.full` entrypoint 완성

- 신규 `docker/emulator-entrypoint.sh` — Xvfb (`:99`, 1080x1920x24) + fluxbox +
  x11vnc (`localhost:5900`) + websockify (`localhost:6080` → noVNC 정적 자원).
  모든 데몬은 internal-only (외부 노출은 v0.25.0 의 reverse-proxy 통해 인증
  boundary 안으로 끌어들임).
- `Dockerfile.full` 의 ENTRYPOINT 가 `emulator-entrypoint.sh` 로 전환. 슬림 본
  `entrypoint.sh` 는 위임 호출로 그대로 재사용 → 코드 중복 없음.
- Dockerfile.full TODO 주석 진행 상태 갱신 (1, 3, 4 완료 / 5, 6 v0.25.0 예정).

### Why two-step rollout

- v0.24.0: 서버 코드 + lifecycle UI + entrypoint = "Mac/Linux 호스트에서
  컨테이너 안 emulator 가 켜진다" 까지. 사용자가 VNC 클라이언트 직접 연결.
- v0.25.0: `/emulator/vnc/` SSR reverse-proxy → 브라우저에서 인증된 admin 만
  noVNC 페이지를 iframe 으로 확인. compose.yml :full 변형 docs.

### Wire change: No (SSR + Docker)

### 호환성

- 슬림 이미지 사용자: 신규 lifecycle 폼은 보이나, SDK 가 설치돼 있어야 동작.
  안 되면 graceful error (안내 메시지). 기존 진단 페이지 동작 영향 없음.
- :full 이미지 (개념적 v0.24.0 prebuild — 본 cycle 에선 Dockerfile 만 push,
  실제 image push 는 v0.25.0 마일스톤에서) 는 entrypoint 가 데몬들을 자동 부팅
  하지만 vibe-coder-server 자체는 슬림과 동일하게 시작.

## [0.23.0] - 2026-05-24 — Phase 3: TestFlight 자동 업로드

### Added — `TestFlightPublishService` + Build Detail 페이지 카드

- 신규 `server/publish/TestFlightPublishService.kt`:
  - `precheck()` — `app-store-connect` MCP 설치/등록 + `ASC_KEY_ID` /
    `ASC_ISSUER_ID` / `ASC_PRIVATE_KEY_FILE(.p8)` 존재 여부 검사.
  - `trigger(projectId, ipaPath, distributionGroups?, releaseNotes?)` —
    `app-store-connect` MCP 로 .ipa 업로드 prompt 발송. compliance 같은
    사용자 결정 단계는 자동 진행 안 함.
- 빌드 상세 페이지 (`/projects/{id}/builds/{buildId}`) 에 **TestFlight
  업로드** 카드 — Play 카드와 달리 빌드 status 무관하게 항상 노출 (vibe-coder
  는 iOS 빌드 안 함 → 빌드 SUCCESS 와 연동 무의미).
- POST 라우트 `/projects/{id}/builds/{buildId}/testflight-upload` — CSRF
  보호, 성공 시 콘솔 페이지로 redirect.
- AuditLogger: `testFlightUploadTriggered` / `testFlightUploadFailed`.

### Why iOS 빌드는 직접 안 함

- macOS + Xcode 필수 — Linux 컨테이너 범위 밖.
- 사용자가 별도 macOS 빌드 농장 (Mac mini / 본인 Mac) 에서 산출한 .ipa 를
  vibe-coder 워크스페이스에 올린 시나리오만 지원 (`scp` / git lfs / shared
  mount). `Roadmap §C.△` 의 fastlane 통합은 후속.

### Wire change: No (SSR + 서버 내부 wiring 만)

## [0.22.0] - 2026-05-24 — Phase 2: Play Console 자동 업로드

### Added — `PlayPublishService` + Build Detail 페이지 카드

빌드 성공 후 산출된 AAB 를 Google Play Console (Internal / Alpha / Beta /
Production) 로 업로드하는 워크플로. **직접 Google API 를 호출하지 않고**
MCP `google-play-publisher` 를 통해 Claude 에게 작업 위임 — vibe-coder 의
일관된 디자인 원칙 (Claude + MCP 가 모든 외부 통신 담당) 유지.

- 신규 `server/publish/PlayPublishService.kt`:
  - `precheck(packageName)` — MCP 설치/등록 상태 + Service Account JSON 존재
    여부 + packageName 일치 여부 검사. `Precheck(ready, mcpStatus, warnings)`.
  - `trigger(projectId, aabPath, track, releaseNotes?)` — 콘솔 세션에 정형
    prompt 전송 (track 화이트리스트 검증 → prompt injection 방지).
- 빌드 상세 페이지 (`/projects/{id}/builds/{buildId}`) 에 **Play Console
  업로드** 카드 — 상태가 SUCCESS 일 때만 노출. 폼: AAB 경로 (기본
  `app/build/outputs/bundle/release/app-release.aab`) + track 선택 +
  Release notes. 사전조건 부족해도 폼은 노출되어 사용자가 prompt 를 우선
  보내본 후 Claude 응답으로 부족한 점 재확인 가능.
- POST 라우트 `/projects/{id}/builds/{buildId}/play-upload` — CSRF 보호,
  성공 시 콘솔 페이지로 redirect (Claude 진행을 라이브로 확인).
- AuditLogger: `playUploadTriggered` / `playUploadFailed` + `Actions.PLAY_UPLOAD`
  (`publish.play.upload`) action constant.

### Why MCP 위임 (직접 API 호출 안 함)

- google-api-services-androidpublisher 의존성 추가 시 이미지 크기 ↑
- OAuth 토큰 / Service Account 키 lifecycle 을 서버 코드가 직접 관리하면
  보안 표면이 넓어짐 — MCP 가 표준 방식으로 처리
- 업로드 진행 / 에러는 자연어로 Claude 가 사용자에게 즉시 설명 → UX 일관성

### Wire change: No

이번 페이즈는 SSR + 서버 내부 wiring 만 추가. ApiPath / DTO 변경 없음.

### Android sync

영향 없음 — Android 클라이언트는 본 기능을 후속 minor (v0.7.x) 에서 빌드
페이지에 같은 폼을 추가하면 됨. 우선 web UI 만 release.

## [0.21.0] - 2026-05-24 — Phase 1: Claude 사용량 시각화 + 임계치 알림

### Wire change: **Yes** (additive only)

`ClaudeStatusDto` 에 두 필드 추가 (둘 다 nullable, default null → backward-compatible):

- `usagePercent: Int?` — `/status` 출력에서 추출된 사용량 0~100. "X% remaining"
  은 자동으로 `100 - X` 로 변환해 "used" 의미로 통일.
- `resetAt: String?` — quota reset 시각 (free-form, CLI 출력 그대로).

### Added — `ClaudeUsageMonitor` 백그라운드 폴링 + 이메일 트리거

- 신규 `server/claude/ClaudeUsageMonitor.kt` — `pollIntervalMinutes` 주기로
  대표 프로젝트 (`__scratch__` 기본) 의 `ClaudeStatusService.snapshot()` 호출,
  `usagePercent` 가 warn (기본 80%) / critical (기본 95%) 임계치를 처음 넘는
  transition 에서만 `EmailNotifier.claudeUsageWarn()` 발송. 재발송은 10분
  cooldown + 임계치 transition 이 다시 일어날 때만.
- 임계치 아래로 내려가면 상태 reset → 다음 cycle 에서 재발송 가능.
- `start()` 는 부팅 직후, `shutdown()` 은 JVM shutdown hook 에 연결.

### Added — `ClaudeStatusService.parseOutput` percent / resetAt 추출

- "quota|remaining|usage" 줄에 `\d+%` 패턴 있으면 `usagePercent` 로 캡쳐.
  "remaining" 단어 동반 시 자동 flip (20% remaining → 80% used).
- "reset" 단어 + "at|in" 동반 줄을 `resetAt` 로 캡쳐.

### Added — 대시보드 Claude 사용량 카드

- `AdminTemplates.dashboardPage` 에 카드 추가 — usagePercent / plan / model /
  reset 시각 + 컬러 바 (80%↑ 노랑, 95%↑ 빨강, 그 외 초록).
- snapshot 미수집 / percent 파싱 실패 시 graceful degrade (안내 메시지).

### Config — `claude.usage` 신규 섹션 (`server.yml`)

```yaml
claude:
  usage:
    enabled: true
    pollIntervalMinutes: 5
    warnThresholdPercent: 80
    criticalThresholdPercent: 95
    scratchOnly: true
```

`scratchOnly=true` (기본) 면 모든 프로젝트가 같은 Claude 계정을 공유한다는
단일-사용자 가정 하에 `__scratch__` 만 폴링해 비용 최소화. `false` 로 두면
프로젝트 전체를 돌며 max usage 채택.

### Android sync 권고

- `vibe-coder-android` 리포 `shared/` 의 `ClaudeStatusDto` 도 두 필드 추가
  필요. UI 노출은 후속 minor (v0.7.3) 에서 콘솔 헤더에 작은 percent badge.

## [0.20.0] - 2026-05-24 — Prompt template wire 정식화

### Wire change: **Yes** (additive only)

서버는 v0.13.0 부터 `/api/prompt-templates` 를 노출했고 응답 모양은 그대로
이지만, 그동안 `shared/` 모듈에 정식 wire DTO 가 없어 안드로이드 client 가
ad-hoc Json 으로 파싱해야 했다. v0.20.0 부터:

- `shared/.../ApiPath.kt`: `const val PROMPT_TEMPLATES = "/api/prompt-templates"`.
- `shared/.../dto/Dtos.kt`: `PromptTemplateDto`, `PromptTemplateListResponseDto`.
- `PromptRoutes` 의 `GET /api/prompt-templates` 가 새 wire DTO 로 응답
  (필드 동일: id / title / category / body / createdAt / updatedAt).

이전 응답 (`PromptListDto`) 과 JSON 모양이 같아 backward-compatible.
브라우저 콘솔 JS (`fetch('/api/prompt-templates')`) 도 그대로 동작.

### Android sync 권고

- `vibe-coder-android` 리포 `shared/` 도 동일 entry 동기 (v0.7.2 에서 반영).
- 신규 UI: `QuickActionSheet` 안에 `PromptTemplatesSection` 통합 — 카테고리
  탭 + 본문 칩 row → 두 번 탭으로 입력란에 paste.

### 호환성

- 신규 wire 만 추가, 기존 wire 제거 / 변경 없음.
- 구버전 (v0.7.1 이하) 안드로이드 클라이언트는 영향 없음 (해당 endpoint 를
  호출하지 않음). 신버전 (v0.7.2+) 은 새 DTO 로 안전하게 파싱.

## [0.19.0] - 2026-05-24

Phase 5 — Android Emulator (scaffolding + 진단 + ADB 통합).

### Added — `/emulator` 페이지 + `EmulatorService`

- `EmulatorService.diagnose()` — KVM (`/dev/kvm`), Android SDK, emulator binary,
  adb binary, 설치된 AVD 목록, 실행 중인 device 목록 검출. shell injection
  안전 (List<String> ProcessBuilder).
- `EmulatorService.installApk(deviceSerial, apkPath)` — 실행 중인 emulator 에
  ADB 로 APK 설치. 60초 timeout.
- `/emulator` SSR 페이지 — 진단 + 권장 사항 + AVD 생성 / emulator launch
  수동 가이드 (docker exec 명령 inline).
- nav 메뉴 "Emulator" 추가.

### Why scaffolding only (not full automation)

- KVM passthrough 는 compose 의 `devices: [/dev/kvm:/dev/kvm]` + 호스트 kvm
  그룹 설정 필요. 1인 LAN 도구라 환경마다 가용성 다름.
- 풀 자동화 + noVNC 미러는 base image 부피 (qemu/x11/websockify) 대폭 증가
  유발 — 별도 image variant (`siamakerlab/vibe-coder-server:full`) 로 분리
  예정 (v0.20+).
- 본 cycle 은 **진단 + ADB 통합** 만 — 운영자가 수동으로 emulator 띄운 후
  본 페이지에서 device 가 인식되면 그 이후엔 콘솔에서 Claude 가 ADB 로 APK
  설치 + UI 자동화 가능 (이미 동작).

### Wire change

없음. SSR only — JSON API 는 풀 자동화 cycle 에서 추가.

## [0.18.0] - 2026-05-24

Phase 4 — Git push + 프로젝트 templating.

### Added — GitWriter + commit/push UI

- `GitWriter.commitAndPush` — `git add` (전체 또는 tracked only) + commit
  (author env 주입) + optional push origin/branch. push 실패해도 commit 유지.
  shell injection 안전 (List<String> ProcessBuilder).
- `POST /api/projects/{id}/git/commit` — JSON API (Bearer 인증).
  `GitCommitRequestDto(message, push, onlyTracked)` →
  `GitCommitResponseDto(committed, pushed, branch, sha, log)`.
- `/projects/{id}/git` SSR — 기존 read-only view 에 commit/push 폼 추가
  (csrf + push checkbox + only-tracked checkbox).
- AuditLogger.gitCommit (git.commit action).

### Added — 프로젝트 시작 템플릿

- `ProjectTemplates.all` — 6 종: empty / compose-basic / compose-mvvm-hilt /
  compose-mvvm-room / wear-os / android-tv.
- `RegisterProjectRequestDto.templateId` 신규 (additive, default null).
- 신규 프로젝트 폼 dropdown 으로 노출.
- 등록 직후 첫 console 진입 시 starter prompt 가 textarea 에 자동 입력
  (한 번 소비 후 제거). 사용자는 Enter 만 누르면 Claude 가 scaffolding 시작.

### Wire change: Yes

- `ApiPath.gitCommit(projectId)` 추가.
- `RegisterProjectRequestDto.templateId` 신규 nullable 필드.
- vibe-coder-android `shared/` 동기 필요 (별도 후속 commit).

## [0.17.0] - 2026-05-24

Phase 3 — SMTP 이메일 알림 + Android Client Guide 의 v0.13.0+ 통합 가이드.

### Added — SMTP 알림 (`EmailNotifier`)

- `EmailSection` (host / port / user / password / passwordFile / from / to /
  tls / claudeUsageWarnPercent / diskUsageWarnPercent).
  env override 모두 가능 (VIBECODER_SMTP_*).
- `EmailNotifier` — Jakarta Mail + Angus implementation. send / sendNow.
  비활성 시 no-op. 발송 실패는 server log 로만.
- `/settings/email` SSR (read-only viewer + 테스트 메일 전송 버튼). nav 메뉴 추가.
- `BuildService` 가 빌드 완료 시 (SUCCESS / FAILED) 자동 알림.

### Docs — Android Client Guide 의 v0.13.0+ 통합 가이드

사용자 요청. 다음 4가지를 Android client 에 통합하는 방법:
- Turn cancel — Retrofit interface + UI rule (■ stop 버튼, console_done /
  process_crashed 등에서 자동 hide).
- Prompt templates — `/api/prompt-templates` 사용 + QuickActionSheet UX.
  CRUD UI 는 본 client v1 scope 외 (browser 에서 관리).
- General Chat — synthetic projectId `__scratch__` 로 기존 console 화면 재사용.
  BottomNav 5탭 또는 Home entry 두 옵션.
- File tree/viewer — 본 cycle 은 SSR 만. JSON API 는 다음 minor 에 예정 —
  Android 통합도 그 때.

### Wire change

없음. REST API 추가 (`/api/prompt-templates` 는 v0.13.0 부터 존재).

## [0.16.0] - 2026-05-24

Phase 2 — Prompt/Response 영구 히스토리.

### Added — conversation_turns 테이블 + `/projects/{id}/history` 페이지

- 새 PG 테이블 `conversation_turns` (id / projectId / sessionId / turnIdx /
  ts / role / content / toolName / toolUseId / tokensIn-Out / raw). Indexes
  on (projectId, ts), (projectId, sessionId, turnIdx), (toolUseId).
- `ConversationTurnRepository` — insert + filter (session / role / tool /
  ts range / content LIKE) + pagination + cascade delete.
- `ConversationHistoryService` (fire-and-forget) — ClaudeSessionManager 가
  user prompt / ClaudeEvent (assistant / tool_use / tool_result / system /
  error) 발생 시 적재. AssistantMessage 의 isPartial chunks 는 적재 안 함
  (turn 단위 final 만).
- `/projects/{id}/history` SSR — 필터 (session / role / tool / ts range /
  LIKE 검색) + 100/page pagination. 프로젝트 상세 / 콘솔 페이지에 링크.
- `/chat/history` — General Chat (`__scratch__`) 도 동일 영구화 + 전용 페이지.
- `ProjectService.delete` cascade 에 conversation_turns 추가.

### Known limits

- 본문 검색은 PostgreSQL `LIKE %query%` — 다음 cycle 에서 tsvector + GIN
  으로 교체 예정.
- 첫 user prompt 는 `sessionId=null` 로 적재 (SessionStarted 가 도착하기
  전이라). 후속 turn 부터 정상 session 묶임.
- Rotation 미구현. 1인 LAN 도구에서 수년 누적 후 수동 정리 권장 (Audit-Log
  Wiki 의 절차 참고).

## [0.15.0] - 2026-05-24

Phase 1 (계획상 ☆ 항목 묶음) — Audit log + 파일 신택스 하이라이트 + T1 wire 동기.

### Added — Audit log (B.☆)

- 신규 `audit_log` 테이블 (id / ts / userId / deviceId / ip / action /
  resourceType / resourceId / result / detail). ts / action 인덱스.
- `AuditLogRepository` — Filter (action/result/userId/from-to ts) + pagination.
- `AuditLogger` facade — 도메인 별 메서드 + kotlinx.serialization 기반 안전 JSON detail
  + 쓰기 실패가 요청 흐름을 망가뜨리지 않는 safe wrapper.
- `/audit` SSR 페이지 — 필터/검색 + 100/page pagination. nav 메뉴 "감사 로그" 추가.
- 통합 지점 (16 actions): auth.login (success/failure), auth.setup,
  auth.logout, auth.password.change, device.revoke, project.create, project.delete,
  build.enqueue, build.cancel, console.new, console.cancel (REST), mcp.install,
  mcp.unregister, settings.update, git.token.register, git.token.delete.

### Added — 파일 신택스 하이라이트 (A — T3 후속)

- `highlight.min.js` (125KB) + `github-dark` 테마 CSS 를 `/static/admin/` 에 번들
  (외부 CDN 정책 미위반).
- `fileViewPage` 가 View 모드 (read-only highlighted `<pre>`) / Edit 모드 (textarea)
  토글. 기본 View. Edit 토글 시 textarea focus + Ctrl+S 저장 + Tab indent.
- 지원 언어: kotlin / java / xml / json / yaml / markdown / properties / bash
  (ProjectFileBrowser mime guess 매핑). 200K 자 초과 파일은 highlight skip
  (브라우저 freeze 방지).

### Wire change: Yes (서버 측은 이미 v0.13.0 에 추가 — 안드로이드 동기 항목)

- v0.13.0 에서 추가한 `ApiPath.claudeConsoleCancel` 가 `vibe-coder-android`
  의 `shared/` 에도 동기됨 (v0.6.11). Android 사용자가 turn cancel 기능을
  쓰려면 v0.6.11 이상 클라이언트 필요. 구버전 클라이언트는 기능 미사용
  상태로 정상 동작.

### Changed — nav 메뉴

- "감사 로그" 추가. 순서: 대시보드 / 프로젝트 / Chat / 프롬프트 / 빌드환경 /
  설정 / 디바이스 / **감사 로그** / 비밀번호.

## [0.14.1] - 2026-05-24

### Changed — ClaudeMdTemplate 에 빌드 도구 경로 명시

**증상**: 사용자가 `/env-setup` 에서 Gradle 최신 버전을 설치한 뒤 신규 프로젝트를
만들면, Claude 가 작업 시 프로젝트의 `gradle-wrapper.properties` 가 가리키는 다른 (구)
버전을 wrapper 가 자동 다운로드. 디스크 / 빌드 시간 / API 토큰 낭비.

**Root cause**: 신규 프로젝트의 `CLAUDE.md` 에는 빌드 도구가 어디에 있는지 정보가
없어서, Claude 가 wrapper 의 distributionUrl 을 그대로 신뢰하고 새 다운로드를 트리거.

**해결**: `ClaudeMdTemplate.kt` 에 다음 섹션 추가:

- `## Installed Build Tools (USE THESE — DO NOT RE-DOWNLOAD)` — 컨테이너 안에
  이미 설치된 도구들의 경로 표.
  - Gradle: `/home/vibe/.local/gradle/` (PATH 의 `gradle`)
  - Android SDK: `$ANDROID_HOME` (`/opt/android-sdk`)
  - JDK 17 / Node 20 / Claude CLI: 이미지 번들
  - MCP packages: `/home/vibe/.local/`
- `### Gradle wrapper alignment policy` — wrapper 버전이 설치된 Gradle 과 다르면
  wrapper 를 설치 버전에 맞추라는 명시적 지시.
- `### When a wrapper is missing` — `gradle wrapper --gradle-version $(gradle --version ...)` 로
  설치 버전 기반 wrapper 생성.
- `### Cache reuse` — `~/.gradle/caches/` / SDK build-tools 캐시 정리 금지.

### 영향 범위

- **신규 프로젝트만**: `ProjectScaffolder.ensureClaudeFiles` 는 `notExists()` 가드.
  기존 프로젝트의 `CLAUDE.md` 는 보존됨. 기존 프로젝트에 적용하려면 사용자가
  파일을 수동으로 갱신하거나 삭제 후 재생성 (콘솔에서 Claude 에게 부탁).
- Wire change: 없음. 운영 정책 변경 (template) — CLAUDE.md §8.D 트리거.

## [0.14.0] - 2026-05-24

### Changed (Breaking) — 영구 저장소 SQLite → PostgreSQL

**Why**: SQLite single-writer 제약이 future-proof 하지 않다는 판단. Roadmap §9.F.☆ #1
"Prompt/Response 영구 히스토리" 가 들어오면 콘솔 stream 적재 + 검색이 동시에 일어남.
JSONB column 으로 tool_use input/output 의 가변 구조도 깔끔히 저장하려면 PG 가 적합.

**변경 사항**:

1. **JDBC driver**: `org.xerial:sqlite-jdbc` → `org.postgresql:postgresql` (42.7.4).
2. **Database.kt**: `jdbc:sqlite:...` → `jdbc:postgresql://host:port/db`. Hikari pool
   1 → 10 (PG 는 multi-connection 가능). Startup 시 30회 (60초) 재시도 — postgres
   컨테이너 ready 까지 대기.
3. **ServerConfig.DatabaseSection 신규**: host/port/name/user/password/passwordFile/maxPoolSize/sslMode.
   env override: `VIBECODER_DB_HOST/PORT/NAME/USER/PASSWORD/PASSWORD_FILE/MAX_POOL/SSLMODE`.
4. **server.yml** 에 `database:` 섹션 추가. 기본 host=postgres / port=5432 / name=vibecoder.
   비밀번호는 절대 디스크에 평문으로 두지 말 것 — env 또는 Docker secret 사용 권장.

### Changed (Breaking) — docker compose 에 postgres 컨테이너 추가

```yaml
services:
  postgres:
    image: postgres:17-alpine
    environment:
      POSTGRES_PASSWORD: ${VIBECODER_DB_PASSWORD:?...}
    volumes:
      - ${VIBE_DATA_ROOT:-./vibe-coder-data}/postgres:/var/lib/postgresql/data
    healthcheck: ...

  vibe-coder-server:
    depends_on:
      postgres: { condition: service_healthy }
    environment:
      VIBECODER_DB_HOST: postgres
      VIBECODER_DB_PASSWORD: ${VIBECODER_DB_PASSWORD:?...}
```

데이터 디렉토리 구조 갱신:
```
vibe-coder-data/
  ├── workspace/
  ├── postgres/         ← v0.14.0 신규 (PG 데이터)
  ├── server/           ← 이전엔 SQLite 도 여기. v0.14.0 부터 로그/빌드 메타만
  ├── dev-tools/
  └── claude/
```

`tar czf backup.tar.gz vibe-coder-data/` 한 줄 백업 그대로 유효
(단, 일관성을 위해 `docker compose stop postgres` 후 백업 권장).

### Upgrade procedure — Fresh start

기존 v0.13.x 사용자는 **fresh start**: SQLite 데이터는 보존하지 않고 admin / 프로젝트 /
디바이스를 새로 등록. 워크스페이스 파일 (`vibe-coder-data/workspace/<projectId>/`) 은
디스크에 그대로 남아 있어 같은 ID 로 다시 등록하면 기존 소스를 이어서 사용 가능.

```bash
docker compose -f docker/compose.yml --env-file docker/.env down

# 기존 SQLite 파일 보관 (선택)
mv vibe-coder-data/server/.vibecoder/vibecoder.db ./vibecoder-v0.13-backup.sqlite 2>/dev/null || true

# .env 갱신 — VIBECODER_DB_PASSWORD 반드시 강력한 값으로
cp docker/.env.example docker/.env
${EDITOR:-nano} docker/.env

# postgres + server 같이 부팅
docker compose -f docker/compose.yml --env-file docker/.env up -d
# 첫 부팅 후 http://<IP>:17880/setup 으로 admin 재생성 + 프로젝트 재등록
```

`.env` 에 **`VIBECODER_DB_PASSWORD`** 반드시 강력한 값으로 설정 (compose 가 빈 값을 거부).

### Wire change

**없음.** REST API / WebSocket frame 무변경. 영향: 운영 (docker compose) + 내부 DB layer 만.
Android `shared/` 동기 불필요.

### Migration trigger (CLAUDE.md §8 분류)

- **C** (운영 정책 변경) — Dockerfile, compose.yml, .env.example 변경 → README / docker README /
  HUB README / 마이그레이션 문서 갱신 완료.

## [0.12.3] - 2026-05-23

### Added — 빌드환경 페이지에 Gradle 카드 + 자동 wrapper bootstrap

**증상**: 사용자가 신규 프로젝트에 Android 앱 생성 → `gradlew assembleDebug`
시도 → "이 환경에 Gradle Wrapper(gradlew)와 시스템 gradle 모두 없어 빌드
불가" 응답. 모든 신규 프로젝트의 첫 빌드가 막혔던 문제.

**Root cause**: Claude 가 생성하는 신규 프로젝트는 `build.gradle.kts` 등은
있지만 wrapper 파일 (`gradlew`, `gradle/wrapper/*.jar` 등) 은 생성 안 함.
컨테이너에 시스템 gradle 도 없어 wrapper bootstrap 불가.

**해결**: vibe-coder 의 "이미지 슬림 + 볼륨 다운로드" 패턴 통일.

1. **빌드환경 페이지에 Gradle 카드 추가** (`SetupComponent.GRADLE`).
   - "설치 (최신 stable)" / "최신 버전으로 업데이트" / "재설치" 버튼.
   - Probe 가 services.gradle.org/versions/current 조회 → 설치 버전과
     최신 비교 → UI 에 "현재 X.Y → 최신 A.B 사용가능" 표시 (업데이트 권유).
   - 설치 위치: `/home/vibe/.local/gradle/` (이미 v0.7.0 bind mount).
     `/home/vibe/.local/bin/gradle` symlink — PATH 자동 등록됨.

2. **vibe-doctor 의 `gradle` 신규 subcommand** (`docker/doctor/lib/gradle.sh`):
   - `vibe-doctor gradle` → 최신 stable 자동 조회 + 다운로드.
   - `vibe-doctor gradle 8.10.2` → 특정 버전.
   - jq 가용 시 jq, 아니면 grep fallback.
   - 같은 버전 이미 설치돼 있으면 skip (멱등).

3. **GradleBuilder 의 자동 wrapper bootstrap**: build 직전 `gradlew` 없으면
   system gradle 로 `gradle wrapper --gradle-version 8.7` 자동 실행 →
   wrapper 생성 → 그 후 정상 `./gradlew assembleDebug`. system gradle 부재면
   명확한 오류 메시지 + 빌드환경 페이지 안내.

### Wire change

**없음.** SetupComponent enum + UI + vibe-doctor 스크립트만. ApiPath / DTO
무변경 (Android 의 환경 진단 API 는 같은 list 를 그대로 반환 — 새 entry
1개 추가).

### 사용자 영향

- 업그레이드 후: 빌드환경 페이지 → Gradle 카드 → "설치 (최신 stable)" 클릭
  (~130 MB, 1~2분).
- 그 다음부터 신규 프로젝트의 첫 빌드 시 wrapper 자동 부트스트랩 → 정상.
- 기존 프로젝트는 wrapper 가 이미 있으면 그대로, 없으면 첫 빌드에서 자동
  생성.

### 배포

- `siamakerlab/vibe-coder-server:0.12.3` multi-arch push 예정.

## [0.12.2] - 2026-05-23

### Fixed — 콘솔에서 모든 파일 쓰기/AskUserQuestion 거부되어 작업 불가

**증상**: 사용자가 콘솔에서 "Hello World Android 앱 만들어줘" 같은 일반 요청
시도 → Claude 가 `settings.gradle.kts`, `build.gradle.kts` 작성 시도 → 매
파일마다 "Claude requested permissions to write to ... but you haven't
granted it yet" 응답 → 어떤 파일도 못 만들고 막힘. 추가로 AskUserQuestion
도 "Answer questions?" 만 표시되고 응답 채널이 없어 hang.

**Root cause**: v0.7.0 이전에 생성된 프로젝트 (예: `/workspace/test/`) 에
`.claude/settings.json` 이 없어서 `permissions.defaultMode: bypassPermissions`
가 적용되지 못함 → Claude Code 의 default `ask` 모드 → vibe-coder 비인터랙티브
콘솔은 권한 prompt 응답 채널 없음 → 모든 write 거부 + AskUserQuestion hang.

v0.7.0 의 `ProjectService.register` 는 신규 프로젝트에만 `.claude/settings.json`
을 생성. 기존 프로젝트는 backfill 안 됨.

**수정 — 이중 안전망**:

1. **spawn args 에 `--dangerously-skip-permissions` 명시** (`ClaudeSessionManager.kt:166`).
   `.claude/settings.json` 유무와 무관하게 권한 prompt 차단 — robust.
   `ClaudeStatusService` 의 `--print /status` 호출도 동일 추가.
2. **인터랙티브 위젯 명시 차단**: `--disallowedTools` 로
   `AskUserQuestion`, `EnterPlanMode`, `ExitPlanMode`, `NotebookEdit` 거부.
   모델이 호출 시도하면 즉시 실패 → 응답 끝에 옵션 나열 등 비인터랙티브
   경로로 자동 분기.
3. **매 spawn 직전 `.claude/settings.json` + `CLAUDE.md` 자동 backfill**
   (`ProjectScaffolder.ensureClaudeFiles`, 신규 파일). 기존 파일 있으면
   noop — 사용자 customize 보존. 신규 프로젝트는 register 가 만들고, 기존
   프로젝트는 spawn 시 보충. backfill 실패해도 prompt 차단 사유는 아님 (log
   만, args 의 플래그가 1차 안전망).

### Wire change

**없음.** server-side spawn args / 디스크 backfill 만. ApiPath / DTO 무변경.

### 사용자 영향

업그레이드 직후, 기존 프로젝트의 첫 콘솔 호출에서 자동으로
`.claude/settings.json` + `CLAUDE.md` 생성됨 (없는 경우만). 그 다음부터
모든 write/edit 가 자동 승인됨.

### 배포

- `siamakerlab/vibe-coder-server:0.12.2` multi-arch push 예정.
- 기존 사용자: `docker compose pull && up -d --force-recreate`. 별도 마이그레이션 불요.

## [0.12.1] - 2026-05-23

### Added — MCP 카탈로그 `comingSoon` 라벨

Phase 2 (Device Code OAuth wrapper) 진행 보류 결정에 따라, vibe-coder 의
비인터랙티브 환경에서 사실상 동작 불가한 MCP 를 카탈로그에 노출하되 명확히
표시. 사용자가 헛수고로 설치 시도하는 일 방지.

**McpCatalog.McpEntry**:
- 새 필드 `comingSoon: Boolean = false`.
- 카탈로그에 노출은 하되 설치 비활성 + UI 카드 흐림(opacity 0.55) + "⏳ 준비중" 배지.

**영향 항목 (1개)**:
- `google-drive` — OAuth client.json 업로드 후 첫 호출에서 브라우저 OAuth 콜백
  필수. 키 파일만 받아서는 토큰 교환 불가. 다른 모든 MCP (Slack/Notion/Linear
  Bot token, GitHub PAT, Service Account JSON 등) 는 v0.11.0 의 토큰/파일
  업로드로 충분.

**서버 검증** (`McpService.spawnBatch`):
- `comingSoon=true` MCP 가 install 요청에 포함되면 400 `coming_soon` 응답.
  웹 UI 우회 시도 차단 (Android wire 도 동일).

**UI** (`McpTemplates.renderEntry`):
- checkbox `disabled` + title 툴팁 ("브라우저 OAuth 콜백이 필수라 현재 환경
  에서 미지원").
- "준비중" 배지 — Trust 배지 옆에 표시.
- configFields 폼 숨김 (어차피 등록 불가).

### Wire change

**예** — `McpEntryDto` 에 optional `comingSoon: Boolean = false` 추가.
구버전 클라이언트는 무시하지만 catalog 응답에 새 필드 포함.

### 배포

- `siamakerlab/vibe-coder-server:0.12.1` multi-arch push 예정.

### Phase 2 (Device Code OAuth) 결정 결과

운영자가 결정한 방향: **PAT 직접 입력으로 충분**. Device Flow 구현 보류.
브라우저 OAuth 콜백이 필수인 MCP 는 위 `comingSoon` 라벨로 일관 처리.

## [0.12.0] - 2026-05-23

### Added — CORS 정책 설정 (env override + 읽기 전용 UI)

이전엔 `anyHost()` 하드코딩이라 외부 origin 노출 환경에서 CSRF 위험이 있었음.
이제 server.yml 또는 docker compose env 로 정밀 제어 가능.

**Config** (`ServerConfig.kt`):
- 새 섹션 `cors: CorsSection(allowedHosts, allowCredentials)`.
- 기본값 `["*"]` — LAN 격리 환경 (anyHost). 외부 노출 시엔 신뢰 origin 만 명시.
- Wildcard subdomain 패턴 (`*.example.com`) 지원.
- 스킴 명시 가능: `https://x.com` (https 만) / `x.com` (http+https 둘 다).

**Env override** (`ConfigLoader.applyEnvironmentOverrides`):
- `VIBECODER_CORS_ALLOWED_HOSTS` — 콤마 구분. server.yml 보다 우선.
- `VIBECODER_CORS_ALLOW_CREDENTIALS` — `true`/`false`.

**Module** (`Module.kt`):
- `install(CORS)` 가 config 참조 — `*` 포함 시 `anyHost()`, 아니면 명시 host
  list 처리. 신규 helper `parseCorsHostEntry` 가 URL 패턴 파싱.

**Compose / .env**:
- `compose.yml` environment 에 `VIBECODER_CORS_ALLOWED_HOSTS`/`VIBECODER_CORS_ALLOW_CREDENTIALS`.
- `.env.example` 의 CORS 섹션 + 보안 안내.

**UI** (신규 `/settings/cors`):
- 현재 적용된 정책 + allowedHosts 표 + describeHost 매핑 표시.
- 보안 경고 (anyHost 의 CSRF 위험, allowCredentials + anyHost 조합 금지).
- **편집은 의도적으로 제외** — env / server.yml 만 가능. UI 우발 변경 방지.
- 변경 절차 step-by-step + 검증 curl 예문.

### Added — 프로젝트 아이콘 (admin SSR header + favicon)

`vibe-coder-icon.png` 가 `server/src/main/resources/static/admin/icon.png`
으로 패키징되어 `/static/icon.png` 로 자동 노출.

- `AdminTemplates.shell()` 의 head 에 favicon `<link rel="icon">` 추가.
- nav 좌상단 brand 옆에 32x32 라운드(50%) 아이콘 배치.
- 모든 admin SSR 페이지에서 자동 표시.

### Wire change

**없음.** CORS 정책은 server-side 동작 변경, UI 는 admin SSR 전용.
ApiPath / DTO / WsFrame 무변경.

### 배포

- `siamakerlab/vibe-coder-server:0.12.0` multi-arch push 예정.
- 기존 사용자: `docker compose pull && up -d --force-recreate`. CORS 정책은
  기본 `*` 유지되므로 동작 변화 없음 (envar 설정 시에만 변경 적용).

### v0.12.0 Phase 2 (예정) — Device Code OAuth wrapper

사용자가 옵션 2 진행 요청 — GitHub OAuth Device Flow 로 PAT 발급 간소화.
구현 복잡도 (OAuth App 등록 필요 + provider 별 endpoint 차이) 때문에
별도 minor 로 분리 예정 (v0.13.0).

## [0.11.0] - 2026-05-23

### Added — MCP secret 파일 업로드 (Service Account JSON / Apple .p8 등)

기존 v0.8.0 MCP 카탈로그의 한계 해소 — Play Console / App Store Connect /
Firebase / Google Drive 같이 **OAuth 토큰이 아니라 JSON/PEM 키 파일**로
인증하는 MCP 들이 모바일·웹 UI 만으로 등록 가능해짐.

**카탈로그 변경** (`McpCatalog.ConfigField`):
- 새 필드 `isFile: Boolean` + `acceptMime: String?`.
- 영향 4개 MCP 의 path 필드를 file upload 로 전환:
  - `google-play-publisher` → `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` (.json)
  - `app-store-connect` → `ASC_PRIVATE_KEY_FILE` (.p8) — 이전 `ASC_PRIVATE_KEY_PATH` 에서 이름 변경
  - `firebase` → `GOOGLE_APPLICATION_CREDENTIALS` (.json)
  - `google-drive` → `GDRIVE_CREDENTIALS_PATH` (.json)

**서버** (`McpService.uploadConfigFile`):
- 안전 경로: `${CLAUDE_CONFIG_DIR}/mcp-secrets/<mcpId>-<fieldKey><ext>`.
- 디렉토리 0700, 파일 0600 (Posix).
- 최대 128 KB (일반 Service Account / .p8 키는 수 KB 이내 — 비정상 크기 거부).
- 파일명 sanitize (영숫자+. _ - 만 허용 — path traversal 방지).
- 확장자는 원본 파일명 우선, fallback 으로 acceptMime 첫 항목.
- atomic move 로 덮어쓰기 (동시 업로드 안전).

**라우트** (SSR + JSON API 모두):
- `POST /env-setup/mcp/{mcpId}/file/{fieldKey}` (SSR) — UI 의 file input
  onChange 가 ajax 호출. 응답 `{path}` 를 hidden input 에 저장 → 일반 install
  POST 의 configValues 에 포함.
- `POST /api/env-setup/mcp/{mcpId}/file/{fieldKey}` (Android wire, Bearer
  auth) — 동일 multipart 형식.
- 신규 헬퍼: `ApiPath.mcpUploadFile(mcpId, fieldKey)`.
- 신규 DTO: `McpFileUploadResponseDto(path)`.

**UI** (`McpTemplates`):
- file input 분기 (`<input type="file" accept=".json,...">`).
- onChange 즉시 ajax 업로드 → 진행 상태 + path 표시.
- 업로드 미완료 상태로 install 제출 시 차단 + alert.
- 기존 등록된 path 가 있으면 표시 + 새 파일로 교체 가능.

### Wire change

**예** — `McpConfigFieldDto` 에 `isFile/acceptMime` optional 필드 추가
(default false/null 이라 구버전 클라이언트 호환). 새 endpoint
`mcpUploadFile`. 새 DTO `McpFileUploadResponseDto`. Android 클라이언트는
`shared/` 동기 + file input 흐름 구현 시 사용 가능.

### 배포

- `siamakerlab/vibe-coder-server:0.11.0` multi-arch push 예정.

## [0.10.2] - 2026-05-23

### Changed — UI 의 버전 라벨 제거 (사용자 노이즈 정리)

빌드환경 / 프로젝트 / Claude 로그인 / Git 통합 / MCP 페이지 등 곳곳에
산재해있던 "v0.x.x 부터 / v0.x.x 신규 / v0.x.x+" 같은 메인테이너 관점
라벨을 일괄 제거. 사용자는 현재 버전에서 제공되는 기능만 보면 되며,
어느 버전부터 추가됐는지는 CHANGELOG 의 관심사.

제거된 6곳:

| 파일 | 위치 | Before | After |
|---|---|---|---|
| `GitIntegrationsTemplates.kt:79` | 페이지 header | `Git 통합 v0.9.0` | `Git 통합` |
| `EnvSetupTemplates.kt:149` | MCP 카드 hint | `v0.8.0+: 체크박스 …` | `체크박스 …` |
| `EnvSetupTemplates.kt:178` | 옵션 0 강조 배너 | `… 한 번에 로그인 (v0.7.0 신규)` | `… 한 번에 로그인` |
| `EnvSetupTemplates.kt:314` | Claude 로그인 header | `Claude 웹 로그인 v0.7.0 옵션 A · 반자동 OAuth` | `Claude 웹 로그인 반자동 OAuth` |
| `WebProjectTemplates.kt:169` | 새 프로젝트 폼 legend | `소스 (v0.9.0+)` | `소스` |
| `McpTemplates.kt:121` | "직접 설치" 안내 hint | `v0.7.0+ 영구 보존` | `영구 보존` |

코드 주석의 `// v0.x.x — …` 패턴은 메인테이너용이라 유지.

### Wire change

**없음.** SSR HTML 텍스트만 변경. server.yml 0.10.1 → 0.10.2 (UI patch).

### 배포

- `siamakerlab/vibe-coder-server:0.10.2` multi-arch push 예정.

## [0.10.1] - 2026-05-23

### Fixed — Claude 웹 로그인 입력창 포커스 손실 + paste 텍스트 소실

**증상**: `/env-setup/claude-login` 의 AWAITING_CODE 단계에서 사용자가
authorization code 를 paste 한 직후, 페이지가 자동 reload 되면서 입력값이
사라지고 포커스가 풀리는 현상.

**Root cause** (`EnvSetupTemplates.kt:325-353`):
- JS 폴링이 1초마다 `/env-setup/claude-login/status.json` 호출.
- 어떤 이유로든 state 가 직전 값과 다르면 무조건 `window.location.reload()`.
- AWAITING_CODE 에서도 폴링이 계속 돌아, child process 의 stdout 미세 변동
  / watchProcess 의 race 로 state 가 흔들리면 사용자 입력 도중 reload 발생.
- input 의 값 보존 / autofocus 없어서 reload 후 처음부터 다시 paste 필요.

**수정**:

1. **AWAITING_CODE 에서 폴링 disable** — 진행상태(STARTING/VERIFYING)
   에서만 폴링. AWAITING_CODE 에선 사용자 action (제출/취소) 만이 state
   를 진행시킬 수 있어 폴링 불요.
2. **input 값 sessionStorage 자동 백업/복원** (`STORAGE_KEY=claude_login_code_buf`):
   - 페이지 로드 시 input 비어있으면 sessionStorage 에서 복원.
   - `input` + `paste` 이벤트마다 즉시 sessionStorage 에 저장.
   - 폼 submit 직전 (제출/취소 양쪽) sessionStorage clear — 다음 세션에
     stale 코드 채움 방지.
3. **input autofocus** — HTML `autofocus` 속성 + JS `setTimeout focus`
   이중 보장 (일부 환경에서 HTML autofocus 가 안 먹는 케이스 대비).
4. **사용자 안내문 추가** — "입력 중에는 페이지가 자동 갱신되지 않으니
   제출 버튼을 직접 누르세요" 힌트 표시.

**Trade-off**: AWAITING_CODE 에서 폴링이 멈춰 있어, child process 가 그
사이 죽어도 사용자는 즉시 알 수 없음. submit 시 `wrong_state` (409)
에러로 안내 (기존 errorBlurb 표시 흐름 그대로).

### Verification

`./gradlew clean :server:installDist` 통과. 빌드환경 페이지 → "옵션 0 웹
로그인" → "로그인 시작" → AWAITING_CODE 화면에서 input 에 long string
paste → 5초 대기 → 페이지 자동 reload 없음 + 입력값 그대로 → 제출 시 정상
전송.

### Wire change

**없음.** SSR HTML/JS 수정만. ApiPath / DTO / WsFrame 무변경.

### 배포

- `siamakerlab/vibe-coder-server:0.10.1` multi-arch push 예정.
- 사용자 영향: `docker compose pull && up -d --force-recreate` 후 즉시 fix
  적용. AWAITING_CODE 상태에서 paste 한 코드가 영구 안정.

## [0.10.0] - 2026-05-23

대규모 wire 마일스톤 — v0.7.0~0.9.0 에서 admin SSR 전용으로 추가됐던 6개
기능군이 모두 JSON API 로 이중 노출됨. vibe-coder-android 클라이언트가 같은
기능을 모바일에서 호출 가능. Bearer token 인증 보호.

### Added — v0.9.0: Git clone (지난 minor 통합 보고)

신규 프로젝트 등록 시 git URL 에서 clone 가능. public + private (HTTPS PAT
+ SSH key) 모두 지원.

- `RegisterProjectRequestDto` 에 `sourceType / cloneUrl / cloneBranch` optional
  필드 추가 (default `empty` 라 구버전 클라이언트 호환). Wire 호환 보장.
- `GitCloneService` — `git clone --progress` 자식 spawn + URL 검증 +
  GIT_TERMINAL_PROMPT=0 (stdin hang 차단) + `accept-new` host key + 부분 파일
  자동 정리.
- `GitCredentialStore` — provider 별 PAT 보관 (`~/.config/vibe-coder/git-tokens.json`)
  + git CLI 표준 `~/.git-credentials` 동기 (`credential.helper=store`).
- SSH 키 자동 생성 (ed25519) — 사용자가 공개키를 GitHub/GitLab/Gitea/Bitbucket 에
  등록하면 SSH URL clone 즉시 동작.
- 새 설정 페이지 `/settings/git-integrations` — 토큰 목록(마스킹) +
  SSH 공개키 표시 + 등록/삭제 폼.
- 신규 프로젝트 폼 (`/projects`) 에 sourceType 라디오 + clone URL/branch 입력.

### Added — v0.10.0 핵심: API 이중 노출 (Android wire)

v0.7~0.9 의 신규 기능들이 admin SSR 전용이라 vibe-coder-android 가 호출할
경로가 없던 문제 해결. **6개 도메인의 19개 엔드포인트 추가**:

`shared/.../ApiPath.kt` (29줄 신규):
- `ENV_SETUP_COMPONENTS` (GET), `ENV_SETUP_INSTALL_ALL` (POST),
  `envSetupInstall(id)` (POST), `wsEnvSetupLogs(taskId)` (WS)
- `CLAUDE_AUTH_UPLOAD` (POST multipart), `CLAUDE_AUTH_API_KEY` (POST),
  `CLAUDE_AUTH_API_KEY_DELETE` (DELETE + POST)
- `CLAUDE_LOGIN_START / SUBMIT / STATUS / CANCEL`
- `MCP_CATALOG` (GET), `MCP_INSTALL / UNREGISTER` (POST)
- `GIT_INTEGRATIONS` (GET + POST), `GIT_INTEGRATIONS_DELETE / SSH_KEYGEN` (POST)

`shared/.../Dtos.kt` 신규 DTO 14개:
- `ComponentStateDto`, `EnvSetupComponentsResponseDto`, `EnvSetupTaskDto`
- `ClaudeApiKeyRequestDto`, `ClaudeCredentialsUploadResponseDto`,
  `ClaudeLoginStateDto`, `ClaudeLoginSubmitRequestDto`
- `McpConfigFieldDto`, `McpEntryDto`, `McpCatalogResponseDto`,
  `McpInstallRequestDto`, `McpUnregisterRequestDto`
- `GitTokenViewDto`, `GitIntegrationsResponseDto`, `GitTokenRegisterRequestDto`,
  `GitTokenDeleteRequestDto`

`server/.../env/EnvSetupApiRoutes.kt` (신규, ~220줄):
- 모든 신규 API 라우트가 한 파일에 그룹화 (`authenticate(AUTH_BEARER)` 보호).
- 같은 service 인스턴스 (EnvSetupService / ClaudeAuthService / ClaudeLoginService
  / McpService / GitCredentialStore / GitCloneService) 를 SSR 라우트와 공유.
- 도메인 객체 → DTO 매핑 helper 포함.

### Wire change

**예** — v0.10.0 이 vibe-coder-android 와 새 API 계약을 맺습니다. Android
클라이언트는 새 ApiPath 상수 + DTO 를 동기화해야 신규 기능 호출 가능. 구버전
Android (`/api/projects/register` 만 호출) 는 여전히 동작 (DTO optional 필드).

### 배포

- `siamakerlab/vibe-coder-server:0.10.0` multi-arch push 예정.

## [0.8.1] - 2026-05-23

### Changed — 베이스 이미지 Ubuntu 22.04 (jammy) → 24.04 LTS (noble)

`eclipse-temurin:17-jdk-jammy` / `17-jre-jammy` → `17-jdk-noble` / `17-jre-noble`.
**서버 코드 무변경**, base OS 메이저 업그레이드만. 4년 더 긴 보안 업데이트
수명 + 최신 glibc/libstdc++ 확보. Ubuntu 26.04 LTS 의 eclipse-temurin 매핑이
아직 확정되지 않아 24.04 로 전환 (26.04 가용 시점에 다음 마이너에서 재검토).

### Fixed — Ubuntu 24.04 base 의 default `ubuntu` 사용자 UID 1000 충돌

Ubuntu 24.04 (noble) 부터 base image 에 default `ubuntu` 사용자가 UID/GID 1000
으로 사전 생성되어 있어 `groupadd --gid 1000 vibe` 가 exit 4 로 실패. 빌드
첫 시도에서 발견 후 `userdel -r ubuntu` + `groupdel ubuntu` 를 vibe 사용자
생성 전에 수행하도록 Dockerfile 수정. 다른 base (jammy/alpine 등) 에서는
사용자 없음 → 조용히 통과 (`|| true`).

### Verification

빌드 + 런타임 도구 검증:
```
PRETTY_NAME="Ubuntu 24.04.4 LTS"
java 17.0.19, node v20.20.2, npm 10.8.2, git 2.43.0,
claude 2.1.150, script (util-linux) 2.39.3, sudo NOPASSWD ok,
npm prefix = /home/vibe/.local (v0.7.0 bind mount 정책 그대로)
```

### Wire change

**없음.** server.yml 0.8.0 → 0.8.1 (base OS patch).

### 배포

- `siamakerlab/vibe-coder-server:0.8.1` multi-arch push 예정.
- 사용자 영향: `docker compose pull && up -d --force-recreate` 하면 자동
  교체. 호스트 PUID/PGID 1000 매칭은 그대로 (vibe 사용자 UID/GID 동일).
- 기존 `:0.8.0` 사용자가 그대로 두어도 동작 — 보안 업데이트만 받으려면 업그레이드.

## [0.8.0] - 2026-05-23

### Added — MCP 카탈로그 페이지 (체크박스 다중 선택 + 50+개 + 토큰 입력)

`/env-setup/mcp` 신규 페이지. 기존 빌드환경 페이지의 "기본 MCP 묶음" 카드를
체크박스 다중 선택 + 카테고리별 그룹 + per-MCP 토큰 입력 UI 로 대체.

**파일** (모두 신규):
- `server/.../env/McpCatalog.kt` — 50+개 MCP 정적 메타데이터.
  Trust tier (VERIFIED / COMMUNITY / EXPERIMENTAL), category, recommended,
  configFields (TOKEN/URL/DSN 등) 정의.
- `server/.../env/McpService.kt` — 일괄 설치 (npm install -g + `.mcp.json`
  엔트리 등록), 제거 (엔트리만 삭제 — npm 패키지는 디스크 보존), 상태 진단
  (`npm ls -g` + `.mcp.json` 교차).
- `server/.../admin/McpRoutes.kt` — GET `/env-setup/mcp`, POST
  `/env-setup/mcp/install`, POST `/env-setup/mcp/unregister`. 진행은 기존
  `/env-setup/tasks/{taskId}` 페이지 (라이브 로그 + 경과 시간) 재사용.
- `server/.../admin/McpTemplates.kt` — 카테고리별 카드 그룹. 추천 항목 ★,
  trust chip, status chip, 체크박스 선택 시에만 토큰 입력란 노출,
  sticky bottom bar 의 "선택 항목 설치 / 제거" 버튼.

**카탈로그 (10 카테고리, 50+개)**:
- **DEV_TOOLS** — filesystem ★, fetch ★, git ★, memory ★, sequential thinking,
  time, everything
- **GIT_HOSTING** — GitHub ★ (PAT), GitLab (PAT+URL), Gitea (URL+PAT),
  Bitbucket (App PW), Azure DevOps (PAT)
- **DATABASE** — SQLite ★, Postgres (DSN), MySQL, MongoDB (URI), Redis,
  Elasticsearch, Supabase (URL+Key), Firebase (SA JSON)
- **SEARCH** — Brave ★ (API key), Tavily, Perplexity, Firecrawl, Google Maps,
  Context7 ★
- **BROWSER** — Playwright ★, Puppeteer
- **PRODUCTIVITY** — Notion ★ (Integration token), Linear, Jira, Confluence,
  Slack (Bot token), Discord, Trello, Asana, ClickUp, Airtable, Monday.com,
  Google Drive, Obsidian
- **CLOUD** — AWS KB (credentials), Cloudflare (API token), Vercel, Heroku,
  Railway, Docker Hub
- **COMMS** — SendGrid, Twilio (SID+token), Telegram Bot, Stripe (Secret),
  Sentry
- **APP_PUBLISH (Experimental)** — Google Play Publisher (SA JSON),
  App Store Connect (.p8), Fastlane
- **AI_ASSIST** — OpenAI Bridge, YouTube Transcript, Wikipedia, ArXiv,
  Everart (이미지)

**Trust tier 의미**:
- `VERIFIED` — Anthropic/1st-party 공식 — 패키지명 안정적
- `COMMUNITY` — 인기 3rd party — 패키지명 변동 가능성
- `EXPERIMENTAL` — 패키지명 미확정 / 설치 실패 가능 — 카탈로그에서 직접 수정 권장

**카탈로그에 없는 MCP 안내**: 페이지 하단에 `docker exec -it --user vibe
vibe-coder-server bash` 안내 + `npm install -g <pkg>` + `.mcp.json` 직접 편집
예문. v0.7.0 의 `/home/vibe/.local` (npm-global) + `/home/vibe/.claude` bind
mount 덕에 직접 설치한 MCP 도 이미지 업그레이드 후 영구 보존됨을 명시.

### Changed — 기존 MCP_DEFAULTS 카드 → 카탈로그 페이지 링크

빌드환경 페이지(`/env-setup`)의 "기본 MCP 서버 묶음" 카드 버튼이 단일
설치 → "MCP 카탈로그 열기 (50+)" 링크로 교체. 기존 vibe-doctor mcp
서브커맨드는 호환성을 위해 그대로 유지 (CLI 사용자용).

### Internal

- `Module.kt` / `ServerMain.kt` — `McpService` DI + `mcpRoutes` 등록.
- `McpCatalog.kt` 의 KDoc 안 `modelcontextprotocol/*` 시퀀스가 nested
  comment 로 잡히던 컴파일 결함 (v0.4.1 ApkFinder.kt 와 같은 패턴) 사전
  발견 후 회피.

### Wire change

**없음.** 신규 라우트(`/env-setup/mcp*`)는 admin SSR 전용. ApiPath / DTO /
WsFrame 무변경 → Android 클라이언트 무영향.

### 배포

- Docker 이미지 재빌드 필요 (서버 코드 변경) — `siamakerlab/vibe-coder-server:0.8.0`
  multi-arch (amd64+arm64) push 예정.
- 기존 `:0.7.0` 이미지 사용자는 그대로 동작 (catalog 라우트만 없을 뿐).
  업그레이드 시 `docker compose pull && up -d --force-recreate`.

## [0.7.1] - 2026-05-23

문서 + 운영 메타 patch. 이미지 무변경 (서버 코드 변경 없음 — `0.7.0` 이미지
재사용 가능).

### Added — AGPL-3.0 LICENSE + 공개 오픈소스 전환

- `LICENSE` 파일 추가 (GNU Affero GPL v3 전문). copyleft 강화 — SaaS 운영자가
  수정본 소스 공개 의무. 상업적 사용은 가능하나 의무 인지 필요.
- `README.md` 에 라이센스 섹션 + AGPL/Docker badge 추가.
- `Dockerfile` LABEL `licenses` 를 `UNLICENSED` → `AGPL-3.0-or-later`,
  `source` 라벨에 GitHub 리포 URL 추가.

### Changed — Git 리모트 GitHub 공개 전환

- `origin` 을 gitea 자체 호스팅 → `https://github.com/siamakerlab/vibe-coder-server.git`
  로 교체. 기존 gitea 리모트는 `gitea` 이름으로 보존 (병행 mirror 가능).
- `docker/HUB_README.md` / `docker/README.md` 의 source URL 도 신 리포 경로로 동기.

### Changed — 컨테이너명 `vibe-coder` → `vibe-coder-server` 통일

운영자가 다른 vibe 관련 컨테이너(`vibe-coder-android` 등)와 혼동하지 않도록
명시적 풀네임 사용.

- `docker/compose.yml` — `services` 이름 + `container_name` 모두 `vibe-coder-server`.
- 모든 문서/코드의 `docker exec -it vibe-coder ...` → `docker exec -it
  vibe-coder-server ...` 일괄 정정. 영향 파일:
  - `README.md`, `docker/README.md`, `docker/HUB_README.md`
  - `docker/doctor/vibe-doctor`, `docker/doctor/lib/claude-auth.sh`
  - `server/src/.../EnvDiagnostics.kt`, `EnvSetupTemplates.kt`, `AdminTemplates.kt`,
    `WebProjectTemplates.kt`, `ConsoleRoutes.kt`

### Changed — README.md docker 설치 섹션 보강

- v0.7.0 통합 구조 (`./vibe-coder-data/`) 기준 compose 예문으로 재작성.
- "자주 쓰는 운영 명령" 섹션 추가 (logs / restart / exec / pull / recreate).
- 백업/이전 한 줄 명령 (tar + scp + ssh up-d) 추가.
- 라이센스 정보 + Docker Hub badge 헤더에 노출.

### Wire change

**없음.** 서버 코드 로직 무변경 — `:server:installDist` 본체는 v0.7.0 과 동일.
새 docker image 빌드 불요 (단, 라벨 메타 최신화를 원하면 v0.7.1 태그로 재빌드 가능).

## [0.7.0] - 2026-05-23

대규모 운영 편의성 개선 마일스톤. 네 가지가 한 릴리스에 묶였습니다.

1. **Claude 웹 로그인 (터미널 접근 불가 대응)** — 자격증명 파일 업로드 + API 키 모드.
2. **Docker 볼륨 통합 구조** — `/dev-tools` 패턴, 이미지 업그레이드 시 MCP 등이 사라지던 데이터 손실 버그 fix.
3. **설치 진행 UI 개선** — 의미 없던 라인수 progress bar → 경과 시간 + 상태.
4. **신규 프로젝트 CLAUDE.md** — vibe-coder 의 비인터랙티브 환경 룰 자동 삽입.

### Added — Claude 웹 로그인 옵션 A (반자동 OAuth)

세션 도중 추가 — 브라우저만으로 `claude auth login` OAuth 흐름을 완료할 수
있는 가장 부드러운 UX. 옵션 B(파일 업로드) / C(API 키) 와 달리 다른 머신이나
사전 작업이 전혀 필요 없습니다.

- **구현** — `ClaudeLoginService.kt` (신규, ~265줄). claude CLI 2.1.150 분석
  결과 `claude auth login` 이 TUI 라 일반 ProcessBuilder spawn 으로는 stdout
  이 비어 있고 stdin 도 무시됨을 확인. **Linux 표준 도구 `script -q -c "claude
  auth login" /dev/null`** 으로 pty 를 wrap 하면 정상 동작 (JNA/pty4j dep
  불필요). stdout 에서 URL 정규식 캡처 + stdin 으로 사용자 코드 한 줄 write.
  ANSI escape sequence 제거 helper 도 포함 (ESC()-prefix 만 한정 매칭해
  URL 안의 일반 문자 손상 방지).

- **상태 머신** — IDLE → STARTING → AWAITING_CODE → VERIFYING → DONE/FAILED/CANCELED.
  watcher 가 자식 프로세스 exit + `.credentials.json` 존재 여부로 성공 판정 — 가장
  신뢰 가능한 신호. 한 번에 하나의 세션만 (mutex), 동시 시도 시 409.

- **UI** — `/env-setup/claude-login` 전용 페이지 (`EnvSetupTemplates.claudeLoginPage`).
  1초 폴링으로 상태 갱신 (XMLHttpRequest 가 아니라 fetch + JSON). 사용자에게
  보이는 것은 **단순 폼 3개**: (1) 로그인 시작 버튼, (2) 캡처된 URL + "새 탭에서
  열기" 링크 + 복사 버튼, (3) authorization code paste 입력란.
  **터미널 에뮬레이터(xterm.js)는 사용하지 않음** — pty 는 서버 내부 디테일이며
  사용자가 임의 shell 명령을 칠 수 있는 UI 가 아니라 정해진 OAuth 코드 한 줄만
  입력하는 폼이라 CLAUDE.md §3 정책 위반 아님. CLAUDE_AUTH 카드에 "옵션 0 —
  웹에서 한 번에 로그인 (v0.7.0 신규)" 강조 배너로 노출.

- **라우트** (`EnvSetupRoutes.kt`):
  - `GET  /env-setup/claude-login` — 진행 페이지 SSR
  - `POST /env-setup/claude-login/start` — 세션 spawn
  - `POST /env-setup/claude-login/submit` — 코드 제출
  - `POST /env-setup/claude-login/cancel` — 세션 취소
  - `GET  /env-setup/claude-login/status.json` — 폴링용 JSON 상태

- **Dockerfile** — `util-linux` 패키지 명시 추가 (`script` 명령 보장).
  Ubuntu jammy base 에 기본 포함되지만 운영 안정성을 위해 명시.

### Added — Claude 웹 로그인 (옵션 B + C)

`docker exec` 터미널 접근이 불가능한 환경(원격 호스팅, 모바일 운영) 에서도
Claude 인증을 완료할 수 있게 두 가지 웹 경로를 추가. 모두 `CLAUDE.md §3`
의 "raw shell UI 금지" 정책을 위반하지 않습니다.

- **옵션 B — `.credentials.json` 업로드** (`POST /env-setup/claude-auth/upload`)
  다른 머신에서 `claude login` 후 받은 파일을 그대로 멀티파트 업로드.
  JSON 파싱 + `claudeAiOauth.expiresAt` 만료 검증 + 기존 파일 자동 백업
  (`.credentials.json.bak.<ts>`) + atomic move + Posix 0600 권한.

- **옵션 C — `ANTHROPIC_API_KEY` 모드** (`POST /env-setup/claude-auth/api-key`)
  OAuth 대신 API 키. `${CLAUDE_CONFIG_DIR}/.env.api-key` 에 0600 저장.
  새 헬퍼 `ClaudeProcessEnv.applyApiKey()` 가 모든 claude 자식 프로세스
  spawn 시점(`ClaudeSessionManager` + `ClaudeStatusService`) 에서 환경변수로
  주입. 컨테이너 재기동 불필요. 진단 (`EnvDiagnostics` + `EnvSetupService`)
  은 API 키 모드면 OAuth 검사보다 우선해 OK 판정.

- **UI** — `EnvSetupTemplates.CLAUDE_AUTH` 카드를 3-옵션 `<details>` 구조로
  확장 (옵션 1 터미널 / 옵션 2 파일 업로드 / 옵션 3 API 키). flash 알림
  (`?claude=uploaded|api-key|api-key-deleted`) 추가.

- **옵션 A (반자동 웹 OAuth) 도 같은 v0.7.0 안에 추가** — 위 별도 섹션 참고.

### Fixed — Docker 볼륨 통합: 이미지 업그레이드 시 MCP 가 사라지던 버그

**증상**: `docker compose pull && up -d --force-recreate` 후 `vibe-doctor mcp`
로 설치한 MCP 서버들이 통째로 사라짐. Android SDK / Gradle 캐시는 named
volume 이라 보존됐지만, MCP 는 시스템 디렉토리(`/usr/local/lib/node_modules`) 에
설치되어 이미지 layer 안에만 존재했음.

**원인**:
- `docker/doctor/lib/mcp.sh:46` 의 `npm install -g $pkg` 가 시스템 prefix 사용.
- `/home/vibe/.npm`(npx 캐시), `/home/vibe/.cache/ms-playwright`(브라우저),
  `/home/vibe/.config` 도 마운트되지 않아 같은 위험.

**수정**:
- Dockerfile — vibe 사용자의 npm prefix 를 `/home/vibe/.local` 로 분리
  (`~/.npmrc`). `PATH` 에 `/home/vibe/.local/bin` 추가. 새 디렉토리들
  (`.npm`, `.cache/ms-playwright`, `.local/{bin,lib/node_modules}`)
  사전 생성.
- entrypoint.sh — 같은 디렉토리들을 chown 루프에 추가. `.npmrc` 가 빈
  볼륨에 가려진 경우 idempotent 재생성.
- compose.yml — **통합 데이터 디렉토리 (`./vibe-coder-data`) 패턴으로
  재작성**. named volume 선언 제거. 6개 dev-tools 디렉토리 + workspace
  + server + claude 가 모두 한 부모 아래에 들어가, `tar` 한 줄로 백업/이전
  가능.

```
./vibe-coder-data/
├── workspace/        → /workspace
├── server/           → /data
├── dev-tools/
│   ├── android-sdk/  → /opt/android-sdk
│   ├── gradle/       → /home/vibe/.gradle
│   ├── npm-global/   → /home/vibe/.local        (신규 — MCP 영구 저장)
│   ├── npm-cache/    → /home/vibe/.npm          (신규)
│   ├── playwright/   → /home/vibe/.cache/ms-playwright  (신규)
│   └── config/       → /home/vibe/.config       (신규)
└── claude/           → /home/vibe/.claude
```

**기존 사용자 마이그레이션**: `docker/README.md` 의 "v0.7.0 마이그레이션"
섹션에 두 가지 옵션 (깔끔 재시작 / 기존 named volume → bind mount 복사)
을 단계별 명령으로 제공.

### Changed — 설치 진행 페이지 UI

`/env-setup/tasks/{taskId}` 페이지의 **라인수 기반 progress bar 제거**.
설치 작업은 종료 시점을 예측할 수 없어 line/10 으로 보여주던 막대가 의미
없었고, 사용자에게 잘못된 ETA 신호를 줬음. 다음으로 교체:

- **경과 시간** (`HH:MM:SS`, 1초마다 갱신, tabular-nums 폰트).
- **상태 칩** — 진행 중(▶) / 완료(✓) / 오류(✗) / 연결 끊김(●).
- **마지막 활동 시각** ("방금" / "N초 전" / "종료됨") — 정체 감지 보조.
- 라인 수는 작은 dim 표기로 보조 정보.

### Added — 신규 프로젝트 `.claude/settings.json` 자동 생성 + vibe NOPASSWD sudo

vibe-coder 환경(stream-json, TTY 없음, turn 내 인터랙션 불가) 에 맞게 Claude
Code 동작을 사전 조정. 신규 프로젝트 생성 시 `<root>/.claude/settings.json`
이 함께 떨어집니다 (`ProjectService.kt:64-72`, `ClaudeSettingsTemplate.kt` 신규).

- `permissions.defaultMode = "bypassPermissions"` — 모든 권한 승인 자동.
  `ask` 모드면 vibe-coder 콘솔이 prompt 노출 채널이 없어 세션이 영구 hang.
- `permissions.deny[]` — 인터랙티브 / hang 가능 명령 차단 (vim/vi/nano/emacs,
  top/htop, less/more, `tail -f`, `adb logcat`, `claude login/logout`,
  `gh auth login`, `npm init` -y 없는 형태 등).
- `env` 강제 비대화형: `TERM=dumb`, `CI=1`, `NO_COLOR=1`, `BROWSER=""`,
  `PAGER=cat`, `EDITOR=true`, `VISUAL=true`, `DEBIAN_FRONTEND=noninteractive`.
- **모든 MCP allow** — vibe-doctor 가 실제 설치한 것만 존재하므로 화이트리스트
  불요. 운영자가 필요시 `.claude/settings.local.json` 으로 override.

Dockerfile 에 **vibe 사용자 NOPASSWD sudo** 추가 (`/etc/sudoers.d/vibe-nopasswd`,
`visudo -c` 검증). sudo prompt 가 vibe-coder 스트림에서 hang 을 일으키던
경우를 해소. 컨테이너가 1인 LAN 격리 도구라 도커 레벨에서 외부 신뢰 경계가
이미 분리되어 있어 가능한 정책.

### Added — 신규 프로젝트 CLAUDE.md 에 비인터랙티브 환경 룰 자동 삽입

vibe-coder 의 콘솔은 Claude 자식 프로세스를 stream-json 으로만 통신하므로
화살표 키 / TUI / stdin prompt / watch 모드를 사용자가 응답할 수 없습니다.
신규 프로젝트가 생성될 때 `ProjectService.createProject` 가 쓰는
`ClaudeMdTemplate.CONTENT` 에 **"Non-Interactive Environment" 섹션**
추가 — Claude Code 가 다음을 지키도록:

- TUI / arrow-key menu / `npm init` (without `-y`) / `claude login` 등
  stdin 대기 명령 금지.
- `tail -f`, `adb logcat` 같은 stop condition 없는 무한 명령 금지.
- 사용자 확인이 필요하면 응답 끝에 (A)(B)(C) 옵션 + 권장안으로 적고
  멈춤. 사용자는 **다음 프롬프트** 에서 선택.

영어 + 한국어 요약 병기. 기존 프로젝트의 CLAUDE.md 는 `notExists()` 가드로
덮어쓰지 않음 (마이그레이션 영향 없음).

### Wire change

**없음.** 본 릴리스는 모두 서버 내부 + 운영 환경 변경. `ApiPath` / DTO /
`WsFrame` 미변경 → Android 클라이언트는 별도 업데이트 불요. 단,
`vibe-coder-android` 의 README/docs 도 `:0.6.3` → `:0.7.0` 태그 갱신은
운영 일관성상 권장.

### 배포

- Docker Hub: `siamakerlab/vibe-coder-server:0.7.0` (마일스톤 — `linux/amd64
  + linux/arm64` multi-arch).
- 모든 docker 파일 + README + HUB_README 의 태그 동기 (0.6.3 → 0.7.0).

### 사용자 즉시 처치 (v0.6.x → v0.7.0)

```bash
# 1) 최신 compose.yml / .env 로 교체
docker compose down
curl -fsSL .../compose.yml -o compose.yml
curl -fsSL .../.env.example -o .env

# 2) 신구조 부팅 (./vibe-coder-data/ 자동 생성)
docker pull siamakerlab/vibe-coder-server:0.7.0
docker compose up -d

# 3) 브라우저 → 빌드환경 → "모두 설치/업데이트"
#    (또는 기존 named volume 데이터를 복사하려면 docker/README.md 마이그레이션 가이드)

# 4) Claude 인증 (선택 1)
#    - 터미널: docker exec -it --user vibe vibe-coder claude login
#    - 웹:    /env-setup → "옵션 2 자격증명 업로드" 또는 "옵션 3 API 키"
```

## [0.6.3] - 2026-05-23

### Fixed — `docker exec` 가 root 로 떨어져 토큰이 vibe 홈에 안 들어가던 함정

콘솔에서 `Success: Not Logged in. Please run /login` 이 뜨는데, 사용자는
이미 `docker exec -it vibe-coder claude login` 으로 로그인했고 빌드환경
페이지도 "로그인됨" 으로 보였던 사례의 진짜 원인.

**원인**: `docker exec` 의 기본 사용자는 root (Dockerfile 의 `USER` 미설정).
entrypoint 의 `gosu vibe` 강등은 `docker run` 의 ENTRYPOINT 흐름에만 적용
되고 `docker exec` 는 그걸 거치지 않는다. 따라서:

- `docker exec -it vibe-coder claude login` → root 로 실행
- 토큰이 `/root/.claude/.credentials.json` 에 저장
- 서버는 vibe 사용자 (`CLAUDE_CONFIG_DIR=/home/vibe/.claude`) 로 동작 → 못 찾음
- 빌드환경 진단은 `/home/vibe/.claude/` 만 봐서 이전 로그인 흔적(파일은 있음)
  을 보고 OK 판정 → false positive

**수정**:

1. 모든 안내 명령에 `--user vibe` 추가 — 12군데 일괄 정정:
   - `EnvDiagnostics.checkClaudeAuth` / `buildClaudeAuthHelp`
   - `EnvSetupTemplates` (처음 사용 안내 + Claude 로그인 카드)
   - `WebProjectTemplates` (콘솔 페이지 인증 배너 + 라이브 배너)
   - `AdminTemplates` (대시보드 환경 카드 hint)
   - `ConsoleRoutes` (REST 503 응답 메시지)
   - `docker/doctor/lib/claude-auth.sh` / `docker/README.md` / `docker/HUB_README.md`

2. **잘못된 위치 감지 + 안내**: `EnvDiagnostics.checkClaudeAuth` /
   `EnvSetupService.probeClaudeAuth` 가 vibe 홈의 `.credentials.json` 이
   없을 때 `/root/.claude/.credentials.json` 도 점검. stray 토큰이 발견되면
   ERROR 메시지에 "토큰이 root 사용자 홈에 저장됨 — `--user vibe` 로 재로그인
   필요" 와 정확한 명령을 표시.

### Documentation — refresh token 자동 갱신 보증

`buildClaudeAuthHelp` 메시지에 한 줄 추가:

> "refresh token 으로 access token 은 자동 갱신되므로 한 번만 진행하면 됩니다."

이미지 pull / 컨테이너 재기동 / 시간 경과로 access token 이 만료돼도, refresh
token 이 살아있으면 claude CLI 가 자동 갱신한다는 점을 명시. 사용자는 평소
명시적 재로그인이 필요 없다.

### Wire change

**없음.** 안내 텍스트 / 진단 메시지 갱신만. ApiPath / DTO / WsFrame 무변경.

### 사용자 즉시 처치

```bash
# 1) 잘못된 위치(root)에 떨어진 토큰이 있다면, vibe 사용자로 재로그인
docker exec -it --user vibe vibe-coder claude login

# 2) 브라우저 새로고침 → 빌드환경 "Claude 로그인" 이 ✓ 로그인됨 으로 표시
#    콘솔 페이지의 첫 프롬프트도 정상 동작
```

### 배포

- Docker Hub: `siamakerlab/vibe-coder-server:0.6.3` / `:latest` (linux/amd64).
- 모든 docker 파일 + README 의 태그 동기.

### v0.7.0 로 옮긴 후보 (사용자가 명시적으로 요청 시 진행)

- **PTY 기반 웹 터미널** (`/env-setup/claude-login` 라이브) — xterm.js +
  Java PTY 로 컨테이너 안에서 `claude login` 을 브라우저에서 직접 실행.
  CLAUDE.md §3 "raw-shell UI 금지" 정책 완화 결정이 선행되어야 함.

## [0.6.2] - 2026-05-23

### Fixed — Claude 인증 진단: 토큰 만료까지 검증 (false positive 완전 해결)

v0.6.1 까지 `.credentials.json` 파일 존재만 보고 OK 로 판정했는데,
**파일은 있지만 OAuth 토큰이 만료된 상태** 가 흔하다. 사용자는 콘솔에서
`Success: Not logged in. Please run /login` 을 받는데 빌드환경 페이지
/대시보드는 ✓ 로그인됨 으로 표시되는 모순.

**진단 강화**: `.credentials.json` 의 `claudeAiOauth.expiresAt` (epoch ms)
까지 파싱해 실제 만료 여부 확인.

| 상태 | UI |
|---|---|
| `.credentials.json` 없음 | ✗ 로그인 필요 |
| 파일 있고 `expiresAt > now` (여유 6h+) | ✓ 로그인됨 (만료: yyyy-MM-dd HH:mm:ss) |
| 만료 6시간 이내 | △ 곧 만료 — 재로그인 권장 |
| `expiresAt <= now` | ✗ 토큰 만료 — 재로그인 필요 |
| 파일은 있는데 형식 파싱 실패 | △ 만료 시각 확인 실패 (WARNING) |

판정은 `EnvDiagnostics.checkClaudeAuth` 와 `EnvSetupService.probeClaudeAuth`
양쪽에서 같은 기준으로 적용. 두 곳 모두 `readOauthExpiresAt(path)` helper 가
JSON 을 안전하게 파싱하고, 어떤 실패도 null 로 떨어뜨려 페이지가 깨지지 않게.

### Added — 콘솔 페이지의 라이브 인증 실패 배너

진단이 어떤 이유로든 false positive 라도 사용자가 막막해지지 않도록,
콘솔 페이지의 WS 로그 스트림에서 다음 패턴을 감지하면 즉시 빨간 배너 +
프롬프트 폼 비활성화:

```
/(not logged in|please run \/login|invalid api key|unauthorized|authentication required)/i
```

`console_assistant` / `console_tool_result` / `console_error` /
`console_system` 모든 채널의 텍스트를 스캔.
배너 안에 `docker exec -it vibe-coder claude login` 명령을 그대로 표시,
사용자가 복사 → 재로그인 → 페이지 새로고침 흐름.

### Wire change

**없음.** `EnvironmentCheckDto.claudeAuth` 의 message/detail 텍스트만 풍부해짐.
DTO 구조 / ApiPath / WsFrame 무변경. 안드로이드 앱 영향 없음.

### 배포

- Docker Hub: `siamakerlab/vibe-coder-server:0.6.2` / `:latest` (linux/amd64).
- 모든 docker 파일 + README 의 태그 동기.

### 사용자 즉시 처치

콘솔에서 `Not logged in. Please run /login` 메시지를 본 경우:

```bash
docker exec -it vibe-coder claude login
```

브라우저 새로고침 — 빌드환경/대시보드의 "Claude 로그인" 행이 ✓ 로그인됨
(만료 시각 표시) 으로 바뀌면 콘솔도 정상 동작합니다.

## [0.6.1] - 2026-05-23

### Added — 빌드환경 페이지 Phase B: 원클릭 설치 + 일괄 + 진행 페이지

v0.6.0 의 상태 진단 + 명령 안내 위에, 사용자가 **버튼 한 번**으로
설치를 시작하고 **실시간 progress + 로그** 를 볼 수 있도록 완성.

**원클릭 설치**

- 카드별 "설치 / 재설치 / 이어서 설치" 버튼 — 상태에 따라 라벨 자동 변경
  (MISSING → "설치", PARTIAL → "이어서 설치", INSTALLED → "재설치 / 업데이트").
- `POST /env-setup/{componentId}/install` → 새 task id 발급 후 즉시
  `/env-setup/tasks/{taskId}` 로 redirect. 한 클릭으로 진행 화면까지 도달.
- 자동 설치 가능한 컴포넌트: Android SDK / MCP 기본 묶음. Claude 로그인 은
  OAuth interactive 라 자동 불가 — 명령 안내만 유지.

**일괄 설치**

- 페이지 상단 우측에 **⚡ "모두 설치/업데이트" 버튼**. 자동화 가능한 모든
  컴포넌트를 단일 task 안에서 순차 실행, 같은 진행 페이지에서 통째로 본다.
- `POST /env-setup/install-all` → 단일 taskId 로 묶임.

**진행 페이지**

- `GET /env-setup/tasks/{taskId}` — 상태 라벨 / 라인 카운터 / progress
  bar (라인 수 기반 추정, 1000 라인=100% saturating) / 실시간 로그.
- WS endpoint `/ws/env-setup/{taskId}/logs` (기존 빌드용 legacy log stream
  재사용). 인증은 v0.5.5 와 동일하게 handshake cookie.
- 완료 시 progress bar 가 SUCCESS=초록 / FAILED=빨강 으로 색이 바뀌고
  하단 hint 가 "빌드환경으로 돌아가기" 또는 "원인 확인 후 재시도" 로 변경.

**백엔드 구현**

- `EnvSetupService.spawnInstall(c)` / `spawnInstallAll()` — `TaskQueue`
  에 등록, `Dispatchers.IO` 에서 `vibe-doctor <subcmd>` 자식 프로세스 spawn,
  stdout 라인 단위로 `WsFrame.Log(level=STDOUT, ...)` emit. 종료 시
  `WsFrame.Done(status)`.
- `EnvSetupService` 생성자에 `TaskQueue` / `LogHub` / `Clock` 의존성 추가.
  `ServerMain` 에서 기존 인스턴스 재사용 (ServerContext 변경 없음).
- `lastTaskId(c)` — 컴포넌트 최근 작업 id 캐시 (재시도 시 같은 진행 페이지로
  돌아가는 용도, 현재는 미사용 / 향후 확장 여지).

### Fixed — Claude 로그인 false positive

v0.5.4 ~ v0.6.0 의 `EnvDiagnostics.checkClaudeAuth` 와
`EnvSetupService.probeClaudeAuth` 가 `~/.claude/.credentials.json` 또는
`config.json` **둘 중 하나만** 있어도 OK 로 판정해 false positive 가 났다.
실제로는 `claude` CLI 가 첫 실행 시 빈 `config.json` 을 항상 만들기 때문에
"config.json 존재 = 로그인됨" 이 아니다. 콘솔에서 `Not logged in. Please
run /login` 이 뜨는데 빌드환경 페이지/대시보드는 "로그인됨" 으로 표시되던
원인.

수정: **`.credentials.json` 만** 보고 판정. `config.json` 은 무시.
vibe-doctor (`docker/doctor/lib/check.sh`) 는 동일 false positive 가 있으나
이 PR 에서는 도커 셸 스크립트는 건드리지 않고 server 측 로직만 정정.

### Changed — 컴포넌트 라벨

Claude 로그인 카드의 상태 배지가 "✓ 설치됨 / ✗ 미설치" → **"✓ 로그인됨 /
✗ 로그인 필요"** 로 표시. 다른 컴포넌트(SDK 등)는 그대로.

### Wire change

**없음.** 새 라우트는 SSR / form / cookie 기반. ApiPath / DTO / WsFrame
무변경. 안드로이드 앱 영향 없음.

### CSS

- `.progress-bar` / `.progress-fill` (`done-ok` / `done-fail`) 추가.

### 배포

- Docker Hub: `siamakerlab/vibe-coder-server:0.6.1` / `:latest` (linux/amd64
  · v0.6.0 정책에 따라 일반 push 는 amd64-only).
- 모든 docker 파일 + README 의 태그 동기.

## [0.6.0] - 2026-05-23

### Added — 빌드환경 페이지 (`/env-setup`) — Phase A

좌측 nav 에 "빌드환경" 메뉴를 신설. 슬림 도커 이미지 정책 ("무거운
컴포넌트는 볼륨에") 의 사용자 진입 부담을 줄이기 위해, 어떤 컴포넌트가
설치돼 있고/없는지 한 화면에서 확인하고 설치 명령을 그대로 복사해서
실행할 수 있게 함.

**대상 컴포넌트 (7개)**

| 컴포넌트 | 출처 | 설치 |
|---|---|---|
| JDK 17 / Git CLI / Node.js / Claude CLI | 도커 이미지 내장 | 추가 작업 불필요 (✓ 자동 진단) |
| Claude 로그인 (`.credentials.json`) | OAuth | `docker exec -it vibe-coder claude login` |
| Android SDK (cmdline-tools + platform-tools + platforms;android-35 + build-tools) | sdkmanager | `docker exec -it vibe-coder vibe-doctor android` |
| Platform Tools (ADB) | Android SDK 포함 | SDK 카드로 일괄 처리 |
| 기본 MCP 서버 묶음 | `vibe-doctor mcp` | `docker exec -it vibe-coder vibe-doctor mcp` |

**핵심 구현**

- `server/env/EnvSetupService.kt` — `SetupComponent` enum + `detect()`
  per-component 진단. 도커 doctor (`docker/doctor/lib/check.sh`) 와 같은
  기준 (cmdline-tools / platform-tools / platforms / build-tools 디렉토리
  존재 여부, claude credentials 파일 여부 등).
- `server/admin/EnvSetupRoutes.kt` — `GET /env-setup` SSR 페이지.
- `server/admin/EnvSetupTemplates.kt` — 카드 7개 + "처음 사용하시나요?"
  안내 + "이미지 pull 후에도 보존됩니다" 보증 카드.
- `AdminTemplates.shell()` 의 좌측 nav 에 `링크 /env-setup "빌드환경"` 추가.

**Phase B 예고 (v0.6.1+)**

지금은 안내/명령 표시만. 다음 단계에서 카드의 "원터치 설치 버튼" + 진행
페이지 (`/env-setup/tasks/{taskId}` + `/ws/env-setup/{taskId}/logs`) +
실시간 progress bar 가 추가됩니다.

### Documentation — "빌드환경은 이미지를 갈아끼워도 보존됩니다" 보증

도커 이미지 pull 후 컨테이너를 recreate 해도 SDK/Gradle 캐시/Claude 인증/
프로젝트 소스가 살아남는다는 점을, README 와 빌드환경 페이지 양쪽에
표 형식으로 명시.

| 데이터 | 마운트 | 이미지 pull 시 |
|---|---|---|
| Android SDK (3~4GB) | `vibe-android-sdk` (named) | ✅ 보존 |
| Gradle 의존성 캐시 | `vibe-gradle-cache` (named) | ✅ 보존 |
| Claude 인증 | `~/.claude` (host bind) | ✅ 보존 |
| 프로젝트 소스 + APK | `./workspace` (host bind) | ✅ 보존 |
| DB / 로그 | `./vibe-data` (host bind) | ✅ 보존 |
| 서버 본체 | 이미지 내장 | 🔄 새 이미지로 교체 |

⚠️ `docker compose down -v` 는 named volume 까지 삭제 — 일반 업그레이드
시 사용 금지. README 에 명시.

### Documentation — README compose 예문 보강

직접 `compose.yml` 을 쓰고 싶은 사용자를 위해 최소 형태 (image + ports +
volumes + named volumes 선언) 를 그대로 복사해 쓸 수 있도록 README 본문에
포함. 기존 "curl 으로 받아서 docker compose up" 흐름도 같은 섹션에 유지.

### Wire change

**없음.** 새 SSR 페이지는 ApiPath / DTO / WsFrame 변경 없이 서버 측에서
`EnvSetupService.detect()` 호출 → HTML 렌더만 함. 안드로이드 앱 영향 없음.

### Refactored

- `ServerContext` 에 `envSetup: EnvSetupService` 추가.
- `Module.kt` 에 `envSetupRoutes(...)` 등록.

### 배포

- Docker Hub: `siamakerlab/vibe-coder-server:0.6.0` / `:latest` (linux/amd64).
- **빌드 정책 변경** — 이번 릴리즈부터 일반 개발 push 는 amd64 only.
  multi-arch (amd64 + arm64) 빌드는 마일스톤 (v0.7.0, v1.0.0 등) 시점에만
  진행한다. 사유: arm64 emulation 빌드가 amd64 대비 3~5배 느려 (10~15 분 vs
  2~3 분) 잦은 commit 흐름을 지연시킴. ARM 호스트 (Apple Silicon / RPi /
  ARM 클라우드) 사용자는 마일스톤 이미지로 pull 하거나, Docker Desktop 의
  자동 emulation 으로 amd64 이미지를 실행할 수 있다.
- 모든 docker 파일 + README 의 태그 동기.

## [0.5.5] - 2026-05-23

### Fixed — 웹 콘솔/빌드 페이지의 WS `invalid_token` (httpOnly 쿠키 충돌)

v0.4.0 부터 `vibe_session` 쿠키는 XSS 방어를 위해 `httpOnly=true` 로 설정.
v0.5.0 콘솔 페이지의 JS 가 `document.cookie.match` 로 그 토큰을 읽으려
했지만, 정의상 JavaScript 는 httpOnly 쿠키에 접근하지 못한다 → 빈 token 으로
첫 `Auth` 프레임을 보냄 → `deviceRepo.findByTokenHash("")` null → 항상
`invalid_token`.

이로 인해 v0.5.0 ~ v0.5.4 사이 웹 브라우저에서 콘솔/빌드 로그가 처음부터
끊겼다. REST API 는 같은 쿠키를 서버 측에서 직접 읽기 때문에 정상 동작
했고, SSR 페이지도 멀쩡히 열려서 발견이 늦었다.

### 수정 — 서버: WS handshake cookie 인증 + JS: auth 프레임 제거

- **`WsRoutes.authenticateFirstFrame`** 에 Path 1 (cookie) 추가.
  WebSocket handshake 시 브라우저가 자동으로 동일 origin 쿠키를 첨부하므로,
  서버는 `call.request.cookies[SESSION_COOKIE]` 에서 토큰을 추출해 곧바로
  device 매칭. 성공 시 첫 프레임을 기다리지 않고 인증 완료.
  - 쿠키가 있는데 매칭 실패 → 즉시 `invalid_token` close.
  - 쿠키 없음 → 기존 Path 2 (첫 텍스트 프레임 = `WsFrame.Auth`) 로 fallback.
    안드로이드 앱은 이 경로로 그대로 동작 → wire 호환 유지.
- **콘솔 / 빌드 페이지 인라인 JS** — `ws.onopen` 에서 token 추출 + Auth
  프레임 송신 코드를 제거. handshake cookie 만으로 인증.
- `function authenticateFirstFrame` 의 expression-body 가 named-return 을
  못 잡아 block body 로 리팩토.

### Wire change

**없음.** 안드로이드 앱은 쿠키를 보내지 않으므로 Path 2 (첫 Auth 프레임)
경로를 그대로 탄다. 기존 protocol 무수정 호환. SDK 변경 없이 그대로 동작.

### 배포

- Docker Hub: `siamakerlab/vibe-coder-server:0.5.5` / `:latest`.
- 모든 docker 파일 + README 의 태그 동기.

### 영향 받은 사용자 안내

v0.5.0 ~ v0.5.4 컨테이너에서 콘솔/빌드 로그가 WS `invalid_token` 으로 끊겼던
경우, v0.5.5 이미지로 갈아끼우면 즉시 해결됩니다. 별도 재로그인 불필요.

```bash
docker pull siamakerlab/vibe-coder-server:0.5.5
docker compose up -d --force-recreate
```

## [0.5.4] - 2026-05-23

### Added — Claude CLI 인증 진단 + 콘솔 가이드

새 프로젝트를 만들고 처음 프롬프트를 보낼 때 Claude CLI 가 인증 안 된
상태였다면, 지금까지는 사용자가 `Invalid API key` / `Please run /login`
같은 자식 프로세스 stderr 만 ConsoleSystem 으로 띄엄띄엄 받아보는
상황이었다. 어떤 명령을 어디서 실행해야 풀리는지가 화면에 없었다.
이번 릴리즈는 그 흐름을 처음부터 끝까지 명확하게 만든다.

- **`EnvDiagnostics.checkClaudeAuth()`** — `CLAUDE_CONFIG_DIR` (없으면
  `~/.claude`) 의 `.credentials.json` 또는 `config.json` 존재 여부를
  검사. vibe-doctor (`docker/doctor/lib/check.sh`) 와 같은 기준.
  - CLI 미설치 → ERROR + "CLI 먼저 설치" 안내.
  - 자격증명 파일 있음 → OK.
  - 없음 → ERROR + "도커 / 호스트별 `claude login` 명령" 안내.
- **`EnvironmentCheckDto.claudeAuth`** — 새 필드. nullable + default null
  로 추가했으므로 안드로이드 앱은 무수정으로 호환 (이 필드는 그냥 안 봄).
- **대시보드 환경 카드** — "Claude 로그인" 행 추가. 로그인 안 됐을 때
  `docker exec -it vibe-coder claude login` 한 줄을 같은 카드에 노출.
- **콘솔 페이지** —
  - CLI 미설치 / 인증 누락 시 페이지 상단에 빨간 배너 (제목 / 본문 /
    복사 가능한 명령 1줄 / 자세히 펼치기) 표시.
  - 프롬프트 textarea + 전송 버튼이 자동 `disabled`. 사용자가 잘못된
    입력을 보내고 의문의 에러를 받는 흐름을 차단.
- **`POST /api/projects/{id}/claude/console/prompt`** — 서버 측 가드.
  인증 미완 / CLI 미설치 시 `503 claude_cli_missing` 또는
  `503 claude_auth_required` 에러 코드 + 한국어 메시지로 응답.
  안드로이드 앱도 곧바로 사람이 읽을 수 있는 안내를 받게 된다.

### Wire change

**최소.** `EnvironmentCheckDto.claudeAuth` 가 추가됐지만 nullable +
default null 이라 기존 안드로이드 빌드와 backward compatible.
DTO / ApiPath / WsFrame 의 명칭은 변경 없음.

새로운 에러 코드: `claude_cli_missing` (503), `claude_auth_required` (503).
안드로이드 앱은 기존 ApiException 처리 흐름 그대로 메시지만 보여주면 됨.

### Refactored

- `consoleRoutes(...)` 시그니처에 `envDiagnostics: EnvDiagnostics` 추가.
  `ServerContext.env` 그대로 주입 (ServerContext 변경 없음).
- `AdminTemplates.dashboardPage(...)` 시그니처에 `claudeAuth: CheckItemDto?`
  추가. 기존 호출부도 그대로 컴파일되도록 default null.

### 배포

- Docker Hub: `siamakerlab/vibe-coder-server:0.5.4` / `:latest`.
- 모든 docker 파일 + README 의 태그 동기.

### 다음 단계 (v0.5.5+)

- 콘솔 페이지에서 WS 로 흘러오는 `claude_unavailable` 등 ConsoleSystem
  코드를 보고 자동으로 인증 가이드 배너를 띄우는 라이브 fallback.
- i18n.

## [0.5.3] - 2026-05-23

### Added — 종료된 빌드의 디스크 로그 replay

v0.5.2 빌드 상세 페이지의 한계 — "종료된 빌드의 로그는 메모리 ring 에서
evicted 되면 더 이상 못 본다" — 를 해결. 종료 상태 빌드를 열면 서버가
워크스페이스의 `.log` 파일을 즉시 읽어 화면에 prerender 한다.

구현:

- **`loadBuildLog(workspace, projectId, buildId, storedLogPath)`** —
  `BuildRow.logPath` 를 읽어 `BuildLogReplay` 로 변환.
  - 보안: `WorkspacePath.ensureUnderWorkspace` 통과 + DB row 의 path 가
    실제로 `workspace.buildLogFile(projectId, buildId)` 와 같은지 한 번 더
    검증 (DB 변조 / path-traversal 방어).
  - 파일 미존재 / 읽기 실패 → null. UI 는 안내만 표시하고 페이지는 살아남음.
  - 너무 큰 파일은 `MAX_REPLAY_LINES = 2000` 줄로 tail-truncate.
    `truncated=true` 시 UI 가 "마지막 N / 전체 M 줄 표시" 로 알림.
- **`parseLogLine`** — `TaskLogger` 가 쓰는 `[ts] [level] message` 라인을
  분해. 포맷 외 라인(stack trace continuation 등) 은 `level=RAW` 로 fallback.
- **UI 통합** —
  - `buildDetailPage(replay = ...)` 인자 추가.
  - 종료 상태일 때 로그 카드 헤더가 "파일 replay" 로 표시.
  - 라인들은 WS 라이브 흐름과 동일한 색상 팔레트 (STDOUT=green / STDERR/ERROR=red
    / WARN=amber / INFO/sys=gray).
  - 카드 하단 caption — 파일 경로, 크기 (KB), 총 줄 수, 잘림 여부.

### Changed — 라우트 의존성

`webProjectRoutes(...)` 시그니처에 `workspace: WorkspacePath` 추가.
`ServerContext` 변경 없이 기존 `ctx.workspace` 재사용.

### Wire change

**없음.** 디스크 직접 read + 기존 `BuildRow.logPath` 활용. ApiPath / DTO /
WsFrame 무변경.

### 배포

- Docker Hub: `siamakerlab/vibe-coder-server:0.5.3` / `:latest`.
  multi-arch (linux/amd64 + linux/arm64).
- 모든 docker 파일 + README 의 태그 동기.

### 다음 단계 (v0.5.4+)

- 콘솔 메시지 검색 / 토픽 필터.
- i18n (현재 SSR 텍스트는 한국어 하드코딩).
- 빌드 로그 페이지에서도 "다운로드 (.log 파일 전체)" 링크 검토.

## [0.5.2] - 2026-05-23

### Added — 빌드 상세 페이지 + 실시간 로그 (`/projects/{id}/builds/{buildId}`)

지금까지 "빌드를 큐에 등록은 했는데 진행 상황을 어디서 보지?" 가
불명확했던 문제 해결. v0.5.0 부터 이미 존재하던 빌드 WS 라우트
(`/ws/projects/{id}/builds/{buildId}/logs`) 위에 SSR + 인라인 JS
레이어를 얹어 한 페이지에서 다음을 본다:

- **상태 카드** — `PENDING/RUNNING/SUCCESS/FAILED/CANCELED/TIMEOUT` 배지,
  variant, 시작/종료 시각, error message.
- **APK 카드** — 성공 시 다운로드 버튼 (`/api/projects/{id}/artifacts/{aid}/download`),
  파일명, sha256 prefix, 크기 (KB). 진행 중에는 placeholder.
- **로그 카드** — `console-log` 스타일 패널.
  - PENDING/RUNNING 이면 자동 WS 연결 (쿠키 토큰 인증).
  - `Log(level, message)` → 색상 분류 (`STDOUT=green`, `STDERR/ERROR=red`,
    `WARN=amber`, 그 외 sys).
  - `Done(status, errorMessage)` 수신 시 표시 + 5초 후 페이지 reload 로
    최종 상태/APK 링크 갱신.
  - 종료 상태면 WS 미연결 (메모리 ring 이미 evicted) + 파일 로그 경로 안내.
- **취소 chip** — non-terminal 상태에서만 표시. `BuildService.cancel(buildId)` →
  진행 중인 Gradle 프로세스 destroyForcibly.

### Changed — 빌드 트리거 flow 단순화

`POST /projects/{id}/builds` 의 redirect target 을
`/projects/{id}/builds?ok=...` 에서 새 빌드 상세 `/projects/{id}/builds/{newBuildId}` 로
변경. 한 번의 클릭으로 로그가 흐르는 화면까지 도달.

빌드 목록 + 프로젝트 상세 "최근 빌드" 의 build ID 셀이 상세 페이지
링크로 활성화.

### 새 라우트

- `GET /projects/{id}/builds/{buildId}` — 상세 + 로그 페이지
- `POST /projects/{id}/builds/{buildId}/cancel` — 빌드 취소 (form)

### Wire change

**없음.** 새 SSR 페이지는 기존 `BuildService.cancel` / `BuildRepository.get` /
`ArtifactRepository.get` 호출 + 기존 빌드 WS (`/ws/.../logs`) 위에 얇은 레이어.

### 배포

- Docker Hub: `siamakerlab/vibe-coder-server:0.5.2` / `:latest`.
- 모든 docker 파일 + README 의 태그 동기.

### 다음 단계 (v0.5.3+)

- 콘솔 메시지 검색 / 토픽 필터.
- i18n (현재 SSR 텍스트는 한국어 하드코딩).
- 빌드 로그를 파일에서 replay (메모리 ring evicted 후에도 종료 빌드 로그
  열람 가능하도록).

## [0.5.1] - 2026-05-23

### Added — 파일 / Git / 콘솔 chip 통합 (Standalone 도커 앱 완성도 보강)

v0.5.0 에서 깐 SSR 콘솔 위에 안드로이드 앱과 동등한 부가 기능을 마저
얹어 "웹 단독 운용 100%" 를 달성. 이제 브라우저만으로 다음이 가능:

**파일 (`/projects/{id}/files`)**
- `GET` — 업로드된 파일 목록 (이름 / MIME / 크기 / 시각 + 다운로드/삭제 chip).
- `POST /upload` — multipart form 업로드. `UploadService` 가 v0.4.1 에서
  Ktor 3.x `provider().toInputStream()` 으로 모더나이즈된 경로를 그대로 재사용.
- `GET /{fileId}/download` — `Content-Disposition: attachment` 로 응답.
  미인증 시 페이지 점프 대신 401 처럼 동작하도록 `requireSessionOrRedirect`
  호출 후에도 다운로드 흐름이 깨지지 않게 처리.
- `POST /{fileId}/delete` — 파일 + DB row 제거.
- 업로드 확장자 블랙리스트 (`exe/bat/cmd/ps1/sh`) 와 최대 크기는 기존
  `server.yml` 정책을 그대로 따른다.

**Git (`/projects/{id}/git`)**
- 한 페이지에 status (branch / ahead / behind / 변경 entry) + diff 미리보기
  (최대 20KB) + recent 10 commits 를 모두 표시. `GitReader` 호출 결과를
  카드 3개로 분리 렌더.
- diff 본문은 별도 `pre.diff-block` 모노스페이스 패널.
- 읽기 전용 — `git push` / `reset --hard` 등 쓰기 작업은 의도적으로
  노출하지 않음 (CLAUDE.md §3 보안). 필요 시 콘솔에서 Claude 에게 부탁.
- git repository 가 아니거나 git CLI 실패 시 안내 + `git init` 힌트 표시.

**콘솔 chip 통합 (`/projects/{id}/console`)**
- 슬래시 chip 7개 추가: `/status` `/cost` `/model` `/memory` `/plan`
  `/compact` `/clear` (마지막은 danger). 클릭 시
  `POST /projects/{id}/console/slash` (CONSOLE_SLASH_WHITELIST 검증) →
  `ClaudeSessionManager.sendPrompt(id, "/cmd")` 호출.
- 페이지 점프 chip 4개: 프로젝트, 빌드, 파일, git.
- "새 세션 시작" 도 chip 스타일 (danger).

**프로젝트 상세 페이지**
- "작업" 카드에 파일 / git 진입 링크 2개 추가 (콘솔 / 빌드 / 파일 / git).

### CSS / 디자인 시스템

- `.chip` / `.chip-link` / `.chip-danger` 공용 컴포넌트 추가
  (둥근 알약, 호버 효과, danger variant).
- `pre.diff-block` — 다크 모노스페이스 코드 블록.

### Refactored — 라우트 의존성

`webProjectRoutes(...)` 시그니처에 `uploads: UploadService` 와
`gitReader: GitReader` 추가. `ServerContext` 변경 없이 기존 인스턴스
재사용 (`ctx.uploads`, `ctx.git`).

### Wire change

**없음.** ApiPath / DTO / WsFrame 무변경. 새 SSR 라우트는 기존
서비스 (`UploadService` / `GitReader` / `ClaudeSessionManager`) 위에
얇은 폼 + 다운로드 응답만 추가.

### 배포

- Docker Hub: `siamakerlab/vibe-coder-server:0.5.1` / `:latest`.
- 모든 docker 파일 + README 의 태그 동기.

### 다음 단계 (v0.5.2+)

- 빌드 진행 중 실시간 로그 뷰 분리 (현재는 `ConsoleSystem` 으로 흘러감).
- 콘솔 메시지 검색 / 토픽 필터.
- 다국어 (현재 SSR 텍스트는 한국어 하드코딩 — i18n 도입 검토).

## [0.5.0] - 2026-05-23

### Added — 웹만으로 완결되는 SSR 콘솔 (Standalone 도커 앱 자아 확립)

안드로이드 앱 없이도 브라우저 하나로 **프로젝트 등록 → Claude 프롬프트 →
Gradle 빌드 → APK 다운로드** 까지 끝나도록 SSR 화면을 추가. v0.4.2 에서
선언했던 비전의 본 단계.

| 새 경로 | 기능 |
|---|---|
| `GET /projects` | 프로젝트 목록 + "새 프로젝트" 등록 폼 (`projectId` / `appName` / `packageName`) |
| `POST /projects` | `ProjectService.register` 호출. 워크스페이스에 빈 폴더 + `CLAUDE.md` 템플릿 생성. 성공 시 `/projects/{id}` 로 redirect |
| `GET /projects/{id}` | 프로젝트 상세 — 패키지/모듈/소스 경로/최근 빌드 5건 + 콘솔/빌드 진입 링크 + 메타데이터 삭제 |
| `POST /projects/{id}/delete` | 프로젝트 DB row 삭제 (워크스페이스 폴더 보존) |
| `GET /projects/{id}/console` | Claude 프롬프트 입력 + 실시간 로그 뷰. WebSocket `/ws/projects/{id}/console/logs` 자동 연결, replay + live frame 렌더. Ctrl+Enter 로 프롬프트 전송 |
| `POST /projects/{id}/console/new` | `ClaudeSessionManager.startNew` — 현 세션 종료, 다음 프롬프트가 새 대화 시작 |
| `GET /projects/{id}/builds` | 빌드 목록 + 상태 배지 + APK 다운로드 링크 (`/api/projects/{id}/artifacts/{aid}/download`) |
| `POST /projects/{id}/builds` | `BuildService.enqueueDebug` 큐 등록 |

추가 사항:

- **사이드바 nav 에 "프로젝트" 링크 추가** — 대시보드와 같은 깊이의 일급 메뉴.
- **WebSocket 인증 호환** — 콘솔 JS 는 `vibe_session` 쿠키 값을 그대로 첫
  `Auth` 프레임의 token 으로 보냄. 서버 `installAuth` 가 쿠키도 토큰 운반체로
  받아주는 v0.4.0 의 변경 덕분에 별도 토큰 발급 절차 없이 작동.
- **JS 는 인라인** (외부 빌드 파이프라인 없음, Node 의존 없음). LAN-only
  도구 철학 유지.
- **CSS** — `admin.css` 에 `.console-log` / `.prompt-form` / 액션 컬러
  팔레트(`assistant=green` / `tool=amber` / `user=blue` / `err=red` /
  `sys=gray`) 추가.

### Refactored — `AdminTemplates.shell()` internal 노출

새 페이지(`WebProjectTemplates`) 가 동일 레이아웃 셸을 사용할 수 있도록
`private fun shell` → `internal fun shell`. nav link 매칭도 새 경로
체계 (`/projects` 등) 를 인식하도록 갱신.

`requireSessionOrRedirect` / `WebSession` 타입도 `internal` 로 풀어 같은
모듈 내 다른 라우트 파일이 재사용 가능.

### Module 변경

- `server/Module.kt` — `webProjectRoutes(...)` 등록. 의존성:
  `ProjectService` / `BuildService` / `BuildRepository` / `ArtifactRepository`
  / `ClaudeSessionManager` / `LogHub`. `AdminRoutesDeps` 는 `adminRoutes` 와
  `webProjectRoutes` 가 같은 인스턴스를 공유 (세션 검증 일관성).

### Wire change

**없음.** ApiPath / DTO / WsFrame 변경 없음. 안드로이드 앱은 무수정으로
계속 동작. 새 페이지들은 기존 `/api/*` REST + `/ws/*` WebSocket 위에
얇은 SSR + JS 레이어로 얹혀 있다.

### 배포

- Docker Hub: `siamakerlab/vibe-coder-server:0.5.0` / `:latest`.
- `docker/Dockerfile`, `docker/compose.yml`, `docker/.env.example`,
  `docker/README.md`, `docker/HUB_README.md`, `README.md` 의 태그 동기.

### 다음 단계 (v0.5.1+)

- `/projects/{id}/files` — 워크스페이스 파일 업로드/다운로드 UI.
- `/projects/{id}/git` — git status/diff/log 뷰.
- 콘솔 입력에서 액션 chip (status / cost / model / clear 등) 호출 버튼.
- 빌드 로그 실시간 뷰 (현재는 콘솔에 ConsoleSystem 으로 흘러가지만 별도
  탭으로 분리 검토).

## [0.4.2] - 2026-05-23

### Changed — 정체성 선언: Standalone 도커 앱

`vibe-coder-server` 의 자아를 명시한다:

> **Standalone 도커 앱.** Claude Code 를 활용해 Android 앱을 만들어내는
> 외부 접근 가능한 개발머신 그 자체. 브라우저로 바로 로그인해 프로젝트
> 생성 · 프롬프트 전송 · Gradle 빌드 · APK 다운로드까지 끝낸다.
> 안드로이드 앱은 같은 서버를 가리키는 부가 클라이언트일 뿐 없어도
> 모든 기능을 쓸 수 있어야 한다.

이번 릴리즈는 그 비전의 **첫 단계**로 웹 UI 의 경로 구조를 평탄화. 실제
프로젝트 / 콘솔 / 빌드 화면 추가는 Phase 2 (v0.5.0) 에서 진행한다.

`README.md` / `CLAUDE.md` 톤도 동일한 방향으로 다듬음.

### Changed — Web URL 평탄화: `/admin/*` prefix 제거 (web-side breaking)

별도 `admin` 영역 개념을 제거하고 모든 SSR 화면을 루트 레벨로 이동.
사용자가 도메인 / 으로 접속하면 별도 추가 입력 없이 곧바로 첫 화면
(셋업 / 로그인 / 대시보드 자동 분기) 이 뜬다.

| v0.4.1 | v0.4.2+ |
|---|---|
| `/admin` | `/` |
| `/admin/login` | `/login` |
| `/admin/setup` | `/setup` |
| `/admin/settings` | `/settings` |
| `/admin/devices` | `/devices` |
| `/admin/password` | `/password` |
| `/admin/logout` | `/logout` |
| `/admin/static/admin.css` | `/static/admin.css` |

호환: `GET /admin{path...}` 핸들러가 같은 경로의 루트 버전으로
**HTTP 301 영구 리다이렉트** 한다. 북마크 · 구버전 안드로이드 앱
사용자 영향 없음. v0.6.0 에서 호환층 제거 예정.

영향 받지 않음:
- 모든 REST API (`/api/*`) — 안드로이드 앱과 wire-level 호환 그대로.
- WebSocket (`/ws/*`)
- `/health` 헬스 프로브 (Docker HEALTHCHECK)

구현:

- `server/admin/AdminRoutes.kt` — `route("/admin") { ... }` 블록 해체,
  모든 라우트를 root level 로. 함수명 `adminRoutes` 는 호출부 변경
  최소화를 위해 유지 (내부적으로 더 이상 admin 전용이 아님 → 향후
  `webRoutes` 로 rename 검토).
- `staticResources("/admin/static", ...)` → `staticResources("/static", ...)`.
- 모든 `respondRedirect("/admin/...")` 호출을 평탄 URL 로 정정.
- `requireSessionOrRedirect` 의 `next=` 파라미터 검증을 `startsWith("/admin")`
  → `startsWith("/") && !startsWith("//")` 로 강화. open-redirect 방지.
- `server/admin/AdminTemplates.kt` — `<link href=>` , 폼 `action=`, nav
  `href=`, `errorPage` 의 "대시보드로" 링크 전부 평탄 URL 로 정정.
  `navHtml` 의 `active` 클래스 매칭도 새 경로 체계로 갱신.
- `server/ServerMain.kt` 부팅 배너 — "Admin URL" 표기 제거, 단일 URL.

### Fixed — KDoc nested-comment 함정 (재발)

`AdminRoutes.kt` 의 새 KDoc 본문에 `admin/*` 표현을 그대로 적었더니
v0.4.1 의 `ApkFinder.kt` 와 동일한 K2 컴파일러 nested-comment 해석으로
`Unclosed comment` 에러 발생. 백틱 코드 표기 (`admin`) 로 회피.
앞으로 KDoc 내부에서 path glob (`*`, `/*`) 표현 사용 금지를 컨벤션화.

### 배포

- Docker Hub: `siamakerlab/vibe-coder-server:0.4.2` / `:latest`.
- `docker/Dockerfile`, `docker/compose.yml`, `docker/.env.example`,
  `docker/README.md`, `docker/HUB_README.md` 의 태그 동기.

### Wire change

**없음.** ApiPath / DTO / WsFrame 변경 없음. 안드로이드 앱은 무수정으로
계속 동작.

## [0.4.1] - 2026-05-23

### Infrastructure — 모노레포 → 2 리포 분리

`vibe-coder` 단일 저장소를 `vibe-coder-server` (본 리포) 와
`vibe-coder-android` (별도 리포) 로 분리. 본 리포는 `:server` /
`:shared` / `docker/` / `vibe-doctor` / Admin 웹 / docs 만 보유한다.

- `settings.gradle.kts`: `rootProject.name = "vibe-coder-server"`,
  `:android-app:app` 와 `skipAndroidModule` 옵션 제거. 모듈은
  `:shared` / `:server` 둘만.
- `gradle/libs.versions.toml`: Android 전용 라이브러리(Compose BOM,
  Hilt, AndroidX, Material Icons, DataStore, navigation-compose, Timber,
  espresso, Truth, Ktor client, KSP, AGP) 와 그 plugin alias 들
  (`kotlin-compose`, `android-application`, `ksp`, `hilt`, `ktor`) 제거.
- 루트 `build.gradle.kts`: `kotlin-jvm` / `kotlin-serialization` 만 남김.
- `:shared` 는 `vibe-coder-android` 리포에 **동일 사본**으로 존재하며
  wire-level 호환을 유지하기 위해 변경 시 양쪽 함께 갱신한다.

### Fixed — 베이스라인 결함 + deprecated 일괄 정리

CLAUDE.md §7 에 적혀 있던 split 시점 빌드 결함을 **실제 재현**하여 모두 회수.
이전 사이클에서는 Gradle daemon 캐시 영향으로 통과한 것처럼 보였으나,
`./gradlew clean` 후 정상 재현되었다.

- **`build/ApkFinder.kt`** — Kotlin 2.2 에서 제거된 `import kotlin.streams.toList`
  제거. JDK 16+ 의 `Stream.toList()` 멤버 메소드로 충분. 동시에 KDoc 본문
  `…/build/outputs/apk/debug/*.apk` 안의 `/*` 시퀀스가 K2 컴파일러에 의해
  nested comment 시작으로 해석되어 `Unclosed comment` 신택스 에러를 일으키던
  부분도 KDoc 표현을 재작성해 회피. import 결함이 가려져 있던 동안 함께
  숨어 있던 결함.
- **`actions/ServerActionHandler.kt:55`** — 존재하지 않는 `builds.submitDebug`
  호출을 실제 메소드 `builds.enqueueDebug` 로 정정.
- **`build/BuildService.kt`** — 위 두 결함의 파급으로 보고됐던 타입 추론
  실패는 root cause 해결과 동시에 자동 해소.

같이 발견된 비차단 deprecation 경고 2건도 동일 PR 에서 모더나이즈:

- **`auth/AuthPlugin.kt`** — Ktor 3.x 에서 `Principal` interface 가 deprecated
  (`This interface can be safely removed`). `DevicePrincipal: Principal` 의
  상위 인터페이스 제거. `principal<DevicePrincipal>()` 호출은 그대로 동작.
- **`files/FileRoutes.kt`** — `PartData.FileItem.streamProvider` (blocking
  InputStream) deprecated. `provider().toInputStream()`
  (`io.ktor.utils.io.jvm.javaio.toInputStream`) 로 교체. UploadService 시그니처
  (`InputStream` 입력) 는 그대로 유지.

이외에 split 직후 한 번 발견됐던 `ProcessRunnerTimeoutTest` 의 `@Test fun =
runBlocking { ... }` 시그니처 (Kotlin 2.2 + JUnit 4 거부) 는 0.4.0 시점에
이미 `: Unit` 명시로 정정됨. 회귀 없음.

전체 `./gradlew :server:test` 18 테스트 통과, deprecation warning 0건.

### Fixed — `.gitignore` 패턴이 source 패키지까지 무시하던 문제

`.gitignore` 의 `build/` 패턴이 Gradle output 뿐 아니라 source 트리 안의
패키지 디렉토리 `server/src/main/kotlin/.../server/build/` 까지 매칭하여,
**4개 핵심 파일이 git tracking 에서 누락된 상태였다**:

- `build/ApkFinder.kt`
- `build/BuildRoutes.kt`
- `build/BuildService.kt`
- `build/GradleBuilder.kt`

이 결함이 v0.4.0 분리 시점부터 누적되어 있었기 때문에, "main 체크아웃에선
재현되지 않는다" 고 적었던 이전 CHANGELOG 기록은 잘못된 관찰이었다
(파일 자체가 commit 되지 않아 clone 후에는 컴파일 자체가 불가능).

회수 방식:

- `.gitignore`: `build/` → `**/build/` + `!**/src/**/build/**` + `!**/src/**/build/`
  로 패턴을 좁혀, Gradle output (`server/build/`, `shared/build/`, `/build/`) 만
  무시하고 source 트리 안의 build 패키지는 보존하도록 변경.
- 누락 4개 파일을 git 에 정상 등록.

검증: `git check-ignore -v` 로 `server/build` 는 ignored, `server/src/.../build/ApkFinder.kt`
는 not-ignored 확인.

### Fixed — 로그인 / 셋업 / 에러 페이지 좌측 정렬 깨짐

Admin 웹의 `.layout` 은 `grid-template-columns: 220px 1fr` 고정이라
사이드바를 렌더하지 않는 화면(로그인 / 셋업 / 에러)에서도 좌측 220px 컬럼이
빈 채로 잡혀, `.auth-card` 가 시각적으로 좌측에 치우쳐 보였다.

- `AdminTemplates.shell()` 이 `showNav=false` 일 때 `.layout` 에 `no-nav`
  modifier 클래스를 추가하도록 변경.
- `admin.css` 에 `.layout.no-nav { grid-template-columns: 1fr }` 및
  `.content { width: 100%; justify-self: center; }` 추가. max-width:1200px
  은 유지하되 grid 셀 내에서 가운데 정렬되도록 보강.

### 배포

- Docker Hub 게시: `siamakerlab/vibe-coder-server:0.4.1` / `:latest`
  (linux/amd64 + linux/arm64 멀티아키, 2026-05-23).
  `docker pull siamakerlab/vibe-coder-server:0.4.1` 로 즉시 사용 가능.
  `docker/Dockerfile`, `docker/compose.yml`, `docker/.env.example`,
  `docker/README.md`, `docker/HUB_README.md` 의 태그를 `0.4.1` 로 동기.

### Wire change

없음. ApiPath / DTO / WsFrame 변경 없이 서버 내부 수정만.

## [0.4.0] - 2026-05-21

### Added — Docker 이미지 + Admin 웹 + 통합 인증

> 서버를 도커 이미지로 패키징하고, Android 앱 외에 브라우저 admin 웹을
> 통해 초기 셋업 / 비밀번호 변경 / 디바이스 관리가 가능하도록 인증
> 모델을 통합. 설계 문서: `docs/01-plan/admin-web.md`.

**Docker PoC (`docker/`)**
- **슬림 멀티스테이지 Dockerfile**: JDK17 builder + JRE runtime, 약 600MB.
  Android SDK / Gradle 캐시 / Claude 인증은 이미지에 박지 않고 컨테이너
  부팅 후 doctor가 볼륨에 다운로드.
- **vibe-doctor** (`docker/doctor/`): 인터랙티브 셋업 도우미.
  `check` / `install` / `android` / `claude` / `mcp` 서브커맨드.
  Android SDK cmdline-tools 자동 다운로드 + sdkmanager 라이선스 자동
  수락 + manifest.yml 기반 패키지 설치.
- **entrypoint.sh**: 호스트 UID/GID 매칭(`PUID`/`PGID`), 볼륨 소유권 정리,
  `VIBECODER_ADMIN_USERNAME`/`PASSWORD` env 패스스루, Android SDK 누락
  안내. `tini` PID 1 + `gosu` 권한 강등.
- **compose.yml**: 5개 볼륨(workspace/data/android-sdk/gradle-cache/claude),
  헬스체크(`/health`), `.env` 통한 모든 옵션 외부화.
- **docker/.env.example**: 한국어 주석. UID/포트/경로/JVM/admin 부트스트랩.
- **docker/README.md**: pull → compose up → admin → doctor → 앱 로그인까지
  한국어 가이드.

**Shared (`shared/`)**
- `ApiPath`: `AUTH_LOGIN`, `AUTH_SETUP`, `AUTH_SETUP_STATUS`, `AUTH_PASSWORD`,
  `HEALTH` 추가. 기존 `AUTH_PAIR` 등은 deprecated 표기로 유지.
- DTO 추가: `LoginRequestDto`, `LoginResponseDto`, `SetupRequestDto`,
  `ChangePasswordRequestDto`, `SetupStatusDto`. `MeDto`에 `username` 필드.

**Server (`server/`)**
- **`/health`**: 인증 없는 헬스 프로브. Docker HEALTHCHECK / 모니터링용.
- **AdminUsers 테이블 + DeviceRow 확장**: `user_id`/`channel` 컬럼 추가
  (nullable + default, 자동 마이그레이션).
- **`AdminUserRepository`**: 단일 admin 행 CRUD. `count()` / `findById*` /
  `insert` / `touchLogin` / `updatePassword`.
- **`PasswordHasher`**: BCrypt cost 12. `PasswordPolicy`(영문+숫자 8자
  이상), `UsernamePolicy`(3~32자 `[A-Za-z0-9._-]`).
- **`AuthService`**: `setup` / `login` / `changePassword`. 같은 username
  10회 실패 시 15분 잠금. `dummy verify`로 timing-attack 방어.
- **`AuthRoutes`**: `/api/auth/login`, `/api/auth/setup`,
  `/api/auth/setup/status`, `/api/auth/password` 신규. `/api/auth/pair`는
  admin 존재 시 410(`pairing_deprecated`) 반환.
- **`AuthPlugin`**: `Authorization` 헤더 외에 `vibe_session` 쿠키도 토큰
  운반 경로로 인정. 같은 토큰이 두 경로 어느 쪽으로 와도 인증됨.
- **Admin 웹 (`admin/`)**: 서버 사이드 렌더 HTML. `/admin/setup` /
  `/admin/login` / `/admin` (대시보드) / `/admin/settings` /
  `/admin/password` / `/admin/devices` / `/admin/logout`. 외부 CDN 의존
  없는 다크 테마 CSS (`resources/static/admin/admin.css`).
- **`ServerMain.bootstrapAdminFromEnv()`**: 부팅 시 `VIBECODER_ADMIN_*` env
  가 있고 DB에 admin이 없으면 자동 생성. Docker compose 자동화용.
- **부팅 배너**: Admin URL 표시 추가, admin 미존재 시 경고 출력.
  레거시 페어링 코드는 admin 부재 시에만 호환용으로 노출.

**Android (`android-app/app/`)**
- **`ApiService.pair()` → `login()`**: username/password 입력으로 토큰
  발급. `LoginRequestDto`/`LoginResponseDto` 사용.
- **`AuthRepository.login()`**: 시그니처 `(serverUrl, username, password,
  deviceName)`. 기존 `pair()` 제거.
- **`KtorClient.sendWithoutRequest`**: `/api/auth/login`,
  `/api/auth/setup`, `/api/auth/setup/status`, `/health`도 인증 헤더 제외
  대상에 추가.
- **`ConnectScreen`**: 페어링 코드 입력 필드를 username + password 필드로
  교체. 비밀번호는 `PasswordVisualTransformation`. admin 셋업 안내 문구
  추가.
- **`strings.xml`**: `connect_pairing_code` → `connect_username` /
  `connect_password` / `connect_setup_hint`. `connect_button` "Pair" →
  "Sign in".

**빌드 인프라**
- **`settings.gradle.kts`**: `-PskipAndroidModule=true` 옵션 신설.
  Docker 이미지 빌드 시 :android-app:app을 제외하여 AGP/Android SDK 의존성
  없이 `:server`만 빌드.

**검증 (PoC manual)**
- `./gradlew :server:installDist` 통과, BCrypt jar 정상 포함
- 서버 실제 부팅 → `/health` `/api/auth/setup/status` `/admin` 302 →
  setup → login(wrong/correct) → bearer `/me` → cookie `/admin` 대시보드
  → `/api/auth/password` (wrong/correct) → 새 비번 로그인 → 레거시
  `/api/auth/pair` 410 모든 흐름 정상
- `./gradlew :android-app:app:compileDebugKotlin` 통과

## [0.3.0] - 2026-05-21

> v0.2.0의 마지막 deferred 2건 처리: 액션 권한 게이트(FR-11-b) + MCP
> per-tool enumeration. 채팅 콘솔이 host capability 상태를 보고 비가용
> 액션을 자동 비활성화하고, MCP 도구는 `.mcp.json`에 직접 적은 만큼 즉시
> chip으로 노출된다.

### Added — capability gate (FR-11-b)
- **Shared**: `ProjectActionDto`의 모든 sealed 변형에 `requires: List<String>`
  필드 추가. `ActionTreeDto.capabilities: Map<String, Boolean>` 신설.
  `CapabilityKey` 상수 객체 — `BUILD`, `GIT`, `CLAUDE_SESSION`,
  `mcp(server)`.
- **Server**: `actions/CapabilityService` 신설. EnvDiagnostics를 30초 TTL로
  캐시하여 `git` / `claude_session` 상태 계산, `.mcp.json`의 서버 목록을
  `mcp:<name>=true`로 매핑. `build`는 등록된 프로젝트라면 true.
- **Server**: `ProjectActionRoutes.GET /actions`가 응답에 capabilities
  포함. `ProjectActionRegistry.listForProject(projectId, capabilities)`로
  시그니처 확장.
- **Server manifests**: 기본 4개 manifest 갱신 — `build:debug` →
  `requires:["build"]`, `git:*` → `requires:["git"]`, `slash:*` →
  `requires:["claude_session"]`. 정적 텍스트만 다루는 prompt/snippet은
  `requires:[]` 유지.
- **Android**: `QuickActionChips`가 `tree.capabilities` × `action.requires`를
  보고 비가용 chip을 disabled로 렌더. 비활성 chip을 탭/롱탭하면 토스트로
  사유 표시(`cap_unavailable_*` 문자열 키). `strings.xml`에 5개 capability
  사유 메시지 추가.

### Added — MCP per-tool enumeration
- **Server**: `.mcp.json`의 서버 entry에 `tools` 배열을 선언하면 per-tool
  chip 생성. 형식:
  ```json
  {"mcpServers":{"bkit":{
    "command":"...","args":[...],
    "tools":[
      {"name":"bkit_pdca_status","label":"PDCA Status","icon":"Activity"},
      {"name":"bkit_pdca_history"}
    ]}}}
  ```
  `label`/`icon` 생략 시 `name`/"Plug"로 기본화. `argsTemplate`는 JSON
  그대로 통과. `tools`가 없으면 기존 per-server fallback chip 유지.
- **Server**: 자동 생성된 InvokeMcpTool은 `requires:["mcp:<server>"]`를 갖고,
  capability map에서 해당 키가 true일 때만 enabled.
- **Server**: `ProjectActionRegistry.mcpServerNames(projectId)`를 외부에
  노출하여 CapabilityService가 활용.

### Versions
- `versionName` `0.2.2` → `0.3.0` (MINOR: 액션 시스템 신규 기능 — 권한
  게이트 + per-tool 매니페스트 확장).
- `versionCode` `260521003` → `260521004`.
- `server.yml` `server.version` `0.2.2` → `0.3.0`.

## [0.2.2] - 2026-05-21

> v0.2.0 deferred 항목 중 빌드 산출물 housekeeping 2건 (F-1, F-2) 처리.
> 사용자 가시 동작 변경 없음.

### Added
- **Server**: `ArtifactService.pruneOldArtifacts(projectId, keepCount)` —
  프로젝트당 newest-first로 정렬해 `keepCount` 초과분을 자동 삭제. 각 항목별로
  (1) artifact 디렉토리 통째 삭제 (APK + metadata.json,
  `ensureUnderWorkspace` 검증 후), (2) `Builds.artifactId` 참조 null로
  해제(build history는 보존), (3) `Artifacts` row delete. `keepCount <= 0`은
  "정리 안 함"으로 처리. 항목별 실패는 KotlinLogging WARN으로 격리.
- **Server**: `storeDebugApk` 직후 `pruneOldArtifacts(projectId,
  config.workspace.artifactKeepCount)` 자동 호출 (기본 20개 보관).
- **Repo**: `ArtifactRepository.listForProjectAll(projectId)` (limit 없음),
  `ArtifactRepository.delete(artifactId): Int`,
  `BuildRepository.detachArtifact(artifactId)` 신설.

### Changed
- **Build infra**: `gradle/wrapper/gradle-wrapper.jar`를 Gradle 9.5.1 정본
  배포본에서 ship한 wrapper로 재생성 (`./gradlew wrapper
  --gradle-version 9.5.1 --distribution-type bin`). jar 48966 → 48462 bytes.
  SHA-256 변경. `gradle-wrapper.properties`에 9.5.1 기본값 `retries=0` /
  `retryBackOffMs=500` 자동 추가. 분석 보고서 F-1 항목 해소.
- **Server**: `ArtifactService` 시그니처 확장 — 의존성에 `config: ServerConfig`,
  `buildRepo: BuildRepository` 추가. `ServerMain` 와이어링 동기.

### Versions
- `versionName` `0.2.1` → `0.2.2` (PATCH: 자동 정리/빌드 인프라).
- `versionCode` `260521002` → `260521003`.
- `server.yml` `server.version` `0.2.1` → `0.2.2`.

## [0.2.1] - 2026-05-21

> v0.2.0의 deferred 항목 중 deprecated 엔드포인트 제거. one-shot Claude task
> 파이프라인 잔재를 모두 정리하고 콘솔 단일 경로로 통합.

### Removed
- **Server**: `POST /api/projects/{id}/claude/tasks` (deprecated 핸들러),
  `GET /api/projects/{id}/claude/tasks`, `GET .../claude/tasks/{taskId}`,
  `POST .../claude/tasks/{taskId}/cancel` 4개 엔드포인트.
- **Server**: WebSocket `/ws/projects/{id}/tasks/{taskId}/logs` 엔드포인트
  (콘솔 WS 및 빌드 WS만 남김).
- **Server 파일**: `claude/ClaudeRoutes.kt`, `claude/ClaudeRunner.kt`,
  `claude/ClaudePromptBuilder.kt`, `tasks/TaskRoutes.kt`,
  `repo/TaskRepository.kt`.
- **DB**: `Tasks` 테이블 정의 삭제 (`db/Schemas.kt`). 신규 서버는 이 테이블을
  더 이상 생성하지 않음. 기존 DB 파일은 그대로 두면 됨 (테이블만 unused
  상태로 남음).
- **Shared**: `ClaudeTaskRequestDto`, `TaskDto`, `TaskType` 제거. `TaskStatus`
  enum은 BuildRow가 사용하므로 보존하되 KDoc에 build 전용임을 명시.
- **Shared**: `ApiPath.claudeTasks/claudeTask/claudeTaskCancel/wsTaskLogs`
  4개 path 상수 제거.
- **Android**: `ApiService.submitClaudeTask/listClaudeTasks/cancelTask` 함수
  제거. `Repositories.kt` `TaskRepository` 클래스 통째로 제거.
  `WsClient.streamTaskLogs` 제거.
- **Android Nav**: `Routes.LOG` (`projects/{id}/logs/{kind}/{taskId}`)
  → `Routes.BUILD_LOG` (`projects/{id}/builds/{buildId}/logs`)로 단순화.
  `ARG_KIND`/`ARG_TASK_ID` 제거, `ARG_BUILD_ID` 신설.
  `Routes.log(id, kind, taskId)` → `Routes.buildLog(id, buildId)`.

### Changed
- **Android `LogScreen`**: build-only로 단순화. ViewModel은 `WsClient` +
  `BuildRepository`만 주입받고 `kind` 분기 제거.
- **Server `StatusService`**: `taskRepo` → `buildRepo` 의존성으로 교체.
  `runningTaskCount`는 이제 `Builds` 테이블의 RUNNING+PENDING 개수.
  `BuildRepository.countRunning()` 메서드 신설.
- **Server `ServerContext`**: `taskRepo`, `claude: ClaudeRunner` 필드 제거.
- **Server `tasks/LogWriter.kt`**: KDoc에서 ClaudeRunner 언급 제거.

### Versions
- `versionName` `0.2.0` → `0.2.1` (PATCH: deprecated 코드 정리, 동작 변경
  없음).
- `versionCode` `260521001` → `260521002`.
- `server.yml` `server.version` `0.2.0` → `0.2.1`.

## [0.2.0] - 2026-05-21

> project-claude-console — 채팅형 Claude Console + 영속 세션 + 액션 레지스트리.
> PDCA 사이클 종료 Match Rate 98% (archived at `docs/archive/2026-05/project-claude-console/`).

### Added — Server (persistent Claude console)
- `claude/ClaudeSessionManager`: 프로젝트당 1개 영속 `claude --print --output-format stream-json --input-format stream-json [--resume <id>]` 자식 프로세스. stdin/stdout 파이프 + per-project stdin mutex + idle 30분 reaper + crash/resume-failure 감지.
- `claude/ClaudeStreamParser` + `claude/ClaudeEvent`: stdout 라인 → sealed `ClaudeEvent`(SessionStarted / AssistantMessage / ToolUse / ToolResult / Error / Done / Unknown).
- `claude/ConsoleRoutes`: `POST /api/projects/{id}/claude/console/prompt`, `POST .../console/new`, `GET .../claude/status`.
- `claude/ClaudeStatusService`: `claude /status` 60s 캐시 (slash command sidecar fallback 포함).
- `ws/LogHub` 확장: 콘솔 토픽 ring buffer 200건 + 단조 증가 seq + `?since=<seq>` replay.
- `ws/WsRoutes` 확장: `/ws/projects/{id}/console/logs?since=` 양방향 (client→server: auth/user_prompt/action_invoke, server→client: console_* sealed frames).
- `actions/` 패키지: sealed `ProjectAction` (SendPrompt / InvokeMcpTool / RunServerAction / OpenPalette / SnippetInsert / InvokeClaudeSlashCommand) + `ProjectActionRegistry` (resources + workspace `actions.user.json` 병합, MCP `.mcp.json` 자동 발견, mtime 10s 핫리로드) + `ServerActionHandler` (whitelist: `build.debug`, `git.{status,diff,log}`, slash `{status,cost,model,clear,memory,plan,compact}`) + routes (`GET /actions`, `POST /actions/invoke`, 4KB params cap).
- `resources/actions/`: 기본 manifest `build.json`, `git.json`, `claude.json`, `snippets.json`.
- `projects/KeystoreGenerator`: 프로젝트 등록 시 디버그/릴리즈 동일 키스토어 자동 생성.
- `error/StatusPagesPlugin`: 표준 `ApiErrorDto` 응답 코드 확장 (`action_not_allowed`, `claude_send_failed`, `params_too_large`, `prompt_too_large` 등).

### Added — Android (chat console)
- `ui/console/`: `ProjectConsoleScreen` (TopAppBar + LazyColumn 대화 + Surface 입력바), `ConsoleViewModel`, `messages/` 카드 6종 (AssistantBubble/ToolUse/ToolResult/Error/System/Unknown), `input/PromptInputBar` + `VoiceButton` (SpeechRecognizer 한/영) + `QuickActionChips` (카테고리 탭 + LazyRow), `scroll/AutoScrollState` (스크롤 잠금 + "↓ Jump to latest"), `status/StatusPanel` (collapsible).
- `data/remote/ConsoleWsClient` + `data/repository/ConsoleRepository`: WS 양방향 + `?since` 재접속 replay.
- `AndroidManifest.xml`: `RECORD_AUDIO` 권한 선언 + `<queries>` SpeechRecognizer.
- `strings.xml`: 콘솔 UI 약 30개 키 신규 (하드코딩 제거).

### Added — Shared
- `dto/ConsoleDtos.kt`: `PromptRequestDto`, `PromptAcceptedDto`, `ClaudeStatusDto`.
- `dto/ProjectActionDto.kt`: 액션 트리 wire DTO (sealed `ProjectActionDto` 6 변형 + `ActionCategoryDto` + `ActionTreeDto` + `ActionInvokeRequestDto`).
- `ws/WsFrame`: `Console*` 서브타입 10종 (`SessionStarted`/`Assistant`/`ToolUse`/`ToolResult`/`Error`/`Done`/`Unknown`/`System`/`ReplayBegin`/`ReplayEnd`) + client→server `UserPrompt`/`ActionInvoke`.
- `ApiPath`: console + actions 엔드포인트 상수.

### Changed
- `claude/ClaudeRoutes.POST /api/projects/{id}/claude/tasks` → **deprecated** (one-shot 모드, 1 사이클 호환 유지). 신규 클라이언트는 console 엔드포인트 사용.
- `WorkspacePath` + `PathSafety`: `.vibecoder` 메타 경로를 `<root>/.vibecoder/<projectId>/`로 통일 (이전: `<root>/<projectId>/.vibecoder/`).
- `server.yml`: `workspace.root` 기본값 `./vibe-coder-server-data/workspace` → `./workspace`; `security.restrictToWorkspace` 옵션 제거 (`PathSafety`가 항상 강제하므로 잉여).
- Repositories(6개): `Clock` 주입으로 결정성 향상; `ProjectService`는 키스토어 생성 흐름 통합.
- Android nav `Routes`: `ProjectDetail` 라우트가 `ProjectConsoleScreen`을 가리키도록 변경; `ClaudePrompt` 라우트는 console로 흡수.
- `ProjectRegisterScreen`: 키스토어 자동 생성 안내 + 폼 확장.
- `MainActivity`: 음성 권한 launcher + 콘솔 진입 흐름.

### Removed
- `ui/claude/ClaudePromptScreen.kt`, `ui/projects/ProjectDetailScreen.kt` (콘솔로 흡수). 라우트도 함께 제거.

### Versions
- `versionName` `0.1.0` → `0.2.0` (MINOR: 영속 콘솔/액션 레지스트리/음성 입력 — 하위호환 신규 기능, 사용자 워크플로 확장).
- `versionCode` `260517001` → `260521001` (yymmddrrr).
- `server.yml` `server.version` `0.1.0` → `0.2.0` (Plan 문서와 동기).

### Deferred (다음 사이클 후보)
- `ProjectActionDto.requires` 권한 게이트 + Android 비활성 chip + 사유 tooltip (FR-11-b).
- MCP per-tool enumeration (현재 per-server 1 chip만; JSON Schema 기반 폼 후속).
- Deprecated `/api/projects/{id}/claude/tasks` 제거 (1 사이클 유예 후).
- `gradle-wrapper.jar` 바이너리 생성 (`gradle wrapper --gradle-version 9.5.1`).
- `artifactKeepCount` 자동 정리 (현재 수동 DELETE artifact API만).

## [0.1.0] - 2026-05-17

### Added
- Initial monorepo skeleton: `:shared`, `:server`, `:android-app:app`.
- Gradle 9.5.1 wrapper + version catalog (`gradle/libs.versions.toml`).
- Build matrix per global `CLAUDE.md` §2-2-1: Gradle 9.5.1 / AGP 9.2.0 / Kotlin 2.2.20 / Compose BOM 2026.05.00 / Hilt 2.59.2 / JDK 21.
- PDCA Plan and Design documents under `docs/01-plan/` and `docs/02-design/`.
- `shared` module: 13 `@Serializable` DTOs, API path constants, WebSocket frame sealed class.
- `server` module: Ktor 3.x + Exposed + SQLite + YAML config + pairing-code auth + WebSocket log streaming + Claude/Gradle/Git process execution + APK artifact management with SHA-256.
- `android-app` module: Jetpack Compose + Material 3 + Hilt + Ktor Client + DataStore + 12 screens (Connect/Dashboard/Environment/ProjectList/Register/Detail/ClaudePrompt/Log/Build/Artifact/Git/Files) + APK installer via FileProvider.

### Notes
- `android.disallowKotlinSourceSets=false` is required for AGP 9 + KSP2 (workaround until KSP migrates to `android.sourceSets`).
- `-Xannotation-default-target=param-property` is applied to Android module (Kotlin 2.2 KT-73255 forward-compat for Hilt).

### Changed during first build (2026-05-17)

Adjustments made while producing the first runnable debug APK:

- **Compose Compiler plugin**: Kotlin 2.0+ moved Compose Compiler into a separate Gradle plugin. Added `org.jetbrains.kotlin.plugin.compose` (alias `libs.plugins.kotlin.compose`) to root `build.gradle.kts` and to `android-app/app/build.gradle.kts`.
- **JDK toolchain 21 → 17**: Local environment ships JDK 17 only; Foojay auto-download did not transparently honour `jvmToolchain(21)` for the AGP `hiltJavaCompileDebug` task. Downgraded `jvmToolchain` and `sourceCompatibility` / `targetCompatibility` in `shared/`, `server/`, and `android-app/app/` to 17. AGP 9 + Kotlin 2.2 remain fully supported on JDK 17.
- **Ktor 3.1.2 API alignment**:
  - `KtorClient.kt` — `sendWithoutRequest` block now reads the URL via `request.url.pathSegments` (URLBuilder `encodedPath` was not resolvable in the lambda's inference scope).
  - `DownloadService.kt` — replaced `ByteReadChannel.readAvailable` (whose ext-fn location moved between Ktor minors) with the stable `bodyAsChannel().toInputStream()` JVM helper.
  - `WsClient.kt` — added explicit `io.ktor.websocket.close` import for `DefaultClientWebSocketSession.close()`.
- **Foojay toolchain resolver**: Added `org.gradle.toolchains.foojay-resolver-convention 0.10.0` to `settings.gradle.kts` and `org.gradle.java.installations.auto-download=true` to `gradle.properties` so a future move back to JDK 21 will auto-provision the toolchain.
- **First APK**: `android-app/app/build/outputs/apk/debug/app-debug.apk` (~21 MB, versionCode `260517001`, versionName `0.1.0`, applicationId `com.siamakerlab.vibecoder.console.debug`).
