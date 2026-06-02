package com.siamakerlab.vibecoder.server.env

/**
 * Verified/community MCP server catalog — v0.8.0.
 *
 * 사용자가 빌드환경 > MCP 페이지에서 체크박스로 선택해 설치하는 카탈로그.
 * 50+ 항목으로 시작 (확장 자유). 추천 항목에는 [recommended] = true.
 *
 * **Trust tier**:
 *  - VERIFIED — 공식 modelcontextprotocol(slash)star 또는 1st-party 벤더가 운영하는 npm 패키지.
 *  - COMMUNITY — 인기 있는 3rd party. 패키지명/배포 상태 변동 가능.
 *  - EXPERIMENTAL — package name 확정 필요 / 사용자 직접 수정 권장.
 *
 * 설치 메커니즘 — `npm install -g <package>`. v0.7.0 부터 vibe 사용자의 npm
 * prefix 가 /home/vibe/.local (bind mount) 라 이미지 업그레이드 후에도 보존된다.
 * 등록 — `~/.claude/.mcp.json` 의 `mcpServers` 아래에 entry 추가.
 *
 * 카테고리는 화면에서 그룹 표시 용도. 같은 ID 가 중복되지 않게 주의.
 */
object McpCatalog {

    /** UI 가 그룹 헤더로 사용. 순서가 표시 순서. */
    enum class Category(val label: String) {
        DEV_TOOLS("개발 도구 (filesystem / git / fetch)"),
        GIT_HOSTING("Git 호스팅 (GitHub / GitLab / Gitea / Bitbucket)"),
        DATABASE("데이터베이스 (Postgres / SQLite / MongoDB / Redis)"),
        SEARCH("검색 / 웹 (Brave / Tavily / Perplexity / Firecrawl)"),
        BROWSER("브라우저 자동화 (Puppeteer / Playwright)"),
        PRODUCTIVITY("생산성 (Notion / Linear / Jira / Slack / Discord)"),
        CLOUD("클라우드 (AWS / Cloudflare / Vercel / Supabase / Firebase)"),
        COMMS("커뮤니케이션 / 알림 (Sendgrid / Twilio / Telegram)"),
        APP_PUBLISH("앱 배포 (Google Play / App Store) — Experimental"),
        AI_ASSIST("AI 보조 (memory / sequential thinking / time / context)"),
    }

    enum class Trust { VERIFIED, COMMUNITY, EXPERIMENTAL }

    /**
     * 설정값 (TOKEN / URL / DSN 등) 한 필드.
     * UI 가 input 으로 렌더링하고 사용자가 입력. 비밀값은 type=password.
     */
    data class ConfigField(
        val key: String,
        val label: String,
        val placeholder: String? = null,
        val isSecret: Boolean = false,
        val required: Boolean = true,
        val help: String? = null,
        /**
         * v0.11.0 — true 면 텍스트 입력 대신 파일 업로드 UI 렌더링.
         * 업로드된 파일은 /home/vibe/.config/mcp-secrets/<mcp-id>-<key>.<ext>
         * (0600) 에 저장되고, 그 절대 경로가 .mcp.json 의 env/args 값으로 주입됨.
         * Service Account JSON / OAuth client.json / Apple .p8 같은 secret 파일 대응.
         */
        val isFile: Boolean = false,
        /** isFile=true 일 때 UI 의 accept 속성 (예: ".json,application/json"). */
        val acceptMime: String? = null,
    )

    data class McpEntry(
        val id: String,
        val displayName: String,
        val pkg: String,
        val description: String,
        val category: Category,
        val trust: Trust,
        val recommended: Boolean = false,
        /**
         * v1.37.0 — 무인증·무설정(zero-config)이라 설치 즉시 사용 가능한 "기본 설치 대상".
         * MCP 카탈로그에서 체크박스가 기본 선택되어, 사용자가 [설치] 한 번이면 바로 적용.
         * fetch / memory / sequential-thinking 처럼 API 키·필수 config 가 없는 항목만 true.
         */
        val defaultInstall: Boolean = false,
        val homepage: String? = null,
        val configFields: List<ConfigField> = emptyList(),
        /** Claude 의 .mcp.json 에 들어가는 args (env 는 별도). 기본은 `npx -y <pkg>` 패턴. */
        val argsTemplate: List<String> = listOf("-y", "@PKG@"),
        /** Claude 가 spawn 할 command — `npx` 기본. binary 가 따로 PATH 에 있으면 override. */
        val command: String = "npx",
        /**
         * v0.12.1 — true 면 카탈로그에 노출하되 "준비중" 라벨 + 설치 비활성화.
         * 브라우저 OAuth 콜백 흐름이 필수라 PAT/키파일만으로는 동작 불가한 MCP.
         * 예: Google Drive (OAuth client.json 받은 후 첫 호출에서 브라우저 인증 필요).
         */
        val comingSoon: Boolean = false,
        /**
         * v1.68.0 — npm 패키지가 아니라 이미지에 박힌 바이너리(`command` 가 PATH 의 실행파일)로
         * 동작하는 MCP. true 면 설치 시 `npm install -g` 를 건너뛰고 user-scope 등록만 한다.
         * "설치됨" 판정도 npm 대신 PATH 의 바이너리 존재로 한다.
         * 예: 공식 Gitea MCP(`gitea-mcp`, Go 정적 바이너리 — Dockerfile 에서 릴리스 다운로드).
         */
        val binaryInstall: Boolean = false,
    )

    /** 전체 카탈로그 — 항목 추가 시 ID 중복 주의. */
    val all: List<McpEntry> = buildList {
        // ─── DEV_TOOLS — Anthropic 공식 ────────────────────────────
        add(McpEntry(
            id = "filesystem",
            displayName = "Filesystem",
            pkg = "@modelcontextprotocol/server-filesystem",
            description = "워크스페이스 내 파일 read/write/list. Claude 가 가장 자주 쓰는 기본 MCP.",
            category = Category.DEV_TOOLS, trust = Trust.VERIFIED, recommended = true,
            homepage = "https://github.com/modelcontextprotocol/servers/tree/main/src/filesystem",
            configFields = listOf(
                ConfigField("root", "허용 디렉토리 (절대 경로)", "/workspace",
                    isSecret = false, required = true,
                    help = "vibe-coder 컨테이너 안 워크스페이스 경로. 기본 /workspace 권장."),
            ),
            argsTemplate = listOf("-y", "@PKG@", "@CONFIG:root@"),
        ))
        add(McpEntry(
            id = "git",
            displayName = "Git",
            pkg = "@modelcontextprotocol/server-git",
            description = "로컬 git 리포의 log/diff/status/show 등 readonly 조회.",
            category = Category.DEV_TOOLS, trust = Trust.VERIFIED, recommended = true,
            homepage = "https://github.com/modelcontextprotocol/servers/tree/main/src/git",
            configFields = listOf(
                ConfigField("repository", "리포지토리 경로", "/workspace",
                    help = "프로젝트 루트 또는 워크스페이스."),
            ),
            argsTemplate = listOf("-y", "@PKG@", "--repository", "@CONFIG:repository@"),
        ))
        add(McpEntry(
            id = "memory",
            displayName = "Memory",
            pkg = "@modelcontextprotocol/server-memory",
            description = "세션 간 지식 그래프 저장 (entities/relations). 장기 컨텍스트 보조.",
            category = Category.AI_ASSIST, trust = Trust.VERIFIED, recommended = true, defaultInstall = true,
        ))
        add(McpEntry(
            id = "sequentialthinking",
            displayName = "Sequential Thinking",
            pkg = "@modelcontextprotocol/server-sequential-thinking",
            description = "복잡한 문제를 단계별로 분해해 추론. 어려운 알고리즘 작업에 도움.",
            category = Category.AI_ASSIST, trust = Trust.VERIFIED, recommended = true, defaultInstall = true,
        ))
        add(McpEntry(
            id = "time",
            displayName = "Time",
            pkg = "@modelcontextprotocol/server-time",
            description = "타임존 변환 + 현재 시각. 컨테이너의 TZ 환경 차이 보정에 유용.",
            category = Category.AI_ASSIST, trust = Trust.VERIFIED,
        ))
        add(McpEntry(
            id = "everything",
            displayName = "Everything (Demo)",
            pkg = "@modelcontextprotocol/server-everything",
            description = "MCP 의 모든 기능을 시연하는 데모 서버. 학습/디버그 용도.",
            category = Category.DEV_TOOLS, trust = Trust.VERIFIED,
        ))

        // ─── GIT_HOSTING ──────────────────────────────────────────
        add(McpEntry(
            id = "github",
            displayName = "GitHub",
            pkg = "@modelcontextprotocol/server-github",
            description = "GitHub 이슈/PR/리포 조작. issue 생성, PR review, file 검색 등.",
            category = Category.GIT_HOSTING, trust = Trust.VERIFIED, recommended = true,
            configFields = listOf(
                ConfigField("GITHUB_PERSONAL_ACCESS_TOKEN", "GitHub PAT", "ghp_...",
                    isSecret = true,
                    help = "Settings > Developer settings > Personal access tokens. 최소 권한 repo + read:user."),
            ),
        ))
        add(McpEntry(
            id = "gitlab",
            displayName = "GitLab",
            pkg = "@modelcontextprotocol/server-gitlab",
            description = "GitLab issues/MRs/projects API. self-hosted 인스턴스 지원.",
            category = Category.GIT_HOSTING, trust = Trust.VERIFIED,
            configFields = listOf(
                ConfigField("GITLAB_PERSONAL_ACCESS_TOKEN", "GitLab PAT", "glpat-...",
                    isSecret = true,
                    help = "User Settings > Access Tokens. api + read_repository 권장."),
                ConfigField("GITLAB_API_URL", "GitLab API URL", "https://gitlab.com/api/v4",
                    isSecret = false, required = false,
                    help = "self-hosted 인스턴스면 변경."),
            ),
        ))
        add(McpEntry(
            // v1.68.0 — 공식 Gitea MCP Server(gitea.com/gitea/gitea-mcp)로 교체.
            //  이전 community npm 패키지(`@boringstudio_org/gitea-mcp`)가 오작동.
            //  Go 정적 바이너리라 npm 이 아닌 이미지 번들 바이너리(`gitea-mcp -t stdio`)로 실행.
            //  env 도 공식 규약: GITEA_HOST(루트 URL, /api/v1 아님) + GITEA_ACCESS_TOKEN.
            id = "gitea",
            displayName = "Gitea",
            pkg = "gitea.com/gitea/gitea-mcp",
            homepage = "https://gitea.com/gitea/gitea-mcp",
            description = "공식 Gitea MCP Server (gitea/gitea-mcp). Gitea / Forgejo 인스턴스의 issues/PRs/repos/releases 등. self-hosted git 환경.",
            category = Category.GIT_HOSTING, trust = Trust.VERIFIED,
            // v1.68.1 — 절대경로로 고정. 구 npm 패키지(@boringstudio_org/gitea-mcp)가
            //  `/home/vibe/.local/bin/gitea-mcp`(→ index.js) 심볼릭을 남겼고, PATH 가
            //  `.local/bin` 을 먼저 보므로 `gitea-mcp` 만 쓰면 공식 바이너리가 가려진다.
            //  이미지의 공식 바이너리(`/usr/local/bin/gitea-mcp`)를 결정적으로 사용.
            command = "/usr/local/bin/gitea-mcp",
            argsTemplate = listOf("-t", "stdio"),
            binaryInstall = true,
            configFields = listOf(
                ConfigField("GITEA_HOST", "Gitea Host URL", "https://gitea.example.com",
                    help = "인스턴스 루트 URL (예: https://gitea.wody.kr). `/api/v1` 를 붙이지 않습니다."),
                ConfigField("GITEA_ACCESS_TOKEN", "Gitea PAT", "...",
                    isSecret = true,
                    help = "User Settings > Applications > Generate New Token."),
                ConfigField("GITEA_INSECURE", "TLS 인증서 무시", "false",
                    required = false,
                    help = "self-signed 인증서면 `true`. 기본 false."),
            ),
        ))
        add(McpEntry(
            id = "bitbucket",
            displayName = "Bitbucket",
            pkg = "mcp-server-bitbucket",
            description = "Bitbucket Cloud + Server 의 repo/issues/PR API.",
            category = Category.GIT_HOSTING, trust = Trust.COMMUNITY,
            configFields = listOf(
                ConfigField("BITBUCKET_USERNAME", "Bitbucket username", "myuser"),
                ConfigField("BITBUCKET_APP_PASSWORD", "App Password", "...",
                    isSecret = true,
                    help = "Personal settings > App passwords. Repository read/write."),
            ),
        ))
        add(McpEntry(
            id = "azure-devops",
            displayName = "Azure DevOps",
            pkg = "@azure-devops/mcp-server-azure-devops",
            description = "Azure DevOps Repos / Boards / Pipelines. PAT 인증.",
            category = Category.GIT_HOSTING, trust = Trust.EXPERIMENTAL,
            configFields = listOf(
                ConfigField("AZURE_DEVOPS_PAT", "Azure DevOps PAT", "...", isSecret = true),
                ConfigField("AZURE_DEVOPS_ORG", "Organization", "myorg"),
            ),
        ))

        // ─── DATABASE ──────────────────────────────────────────────
        add(McpEntry(
            id = "sqlite",
            displayName = "SQLite",
            pkg = "@modelcontextprotocol/server-sqlite",
            description = "로컬 SQLite DB 의 read/write. vibe-coder 의 server.db 분석에도 사용 가능.",
            category = Category.DATABASE, trust = Trust.VERIFIED, recommended = true,
            configFields = listOf(
                ConfigField("db", "DB 파일 경로", "/workspace/.vibecoder/vibecoder.db"),
            ),
            argsTemplate = listOf("-y", "@PKG@", "--db-path", "@CONFIG:db@"),
        ))
        add(McpEntry(
            id = "postgres",
            displayName = "PostgreSQL",
            pkg = "@modelcontextprotocol/server-postgres",
            description = "Postgres 데이터베이스 read/write. 스키마 introspection 지원.",
            category = Category.DATABASE, trust = Trust.VERIFIED,
            configFields = listOf(
                ConfigField("DATABASE_URL", "Postgres DSN",
                    "postgresql://user:pw@host:5432/dbname", isSecret = true),
            ),
            argsTemplate = listOf("-y", "@PKG@", "@CONFIG:DATABASE_URL@"),
        ))
        add(McpEntry(
            id = "mysql",
            displayName = "MySQL",
            pkg = "mcp-server-mysql",
            description = "MySQL/MariaDB 쿼리. 호환 layer 로 일부 변경.",
            category = Category.DATABASE, trust = Trust.COMMUNITY,
            configFields = listOf(
                ConfigField("MYSQL_HOST", "Host", "localhost"),
                ConfigField("MYSQL_PORT", "Port", "3306", required = false),
                ConfigField("MYSQL_USER", "User", "root"),
                ConfigField("MYSQL_PASSWORD", "Password", isSecret = true),
                ConfigField("MYSQL_DATABASE", "Database", "mydb"),
            ),
        ))
        add(McpEntry(
            id = "mongodb",
            displayName = "MongoDB",
            pkg = "mongodb-mcp-server",
            description = "MongoDB find/insert/aggregate. Atlas + self-hosted 지원.",
            category = Category.DATABASE, trust = Trust.COMMUNITY,
            configFields = listOf(
                ConfigField("MONGODB_URI", "Connection URI",
                    "mongodb://user:pw@host:27017", isSecret = true),
            ),
        ))
        add(McpEntry(
            id = "redis",
            displayName = "Redis",
            pkg = "@modelcontextprotocol/server-redis",
            description = "Redis key/value GET/SET/DEL. 디버그용으로 유용.",
            category = Category.DATABASE, trust = Trust.VERIFIED,
            configFields = listOf(
                ConfigField("REDIS_URL", "Redis URL", "redis://localhost:6379"),
            ),
            argsTemplate = listOf("-y", "@PKG@", "@CONFIG:REDIS_URL@"),
        ))
        add(McpEntry(
            id = "elasticsearch",
            displayName = "Elasticsearch",
            pkg = "mcp-server-elasticsearch",
            description = "Elasticsearch index/query. logs/analytics 검색.",
            category = Category.DATABASE, trust = Trust.COMMUNITY,
            configFields = listOf(
                ConfigField("ELASTIC_URL", "Cluster URL", "http://localhost:9200"),
                ConfigField("ELASTIC_API_KEY", "API Key", isSecret = true, required = false),
            ),
        ))
        add(McpEntry(
            id = "supabase",
            displayName = "Supabase",
            pkg = "@supabase/mcp-server-supabase",
            description = "Supabase project DB + Auth + Storage 접근. Postgres + REST.",
            category = Category.DATABASE, trust = Trust.COMMUNITY,
            configFields = listOf(
                ConfigField("SUPABASE_URL", "Project URL", "https://xxx.supabase.co"),
                ConfigField("SUPABASE_SERVICE_ROLE_KEY", "Service Role Key", isSecret = true),
            ),
        ))
        add(McpEntry(
            id = "firebase",
            displayName = "Firebase",
            pkg = "mcp-server-firebase",
            description = "Firestore + RTDB + Auth read/write.",
            category = Category.DATABASE, trust = Trust.EXPERIMENTAL,
            configFields = listOf(
                ConfigField("GOOGLE_APPLICATION_CREDENTIALS", "Service Account JSON 파일",
                    isFile = true, acceptMime = ".json,application/json",
                    help = "GCP Console > IAM > Service Accounts > Keys 에서 JSON 다운로드."),
            ),
        ))

        // ─── SEARCH / WEB ─────────────────────────────────────────
        add(McpEntry(
            id = "brave-search",
            displayName = "Brave Search",
            pkg = "@modelcontextprotocol/server-brave-search",
            description = "Brave 검색 API — 광고/추적 적은 웹 검색. 일반 검색에 권장.",
            category = Category.SEARCH, trust = Trust.VERIFIED, recommended = true,
            configFields = listOf(
                ConfigField("BRAVE_API_KEY", "Brave API Key", "BSA...",
                    isSecret = true,
                    help = "https://api.search.brave.com/app/keys 에서 무료 발급."),
            ),
        ))
        add(McpEntry(
            id = "tavily",
            displayName = "Tavily Search",
            pkg = "tavily-mcp",
            description = "AI 친화적 검색 API. context-augmented 검색 결과.",
            category = Category.SEARCH, trust = Trust.COMMUNITY,
            configFields = listOf(
                ConfigField("TAVILY_API_KEY", "Tavily API Key", isSecret = true,
                    help = "https://tavily.com 에서 무료 plan 가능."),
            ),
        ))
        add(McpEntry(
            id = "perplexity",
            displayName = "Perplexity Ask",
            pkg = "@perplexity/mcp-server",
            description = "Perplexity 의 AI 검색 API. 인용 포함된 응답.",
            category = Category.SEARCH, trust = Trust.COMMUNITY,
            configFields = listOf(
                ConfigField("PERPLEXITY_API_KEY", "Perplexity API Key", isSecret = true),
            ),
        ))
        add(McpEntry(
            id = "firecrawl",
            displayName = "Firecrawl",
            pkg = "firecrawl-mcp",
            description = "URL → Markdown 변환 + 사이트 전체 crawl. 문서 수집에 강력.",
            category = Category.SEARCH, trust = Trust.COMMUNITY,
            configFields = listOf(
                ConfigField("FIRECRAWL_API_KEY", "Firecrawl API Key", isSecret = true,
                    help = "https://firecrawl.dev"),
            ),
        ))
        add(McpEntry(
            id = "google-maps",
            displayName = "Google Maps",
            pkg = "@modelcontextprotocol/server-google-maps",
            description = "Geocoding + 경로 + 장소 검색. 위치 기반 작업에 유용.",
            category = Category.SEARCH, trust = Trust.VERIFIED,
            configFields = listOf(
                ConfigField("GOOGLE_MAPS_API_KEY", "Google Maps API Key", isSecret = true),
            ),
        ))
        add(McpEntry(
            id = "context7",
            displayName = "Context7",
            pkg = "@upstash/context7-mcp",
            description = "최신 라이브러리/프레임워크 docs 검색. \"use context7\" 키워드로 호출.",
            category = Category.SEARCH, trust = Trust.COMMUNITY, recommended = true, defaultInstall = true,
            homepage = "https://context7.com",
        ))

        // ─── BROWSER ──────────────────────────────────────────────
        add(McpEntry(
            id = "playwright",
            displayName = "Playwright",
            pkg = "@playwright/mcp",
            description = "Microsoft Playwright 기반 브라우저 자동화 + screenshot. Chromium 자동 설치.",
            category = Category.BROWSER, trust = Trust.VERIFIED, recommended = true, defaultInstall = true,
            homepage = "https://github.com/microsoft/playwright-mcp",
        ))
        add(McpEntry(
            id = "puppeteer",
            displayName = "Puppeteer",
            pkg = "@modelcontextprotocol/server-puppeteer",
            description = "Anthropic 공식 Puppeteer wrapper. console / network / screenshot.",
            category = Category.BROWSER, trust = Trust.VERIFIED,
        ))

        // ─── PRODUCTIVITY ─────────────────────────────────────────
        add(McpEntry(
            id = "notion",
            displayName = "Notion",
            pkg = "@notionhq/notion-mcp-server",
            description = "Notion pages/databases CRUD. workspace 자동화.",
            category = Category.PRODUCTIVITY, trust = Trust.VERIFIED, recommended = true,
            configFields = listOf(
                ConfigField("NOTION_API_KEY", "Notion Integration Token", "secret_...",
                    isSecret = true,
                    help = "https://www.notion.so/my-integrations 에서 발급."),
            ),
        ))
        add(McpEntry(
            id = "linear",
            displayName = "Linear",
            pkg = "@linear/mcp-server",
            description = "Linear issues/projects. 이슈 자동 생성/업데이트.",
            category = Category.PRODUCTIVITY, trust = Trust.COMMUNITY,
            configFields = listOf(
                ConfigField("LINEAR_API_KEY", "Linear API Key", isSecret = true,
                    help = "Settings > API > Personal API keys."),
            ),
        ))
        add(McpEntry(
            id = "jira",
            displayName = "Jira",
            pkg = "@atlassian/mcp-server-jira",
            description = "Atlassian Jira issues/projects.",
            category = Category.PRODUCTIVITY, trust = Trust.EXPERIMENTAL,
            configFields = listOf(
                ConfigField("JIRA_BASE_URL", "Jira Base URL", "https://yourorg.atlassian.net"),
                ConfigField("JIRA_EMAIL", "Email", "you@example.com"),
                ConfigField("JIRA_API_TOKEN", "API Token", isSecret = true),
            ),
        ))
        add(McpEntry(
            id = "confluence",
            displayName = "Confluence",
            pkg = "@atlassian/mcp-server-confluence",
            description = "Atlassian Confluence pages/spaces read/write.",
            category = Category.PRODUCTIVITY, trust = Trust.EXPERIMENTAL,
            configFields = listOf(
                ConfigField("CONFLUENCE_BASE_URL", "Base URL", "https://yourorg.atlassian.net/wiki"),
                ConfigField("CONFLUENCE_EMAIL", "Email"),
                ConfigField("CONFLUENCE_API_TOKEN", "API Token", isSecret = true),
            ),
        ))
        add(McpEntry(
            id = "slack",
            displayName = "Slack",
            pkg = "@modelcontextprotocol/server-slack",
            description = "Slack 채널/메시지/사용자 조회. bot 토큰 인증.",
            category = Category.PRODUCTIVITY, trust = Trust.VERIFIED,
            configFields = listOf(
                ConfigField("SLACK_BOT_TOKEN", "Bot User OAuth Token", "xoxb-...",
                    isSecret = true,
                    help = "https://api.slack.com/apps > Create App > Bot Token."),
                ConfigField("SLACK_TEAM_ID", "Workspace Team ID", "T0..."),
            ),
        ))
        add(McpEntry(
            id = "discord",
            displayName = "Discord",
            pkg = "mcp-server-discord",
            description = "Discord 서버 메시지/채널/멤버 read/write.",
            category = Category.PRODUCTIVITY, trust = Trust.COMMUNITY,
            configFields = listOf(
                ConfigField("DISCORD_BOT_TOKEN", "Discord Bot Token", isSecret = true),
            ),
        ))
        add(McpEntry(
            id = "trello",
            displayName = "Trello",
            pkg = "mcp-server-trello",
            description = "Trello boards/lists/cards.",
            category = Category.PRODUCTIVITY, trust = Trust.COMMUNITY,
            configFields = listOf(
                ConfigField("TRELLO_API_KEY", "API Key", isSecret = true),
                ConfigField("TRELLO_TOKEN", "Token", isSecret = true),
            ),
        ))
        add(McpEntry(
            id = "asana",
            displayName = "Asana",
            pkg = "mcp-server-asana",
            description = "Asana projects/tasks. PAT 인증.",
            category = Category.PRODUCTIVITY, trust = Trust.COMMUNITY,
            configFields = listOf(
                ConfigField("ASANA_ACCESS_TOKEN", "Personal Access Token", isSecret = true),
            ),
        ))
        add(McpEntry(
            id = "clickup",
            displayName = "ClickUp",
            pkg = "mcp-server-clickup",
            description = "ClickUp tasks/lists/spaces.",
            category = Category.PRODUCTIVITY, trust = Trust.COMMUNITY,
            configFields = listOf(
                ConfigField("CLICKUP_API_TOKEN", "API Token", isSecret = true),
            ),
        ))
        add(McpEntry(
            id = "airtable",
            displayName = "Airtable",
            pkg = "mcp-server-airtable",
            description = "Airtable bases/tables/records.",
            category = Category.PRODUCTIVITY, trust = Trust.COMMUNITY,
            configFields = listOf(
                ConfigField("AIRTABLE_API_KEY", "API Key (또는 PAT)", isSecret = true),
            ),
        ))
        add(McpEntry(
            id = "monday",
            displayName = "Monday.com",
            pkg = "mcp-server-monday",
            description = "Monday.com boards/items.",
            category = Category.PRODUCTIVITY, trust = Trust.EXPERIMENTAL,
            configFields = listOf(
                ConfigField("MONDAY_API_TOKEN", "API Token", isSecret = true),
            ),
        ))
        add(McpEntry(
            id = "google-drive",
            displayName = "Google Drive",
            pkg = "@modelcontextprotocol/server-gdrive",
            description = "Google Drive 파일 검색/read. OAuth 클라이언트 JSON 업로드 후 첫 호출 시 브라우저 인증 필요 — vibe-coder 의 비인터랙티브 환경과 호환 안 됨.",
            category = Category.PRODUCTIVITY, trust = Trust.VERIFIED,
            comingSoon = true,
            configFields = listOf(
                ConfigField("GDRIVE_CREDENTIALS_PATH", "OAuth credentials.json 파일",
                    isFile = true, acceptMime = ".json,application/json",
                    help = "GCP Console > APIs & Services > Credentials > OAuth client ID > Download JSON."),
            ),
        ))
        add(McpEntry(
            id = "obsidian",
            displayName = "Obsidian",
            pkg = "mcp-server-obsidian",
            description = "Obsidian vault notes 검색/edit. Local REST API plugin 필요.",
            category = Category.PRODUCTIVITY, trust = Trust.COMMUNITY,
            configFields = listOf(
                ConfigField("OBSIDIAN_API_KEY", "Local REST API Key", isSecret = true),
                ConfigField("OBSIDIAN_HOST", "Host", "http://localhost:27124"),
            ),
        ))

        // ─── CLOUD ────────────────────────────────────────────────
        add(McpEntry(
            id = "aws-kb",
            displayName = "AWS Knowledge Base",
            pkg = "@modelcontextprotocol/server-aws-kb-retrieval",
            description = "AWS Bedrock Knowledge Base 검색.",
            category = Category.CLOUD, trust = Trust.VERIFIED,
            configFields = listOf(
                ConfigField("AWS_ACCESS_KEY_ID", "Access Key ID", isSecret = true),
                ConfigField("AWS_SECRET_ACCESS_KEY", "Secret Access Key", isSecret = true),
                ConfigField("AWS_REGION", "Region", "us-east-1"),
            ),
        ))
        add(McpEntry(
            id = "cloudflare",
            displayName = "Cloudflare",
            pkg = "@cloudflare/mcp-server-cloudflare",
            description = "Cloudflare Workers / DNS / Pages / R2 관리.",
            category = Category.CLOUD, trust = Trust.VERIFIED,
            configFields = listOf(
                ConfigField("CLOUDFLARE_API_TOKEN", "API Token", isSecret = true,
                    help = "dash.cloudflare.com > My Profile > API Tokens."),
                ConfigField("CLOUDFLARE_ACCOUNT_ID", "Account ID", required = false),
            ),
        ))
        add(McpEntry(
            id = "vercel",
            displayName = "Vercel",
            pkg = "mcp-server-vercel",
            description = "Vercel 프로젝트/배포 목록/로그.",
            category = Category.CLOUD, trust = Trust.COMMUNITY,
            configFields = listOf(
                ConfigField("VERCEL_TOKEN", "Vercel Token", isSecret = true,
                    help = "vercel.com/account/tokens"),
            ),
        ))
        add(McpEntry(
            id = "heroku",
            displayName = "Heroku",
            pkg = "mcp-server-heroku",
            description = "Heroku apps / dynos / logs / releases.",
            category = Category.CLOUD, trust = Trust.EXPERIMENTAL,
            configFields = listOf(
                ConfigField("HEROKU_API_KEY", "API Key", isSecret = true),
            ),
        ))
        add(McpEntry(
            id = "railway",
            displayName = "Railway",
            pkg = "mcp-server-railway",
            description = "Railway 프로젝트 / 서비스 / 로그.",
            category = Category.CLOUD, trust = Trust.EXPERIMENTAL,
            configFields = listOf(
                ConfigField("RAILWAY_TOKEN", "Token", isSecret = true),
            ),
        ))
        add(McpEntry(
            id = "docker-hub",
            displayName = "Docker Hub",
            pkg = "@docker/mcp-server-docker-hub",
            description = "Docker Hub 이미지 검색/메타데이터.",
            category = Category.CLOUD, trust = Trust.COMMUNITY,
        ))

        // ─── COMMS ────────────────────────────────────────────────
        add(McpEntry(
            id = "sendgrid",
            displayName = "SendGrid",
            pkg = "mcp-server-sendgrid",
            description = "SendGrid 이메일 발송 + 템플릿.",
            category = Category.COMMS, trust = Trust.COMMUNITY,
            configFields = listOf(
                ConfigField("SENDGRID_API_KEY", "API Key", isSecret = true),
            ),
        ))
        add(McpEntry(
            id = "twilio",
            displayName = "Twilio",
            pkg = "mcp-server-twilio",
            description = "Twilio SMS / Voice / WhatsApp 발송.",
            category = Category.COMMS, trust = Trust.COMMUNITY,
            configFields = listOf(
                ConfigField("TWILIO_ACCOUNT_SID", "Account SID"),
                ConfigField("TWILIO_AUTH_TOKEN", "Auth Token", isSecret = true),
            ),
        ))
        add(McpEntry(
            id = "telegram",
            displayName = "Telegram Bot",
            pkg = "mcp-server-telegram",
            description = "Telegram Bot 메시지 발송 + 채팅 조회.",
            category = Category.COMMS, trust = Trust.COMMUNITY,
            configFields = listOf(
                ConfigField("TELEGRAM_BOT_TOKEN", "Bot Token", isSecret = true,
                    help = "@BotFather 로 발급."),
            ),
        ))
        add(McpEntry(
            id = "stripe",
            displayName = "Stripe",
            pkg = "@stripe/mcp",
            description = "Stripe customers / payments / subscriptions. 결제 자동화.",
            category = Category.COMMS, trust = Trust.COMMUNITY,
            configFields = listOf(
                ConfigField("STRIPE_SECRET_KEY", "Secret Key", "sk_test_... / sk_live_...",
                    isSecret = true),
            ),
        ))
        add(McpEntry(
            id = "sentry",
            displayName = "Sentry",
            pkg = "@modelcontextprotocol/server-sentry",
            description = "Sentry 에러 이벤트 조회 + 이슈 검색.",
            category = Category.COMMS, trust = Trust.VERIFIED,
            configFields = listOf(
                ConfigField("SENTRY_AUTH_TOKEN", "Auth Token", isSecret = true),
                ConfigField("SENTRY_ORG", "Organization slug", "myorg"),
            ),
        ))

        // ─── APP PUBLISH (Experimental) ───────────────────────────
        add(McpEntry(
            id = "google-play-publisher",
            displayName = "Google Play Publisher",
            pkg = "mcp-server-google-play-publisher",
            description = "Google Play Console — track 업로드 / release / metadata 갱신. service account 인증.",
            category = Category.APP_PUBLISH, trust = Trust.EXPERIMENTAL,
            configFields = listOf(
                ConfigField("GOOGLE_PLAY_SERVICE_ACCOUNT_JSON", "Service Account JSON 파일",
                    isFile = true, acceptMime = ".json,application/json",
                    help = "Google Play Console > Setup > API access > Service accounts > Create key (JSON)."),
                ConfigField("GOOGLE_PLAY_PACKAGE_NAME", "패키지명",
                    "com.siamakerlab.myapp"),
            ),
        ))
        add(McpEntry(
            id = "app-store-connect",
            displayName = "App Store Connect",
            pkg = "mcp-server-app-store-connect",
            description = "Apple App Store Connect API — TestFlight 빌드 / 메타데이터 / 심사 상태.",
            category = Category.APP_PUBLISH, trust = Trust.EXPERIMENTAL,
            configFields = listOf(
                ConfigField("ASC_KEY_ID", "Key ID", "AB12..."),
                ConfigField("ASC_ISSUER_ID", "Issuer ID", "abc-..."),
                ConfigField("ASC_PRIVATE_KEY_FILE", "Private Key 파일 (.p8)",
                    isFile = true, acceptMime = ".p8",
                    help = "App Store Connect > Users and Access > Keys > Generate API Key > Download."),
            ),
        ))
        add(McpEntry(
            id = "fastlane",
            displayName = "Fastlane",
            pkg = "mcp-server-fastlane",
            description = "iOS/Android 빌드/사인/배포 자동화 fastlane wrapper. Ruby/fastlane 별도 설치 필요.",
            category = Category.APP_PUBLISH, trust = Trust.EXPERIMENTAL,
        ))
        add(McpEntry(
            id = "app-publish",
            displayName = "App Publish (Play + App Store)",
            pkg = "app-publish-mcp",
            description = "Google Play Console + Apple App Store Connect 통합 MCP. 릴리스 / 트랙 업로드 / 스크린샷 / 리뷰 / 구독 등 (Play 35 + Apple 56 도구). 쓰는 플랫폼의 인증값만 채우면 됨.",
            category = Category.APP_PUBLISH, trust = Trust.EXPERIMENTAL,
            homepage = "https://github.com/mikusnuz/app-publish-mcp",
            configFields = listOf(
                ConfigField("GOOGLE_SERVICE_ACCOUNT_PATH", "Google Play Service Account JSON 파일",
                    isFile = true, acceptMime = ".json,application/json", required = false,
                    help = "Google Cloud Console 에서 service account 생성 → Google Play Android Developer API 활성화 → Play Console > 설정 > API 액세스에서 권한 부여 후 JSON 키 다운로드. (Play 안 쓰면 비워둠)"),
                ConfigField("APPLE_KEY_ID", "Apple App Store Connect Key ID",
                    "AB12CD34EF", required = false,
                    help = "App Store Connect > Users and Access > Integrations > App Store Connect API. (App Store 안 쓰면 비워둠)"),
                ConfigField("APPLE_ISSUER_ID", "Apple Issuer ID",
                    "12a3b456-...", required = false,
                    help = "App Store Connect API 페이지 상단의 Issuer ID. (App Store 안 쓰면 비워둠)"),
                ConfigField("APPLE_P8_PATH", "Apple Private Key 파일 (.p8)",
                    isFile = true, acceptMime = ".p8", required = false,
                    help = "App Store Connect > Integrations > API Keys > Generate API Key > Download (.p8). (App Store 안 쓰면 비워둠)"),
            ),
        ))

        // ─── AI_ASSIST 추가 ──────────────────────────────────────
        add(McpEntry(
            id = "openai-bridge",
            displayName = "OpenAI Bridge",
            pkg = "mcp-server-openai",
            description = "OpenAI API 를 MCP 로 노출 (GPT-4 호출 등). 멀티 모델 비교용.",
            category = Category.AI_ASSIST, trust = Trust.EXPERIMENTAL,
            configFields = listOf(
                ConfigField("OPENAI_API_KEY", "OpenAI API Key", "sk-...", isSecret = true),
            ),
        ))
        add(McpEntry(
            id = "youtube-transcript",
            displayName = "YouTube Transcript",
            pkg = "mcp-server-youtube-transcript",
            description = "YouTube 영상의 자막/transcript 추출 (영상 학습 보조).",
            category = Category.AI_ASSIST, trust = Trust.COMMUNITY,
        ))
        add(McpEntry(
            id = "wikipedia",
            displayName = "Wikipedia",
            pkg = "mcp-server-wikipedia",
            description = "Wikipedia 본문/요약/검색.",
            category = Category.AI_ASSIST, trust = Trust.COMMUNITY,
        ))
        add(McpEntry(
            id = "arxiv",
            displayName = "ArXiv",
            pkg = "mcp-server-arxiv",
            description = "ArXiv 논문 검색/메타데이터/abstract. 학술 작업에 유용.",
            category = Category.AI_ASSIST, trust = Trust.COMMUNITY,
        ))
        add(McpEntry(
            id = "everart",
            displayName = "Everart (이미지 생성)",
            pkg = "@modelcontextprotocol/server-everart",
            description = "Everart 이미지 생성 API. AI 이미지 출력.",
            category = Category.AI_ASSIST, trust = Trust.VERIFIED,
            configFields = listOf(
                ConfigField("EVERART_API_KEY", "Everart API Key", isSecret = true),
            ),
        ))
    }

    /** id 로 빠른 조회. */
    val byId: Map<String, McpEntry> = all.associateBy { it.id }
    fun get(id: String): McpEntry? = byId[id]

    /** UI 가 그룹 헤더 표시할 때 사용. 빈 그룹은 표시 안 함. */
    val byCategory: Map<Category, List<McpEntry>> =
        all.groupBy { it.category }

    /** 추천 MCP 만 — 첫 사용자 onboarding 시 "추천 묶음 한번에 설치" 옵션. */
    val recommendedIds: List<String> = all.filter { it.recommended }.map { it.id }

    /** v1.37.0 — zero-config 기본 설치 대상 (fetch / memory / sequential-thinking). 카탈로그 기본 선택. */
    val defaultInstallIds: List<String> = all.filter { it.defaultInstall }.map { it.id }

    /** 카탈로그 크기 보고용. */
    val size: Int = all.size
}
