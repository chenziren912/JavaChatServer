# Repository Guidelines

This document is the contributor guide for **ChatServer** — a self-contained,
dependency-light Java social platform (chat, moments, groups, cloud drive,
music, video, mini-games, AI assistant, and admin tooling) built directly on
the JDK's built-in `com.sun.net.httpserver.HttpServer`. Read it before making
changes, and keep it up to date when architecture or conventions shift.

> For an exhaustive, machine-generated, file-by-file dump of the codebase, see
> [`项目详情.md`](./项目详情.md) (~3.4 MB, produced by `generate_agents.py`). That
> file is a raw reference; **this** file is the curated, human-facing guide.

---

## Table of Contents

1. [Project Overview](#1-project-overview)
2. [Project Structure & Module Organization](#2-project-structure--module-organization)
3. [Runtime Data Layout (`chatserver/`)](#3-runtime-data-layout-chatserver)
4. [Build, Test, and Development Commands](#4-build-test-and-development-commands)
5. [Architecture Overview](#5-architecture-overview)
6. [Request Lifecycle & Routing](#6-request-lifecycle--routing)
7. [The Service Layer](#7-the-service-layer)
8. [The Model Layer](#8-the-model-layer)
9. [The Utility Layer](#9-the-utility-layer)
10. [Persistence & Storage Strategy](#10-persistence--storage-strategy)
11. [Frontend & Assets](#11-frontend--assets)
12. [HTTP API Surface](#12-http-api-surface)
13. [Security & Configuration](#13-security--configuration)
14. [Internationalization (i18n)](#14-internationalization-i18n)
15. [External Integrations](#15-external-integrations)
16. [Coding Style & Naming Conventions](#16-coding-style--naming-conventions)
17. [Testing Guidelines](#17-testing-guidelines)
18. [Commit & Pull Request Guidelines](#18-commit--pull-request-guidelines)
19. [Feature Domains Reference](#19-feature-domains-reference)
20. [Adding a New Feature: Step-by-Step](#20-adding-a-new-feature-step-by-step)
21. [Operational Notes & Gotchas](#21-operational-notes--gotchas)
22. [Agent-Specific Instructions](#22-agent-specific-instructions)

---

## 1. Project Overview

ChatServer is a **monolithic Java 17 application** that serves both the HTTP API
and the server-rendered HTML frontend from a single process. It deliberately
avoids heavyweight frameworks (no Spring, no servlet container, no ORM). The
entire web stack is the JDK's `HttpServer`, and all state is persisted as plain
JSON files plus content-addressed binary blobs under a local `chatserver/`
directory.

Key characteristics:

- **Zero external database.** State lives in JSON files and hashed blob files.
- **Singleton services.** Each domain is a `getInstance()` singleton holding an
  in-memory index (usually a `ConcurrentHashMap`) backed by periodic atomic
  writes to disk.
- **Thin entry handler + domain delegates.** `RequestHandler` implements the
  single `HttpHandler` registered on context `/`, owns authentication and route
  dispatch, then delegates pages, AI, cloud, mini-program, and admin requests to
  focused package-private handlers.
- **Server-rendered pages + rich client JS.** HTML shells are built as Java
  strings; the interactive SPA behavior lives in `src/main/resources/assets/`.
- **Chinese-first product.** User-facing strings and code comments are primarily
  Simplified Chinese; an `en` locale is provided via `switch`-based translation.

The build produces a single fat JAR you can launch with `java -jar`.

---

## 2. Project Structure & Module Organization

```text
ChatServer/
├── pom.xml                      # Maven build (Java 17, fat-jar assembly)
├── AGENTS.md                    # This contributor guide
├── 项目详情.md                  # Auto-generated exhaustive code dump (do not hand-edit)
├── generate_agents.py           # Script that regenerates 项目详情.md
├── src/
│   ├── main/
│   │   ├── java/com/chat/
│   │   │   ├── Main.java                 # Entry point (port resolution)
│   │   │   ├── server/                   # HTTP layer
│   │   │   │   ├── ChatHttpServer.java   # Server bootstrap + thread pool
│   │   │   │   ├── RequestHandler.java   # HTTP lifecycle + route dispatch (<4k lines)
│   │   │   │   ├── RequestHandlerSupport.java # Shared parsing/response/upload helpers
│   │   │   │   ├── AppPageRenderer.java  # Login/share/status/main HTML shells
│   │   │   │   ├── AiRequestHandler.java # AI endpoints + provider integration
│   │   │   │   ├── CloudRequestHandler.java # Cloud-drive endpoints
│   │   │   │   ├── GameRequestHandler.java  # Mini-program endpoints
│   │   │   │   ├── AdminRequestHandler.java # Admin and moderation endpoints
│   │   │   │   ├── MusicRequestHandler.java # Music, comments, metadata, and ZIP import
│   │   │   │   ├── CloudEntryMapper.java # Cloud response DTO mapping
│   │   │   │   ├── UserRoles.java        # Shared server-side role predicates
│   │   │   │   ├── StoredFileAccess.java # Unified stored-file authorization policy
│   │   │   │   ├── I18n.java             # Server-side translation helper
│   │   │   │   └── TestGen.java          # Dev/test scaffolding helper
│   │   │   ├── service/                  # Business logic (singletons)
│   │   │   │   ├── UserService.java
│   │   │   │   ├── MessageService.java
│   │   │   │   ├── CloudService.java     # Largest service (~1.5k lines)
│   │   │   │   ├── GroupService.java
│   │   │   │   ├── GameService.java
│   │   │   │   ├── AiService.java
│   │   │   │   ├── MusicService.java
│   │   │   │   ├── VideoService.java
│   │   │   │   ├── MomentService.java
│   │   │   │   ├── FriendService.java
│   │   │   │   ├── NoteService.java
│   │   │   │   ├── FileStore.java        # Content-addressed blob storage
│   │   │   │   ├── AnnouncementService.java
│   │   │   │   ├── FeedbackService.java
│   │   │   │   ├── PublicRoomService.java
│   │   │   │   ├── PasswordRecoveryService.java
│   │   │   │   ├── SuperAdminService.java
│   │   │   │   └── GameCoverRenderer.java
│   │   │   ├── model/                    # Plain POJOs (Gson-serialized)
│   │   │   │   ├── User.java, Message.java, Group.java, Moment.java
│   │   │   │   ├── Note.java, GameEntry.java, GameVersion.java
│   │   │   │   ├── CloudEntry.java, CloudShareLink.java, CloudTask.java
│   │   │   │   ├── MusicTrack.java, MusicPlaylist.java, VideoEntry.java
│   │   │   │   ├── AiConversation.java, AiMessage.java, AiMediaTask.java
│   │   │   │   └── ... (see full list below)
│   │   │   └── util/                     # Cross-cutting helpers
│   │   │       ├── JsonUtil.java          # Gson + atomic file writes
│   │   │       ├── PasswordUtil.java      # PBKDF2 hashing
│   │   │       ├── SessionManager.java    # Session store
│   │   │       └── SessionCookieSecurity.java  # HMAC guard cookies
│   │   └── resources/assets/            # Browser JS/CSS + vendored deps
│   │       ├── app-icons.js             # Generated icon map + theme normalization helpers
│   │       ├── app-extra.js             # Main client logic (~6.5k lines)
│   │       ├── app-i18n.js              # Client translation dictionary (~12.5k lines)
│   │       ├── app-extra.css            # Client styles (~2.3k lines)
│   │       ├── glass-ui.css             # Glass/frosted UI theme
│   │       ├── wechat-theme.css         # Authoritative Apple-inspired responsive UI system
│   │       ├── icons/generated/          # 256px generated module/category/empty-state PNGs
│   │       └── katex/                   # Vendored KaTeX (math rendering)
│   └── test/java/com/chat/             # JUnit 5 tests (mirrors main packages)
│       ├── service/UserServiceValidationTest.java
│       ├── service/MomentServiceDeletionTest.java
│       ├── service/GameServiceCategoryTest.java
│       ├── util/SessionCookieSecurityTest.java
│       └── model/MomentDefaultsTest.java
├── china-clock/                 # Standalone static page (Beijing-time clock, Vercel-deployed)
│   └── index.html
├── chatserver/                  # Runtime state (generated; NOT source)
└── target/                      # Build output (generated)
```

**Layering rule:** dependencies flow `server → service → model/util`. The
`server` layer parses HTTP and formats responses; the `service` layer owns all
business logic and persistence; `model` holds data; `util` holds shared
primitives. Never put business logic in `RequestHandler`; delegate to a service.

### Full model inventory

`FriendRequest`, `GameVersion`, `SessionRecord`, `Note`, `StoredFileMetadata`,
`CloudDownloadRecord`, `CloudShareLink`, `CloudTask`, `FeedbackTicket`,
`AiMediaTask`, `AiUsageLedger`, `MusicPlaylist`, `MusicTrack`, `VideoCategory`,
`VideoComment`, `VideoDanmaku`, `VideoEntry`, `AiConversation`, `Announcement`,
`CloudEntry`, `AiMessage`, `PublicRoomConfig`, `MusicComment`, `Group`,
`Message`, `Moment`, `User`, `PasswordRecoveryRequest`, `GameEntry`.

Models are pure data holders: private fields plus `get`/`set` accessors, no
logic beyond a few defaulting helpers (e.g. `User.isCurrentlyBanned()`).

---

## 3. Runtime Data Layout (`chatserver/`)

`chatserver/` is created next to the running JAR (the working directory is
pinned to the JAR's parent in a `RequestHandler` static initializer). It is
**runtime state, not source** — never delete it in production, and never commit
real user data. Typical layout:

```text
chatserver/
├── cookie-secret.key            # 48-byte Base64 HMAC secret (auto-generated once)
├── sessions.json                # Persisted session records
├── users/
│   ├── users.json               # Legacy combined user file
│   ├── accounts.json            # Split: credentials
│   ├── profiles.json            # Split: profile fields
│   └── settings.json            # Split: preferences
├── chats/public/message         # Public room message log
├── cloud/
│   ├── entries.json  shares.json  tasks.json  downloads.json
├── cloud-files/<userId>/<uuid>.<ext>   # Per-user cloud drive blobs
├── files/
│   ├── index.json               # Content-addressed file index (SHA-512 → metadata)
│   └── <sha512-hash>            # Deduplicated chat/upload blobs (no extension)
├── videos/  (entries, categories, danmaku, comments .json)
├── music/   notes/   moments/   groups/   games/
├── announcements/   feedback/   public-room/config.json
└── ai/      (conversations, messages, tasks, usage ledger)
```

Two distinct blob stores exist:

- **`files/`** — global, content-addressed by SHA-512 (see `FileStore`), used for
  chat attachments and shared uploads; identical bytes are stored once.
- **`cloud-files/<userId>/`** — per-user cloud drive storage keyed by random
  UUID filenames, managed by `CloudService`.

---

## 4. Build, Test, and Development Commands

This is a Maven project targeting **Java 17**. Run all commands from the repo
root.

| Command | Purpose |
| --- | --- |
| `mvn clean package` | Compile, run tests, and build the fat JAR (`jar-with-dependencies`). |
| `mvn test` | Run the JUnit 5 test suite only. |
| `mvn clean` | Remove the `target/` build output. |
| `mvn compile` | Compile without packaging or testing. |
| `java -jar target/ChatServer-1.0-SNAPSHOT-jar-with-dependencies.jar 8080` | Run the server on port 8080. |

### Running locally

```bash
# 1. Build
mvn clean package

# 2. Run on port 8080 (positional arg wins over env var)
java -jar target/ChatServer-1.0-SNAPSHOT-jar-with-dependencies.jar 8080

# 3. Open the app
#    http://localhost:8080  → redirects to /login (or /chat if signed in)
```

**Port resolution order** (`Main.resolvePort`): first CLI arg → `CHAT_SERVER_PORT`
env var → default `80`. Values must parse to `1..65535`, otherwise it falls back
to `80`.

The assembly plugin generates two artifacts: the thin `ChatServer-1.0-SNAPSHOT.jar`
and the runnable `ChatServer-1.0-SNAPSHOT-jar-with-dependencies.jar`. Always run
the `-jar-with-dependencies` one; the manifest `Main-Class` is `com.chat.Main`.

### Providing API keys for AI features

AI chat/image/video routes require provider keys (see
[External Integrations](#15-external-integrations)):

```bash
export LONGCAT_API_KEY=...     # or -Dlongcat.api.key=...
export VOLC_API_KEY=...        # or -Dvolc.api.key=...
java -jar target/ChatServer-1.0-SNAPSHOT-jar-with-dependencies.jar 8080
```

Without keys, non-AI features work normally; AI endpoints will fail gracefully.

---

## 5. Architecture Overview

```text
                ┌──────────────────────────────────────────┐
   Browser ───► │  com.sun.net.httpserver.HttpServer         │
   (HTML +      │  context "/"  →  RequestHandler (HttpHandler)│
    fetch)      └───────────────┬────────────────────────────┘
                                │  parse method/path/query/cookies
                                │  session validation + auth gating
                                ▼
                ┌──────────────────────────────────────────┐
                │  handleGet / handlePost dispatch (switch)  │
                └───────────────┬────────────────────────────┘
                                │ delegate
                                ▼
      ┌───────────────────────────────────────────────────────────┐
      │  Service singletons (UserService, CloudService, ...)        │
      │  - in-memory ConcurrentHashMap index                        │
      │  - business rules, validation, authorization                │
      │  - dirty-flag + background save thread (every ~2s)          │
      └───────────────┬───────────────────────────────────────────┘
                      │ JsonUtil.saveJsonAtomic / FileStore
                      ▼
      ┌───────────────────────────────────────────────────────────┐
      │  chatserver/*.json  +  content-addressed blob files          │
      └───────────────────────────────────────────────────────────┘
```

### Bootstrap (`ChatHttpServer.start`)

```java
GameService.warmUp();                                   // preload game data
HttpServer server = HttpServer.create(
        new InetSocketAddress("0.0.0.0", port), 0);
server.createContext("/", new RequestHandler());        // one handler for everything
server.setExecutor(new ThreadPoolExecutor(
        16, 64, 60L, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(1000),
        new ThreadPoolExecutor.CallerRunsPolicy()));     // backpressure via caller-runs
server.start();
```

The thread pool is bounded: **16 core / 64 max threads**, a 1,000-slot queue,
and a `CallerRunsPolicy` so that overload throttles the acceptor rather than
dropping requests. Keep per-request work non-blocking where possible; long AI
calls use a shared `java.net.http.HttpClient`.

---

## 6. Request Lifecycle & Routing

Every request flows through `RequestHandler.handle(HttpExchange)`:

1. **CORS.** `Access-Control-Allow-Origin: *` is always added. `OPTIONS` returns
   `204` with the allowed methods/headers.
2. **Cookie integrity.** `SessionManager.getSessionIdFromCookie` extracts the
   `sessionId`; `SessionCookieSecurity.validate` verifies the 12 signed guard
   cookies. On failure, all cookies are cleared and the client is redirected to
   `/login?reason=session-security` (or gets `401` for `/api/*`).
3. **Session → user.** `SessionManager.getUser(sid)` resolves the current
   `User`; valid sessions are refreshed (sliding expiry). Language is set into
   `I18n.CURRENT_LANG` (a `ThreadLocal`) for this request.
4. **Ban enforcement.** Banned users (`me.isCurrentlyBanned()`) are blocked from
   all but a small whitelist (login/logout/register/assets/etc.).
5. **Auth gating.** Anonymous users may only reach public paths (login, register,
   forgot-password, a few public APIs, `/assets/`, `/share/`, `/files/` — the
   last enforces per-file access in `serveFile`).
6. **Dispatch.** `GET` → `handleGet`; `POST` → `handlePost` (with special
   streaming upload routes handled before body reading). Anything else → `405`.
7. **Error handling.** `OutOfMemoryError` → `413`; other exceptions → `500`
   (JSON for `/api/*`, plain text otherwise). `I18n.CURRENT_LANG` is always
   cleared in `finally`.

Routing remains explicit: `handleGet` → `handleGetApiRoute`, and `handlePost` →
the POST switch. Each `case` calls either a focused local handler or one of the
domain delegates (`AiRequestHandler`, `CloudRequestHandler`,
`GameRequestHandler`, `AdminRequestHandler`, `MusicRequestHandler`). **Add new domain routes to their
own handler; keep authentication, global policy, and dispatch only in
`RequestHandler`, and never inline business logic into a switch.** Shared HTTP
parsing and response primitives belong in `RequestHandlerSupport`.

Special GET prefixes handled before the switch:

- `/files/...`   → `serveFile` (content-addressed blobs, with access checks)
- `/cloud-files/...` → `serveCloudFile` (per-user cloud drive)
- `/shared-cloud-files/...` → `serveSharedCloudFile` (public cloud-share download)
- `/assets/...`  → `serveAsset` (static assets with path-traversal guards)
- `/share/...`   → public share pages (no login required)
- app page paths (`/chat`, etc.) → `AppPageRenderer.buildChatPage`

---

## 7. The Service Layer

All services live in `com.chat.service` and follow one consistent pattern.

### The singleton + in-memory + async-persist pattern

```java
public class UserService {
    private static final UserService INSTANCE = new UserService();
    public static UserService getInstance() { return INSTANCE; }

    private final Map<String, User> usersById = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private volatile boolean dirty = false;

    private UserService() {
        load();                                  // read JSON into memory
        Thread saver = new Thread(() -> {        // periodic flush
            while (true) {
                Thread.sleep(2000);
                if (dirty) saveSync();
            }
        });
        saver.setDaemon(true);
        saver.start();
        Runtime.getRuntime().addShutdownHook(new Thread(this::saveSync));
    }
}
```

Conventions every service follows:

- **Access via `getInstance()`** — never `new`. Services are process-wide.
- **Initialize singleton dependencies first.** Any static collection, pattern,
  or helper object used by a service constructor or `load()` must be declared
  before the `INSTANCE` field; compile-time `String`/primitive constants are
  exempt.
- **In-memory source of truth** — a `ConcurrentHashMap` (often indexed multiple
  ways, e.g. by id *and* by name).
- **`dirty` flag + daemon save thread** — mutations set `dirty = true`; a
  background thread flushes every ~2 seconds, and a JVM shutdown hook flushes on
  exit. This debounces disk writes under load.
- **`ReentrantReadWriteLock`** guards multi-step mutations that must be atomic
  relative to snapshots being serialized.
- **Snapshot synchronized lists before iteration.** `User.snapshotFriends()`
  is the canonical example; do not stream or enhanced-for a
  `Collections.synchronizedList` without holding its monitor.
- **Data paths are relative** to the JAR's working directory
  (`chatserver/...`), resolved consistently because `user.dir` is pinned at
  startup.

### Service responsibilities (quick map)

| Service | Owns |
| --- | --- |
| `UserService` | Accounts, profiles, settings, validation, bans, quotas, EXP/level |
| `MessageService` | Private/group/public messages, recall, pagination, events |
| `FriendService` | Friend requests and friendship graph |
| `GroupService` | Group lifecycle, membership, roles, mute, icons |
| `MomentService` | Moments (朋友圈): posts, likes, comments, deletion |
| `CloudService` | Cloud drive: folders, files, shares, recycle bin, safebox, zip |
| `FileStore` | Global content-addressed blob storage (SHA-512, dedup) |
| `MusicService` | Tracks, playlists, recommendations, comments, metadata |
| `VideoService` | Videos, categories, danmaku, comments |
| `NoteService` | Notes and note sharing |
| `GameService` | Mini-game catalog, versions, approval, visits |
| `GameCoverRenderer` | Generates game cover images |
| `AiService` | AI conversations, messages, media tasks, token ledger |
| `AnnouncementService` | Site announcements |
| `FeedbackService` | User feedback tickets |
| `PublicRoomService` | Public chat room config, admins, mutes |
| `PasswordRecoveryService` | Password recovery requests |
| `SuperAdminService` | Super-admin roster and checks |

---

## 8. The Model Layer

Models in `com.chat.model` are **plain POJOs** designed for Gson
(de)serialization. Rules:

- Private fields + public `get`/`set` accessors. No constructors with logic
  required (Gson instantiates via no-arg).
- Timestamps are `long` epoch-millis (`createdAt`, `updatedAt`, etc.).
- IDs are `String` (usually UUIDs or numeric strings).
- Keep derived/behavioral helpers minimal — e.g. `User` includes ban helpers
  such as `isCurrentlyBanned()`, `getBanRemainingMillis()`, and
  `isFeatureBanned(String)` because the router needs them, but heavier logic
  belongs in services.

Example (`Note.java`) — canonical shape to imitate for new models:

```java
public class Note {
    private String id;
    private String ownerId;
    private String title;
    private String content;
    private long createdAt;
    private long updatedAt;
    private String shareId;
    private long shareUpdatedAt;
    // getters/setters only
}
```

When adding a field, prefer additive changes; Gson ignores unknown JSON keys and
leaves missing fields at their Java defaults, which keeps old data files
forward-compatible.

---

## 9. The Utility Layer

`com.chat.util` holds shared, framework-free primitives.

### `JsonUtil`

- Wraps a single shared `Gson` instance (`toJson` / `fromJson`).
- **Atomic writes are mandatory for persistence.** Use
  `JsonUtil.saveJsonAtomic(path, obj)`, `writeStringAtomic`, `writeBytesAtomic`,
  or `writeLinesAtomic`. Each writes to a `*.tmp.<uuid>` sibling then performs
  `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)`, so a crash never leaves a
  half-written JSON file. Never write JSON with a raw `Files.writeString`.

### `PasswordUtil`

- PBKDF2 (`PBKDF2WithHmacSHA256`), **120,000 iterations**, 256-bit key, 16-byte
  random salt from `SecureRandom`.
- Stored format: `pbkdf2$<iterations>$<base64 salt>$<base64 hash>`.
- `hashPassword`, `verifyPassword` (constant-time compare via
  `MessageDigest.isEqual`), and `looksHashed` for migration checks. **Never**
  store or compare plaintext passwords.

### `SessionManager`

- Singleton session store persisted to `chatserver/sessions.json`.
- `SESSION_MAX_AGE_SECONDS = 30 days` (sliding — refreshed on each authenticated
  request). Same dirty-flag + daemon-save + shutdown-hook pattern as services.
- Maps `sessionId → SessionRecord → User`.

### `SessionCookieSecurity`

- Anti-tampering layer on top of the raw `sessionId` cookie: **12 HMAC-SHA256
  signed "guard" cookies** (`chatGuard01`..`chatGuard12`), signed with a
  per-install 48-byte secret stored in `chatserver/cookie-secret.key`
  (auto-generated on first run).
- `validate()` requires *exactly* 12 correctly-signed guards for an authenticated
  request, and *zero* guards for an anonymous one (orphaned guards are rejected).
- Cookies are `HttpOnly; SameSite=Lax`, and `Secure` is added when
  `chat.cookie.secure=true` / `CHATSERVER_COOKIE_SECURE=true` or the request
  arrives via `X-Forwarded-Proto: https`.

---

## 10. Persistence & Storage Strategy

There is **no database**. Persistence has three tiers:

1. **JSON documents** (`chatserver/**/*.json`) — one or more per service,
   holding the serialized in-memory collections. Written atomically via
   `JsonUtil`.
2. **Content-addressed blobs** (`chatserver/files/<sha512>`) — managed by
   `FileStore`. On upload the stream is hashed with SHA-512 while being written
   to a temp file; the hash becomes the stored filename, so identical content is
   automatically deduplicated. Metadata lives in `files/index.json`
   (`StoredFileMetadata`: storedName, originalFileName, contentType, size,
   createdAt, lastAccessAt, ownerUserIds). Deduplicated uploads append every
   uploader to `ownerUserIds`; `StoredFileAccess` combines that ownership with
   message/cloud/note/public-reference permissions before a stored path may be reused.
3. **Per-user cloud blobs** (`chatserver/cloud-files/<userId>/<uuid>.<ext>`) —
   managed by `CloudService`, keyed by random UUID, not deduplicated across
   users.

Guidelines:

- **Always go through the owning service** for reads/writes; never touch
  `chatserver/` files directly from `RequestHandler`.
- **Never block the acceptor** on large writes — services buffer in memory and
  flush asynchronously.
- **Preserve backward compatibility.** `UserService` demonstrates a migration
  path: it reads split files (`accounts/profiles/settings.json`) when present and
  falls back to the legacy combined `users.json`. Follow this pattern when
  changing on-disk shapes.
- **Archive extraction is hard-limited.** Cloud ZIP extraction rejects unsafe
  paths and caps entry count, path depth, single-file output, and total output;
  these limits must remain independent of a user's cloud quota or admin role.

---

## 11. Frontend & Assets

The frontend is **server-rendered HTML shells + a large vanilla-JS client**.
There is no build step for the frontend and no framework.

### How pages are assembled

`AppPageRenderer` builds HTML as Java strings and `RequestHandler` only selects
which shell to return:

- `buildLoginPage()`, `buildForgotPasswordPage()`, `buildChatPage(me)`,
  `buildSharePage(...)`, `buildStatusPage(...)`.
- The main app shell (`buildChatPage`) links the stylesheets and scripts from
  `/assets/` and cache-busts the JS with `?v=<currentTimeMillis>`:

```java
"<link rel='stylesheet' href='/assets/app-extra.css'>"
"<link rel='stylesheet' href='/assets/wechat-theme.css'>"
"<script src='/assets/app-i18n.js?v=...'></script>"
"<script src='/assets/app-extra.js?v=...'></script>"
```

### Asset files (`src/main/resources/assets/`)

| File | Role |
| --- | --- |
| `app-extra.js` (~6.5k lines) | Main client logic; global state on `window.X`; helpers like `q(id)`, `esc()`, `fallbackAvatar()` mounted on `window` for inline `onclick` handlers. |
| `app-icons.js` | Canonical generated-image map (`window.AppIcons`), `featureIcon()`, and legacy-to-current `normalizeTheme()` mapping. |
| `app-i18n.js` (~12.5k lines) | `I18N_DICT` translation dictionary keyed by locale (`zh-CN`, `en`, ...). |
| `app-extra.css` (~2.3k lines) | Primary client styles. |
| `glass-ui.css` | Legacy frosted-glass stylesheet; retained as an asset but not loaded by the main app. |
| `wechat-theme.css` | Authoritative Apple-inspired design system: iOS grouped surfaces, system colors, material navigation, dark mode, and the responsive shell. |
| `icons/generated/` | Generated 256×256 PNG module, category, built-in app, and empty-state assets. |
| `katex/` | Vendored KaTeX for math rendering (a CDN fallback also exists). |

The client state object `window.X` namespaces every feature (`cloud`, `ai`,
`music`, `videos`, `preview`, `mobile`, `admin`, ...). When adding client
behavior, extend `window.X` and reuse existing helpers rather than introducing a
new global.

The supported persisted appearance keys remain `sand`, `ink`, `pine`, and
`clay` for backward compatibility, but their user-facing Apple-style variants
are 系统蓝、深空黑、薄荷绿、日落橙. Old persisted theme values must be passed
through `normalizeTheme()` instead of adding new legacy selectors. Mobile
primary navigation uses the inline `ios-symbol` SVG set in `app-extra.js`;
feature artwork, categories, and empty states may continue to use
`icons/generated/`. Avoid Emoji as primary navigation icons.

**Style rule (important):** business/client JS belongs in `assets/`, **not**
embedded in Java strings. Some inline `<script>`/`<style>` still exists in
`AppPageRenderer` (e.g. KaTeX wiring) for historical reasons — prefer moving new
client code into `app-extra.js` and new styles into the CSS files.

User-uploaded HTML and mini-programs must remain in sandboxed iframes without
`allow-same-origin`. `/files/` also emits a CSP sandbox for `text/html`; never
remove either boundary or serve uploaded HTML as an unrestricted same-origin page.

The mini-program directory merges server-provided entries with the client-only
`window.BuiltinMiniApps` catalog. `builtin-qr` is the sole built-in entry and is
launched at `/games?app=builtin-qr`; it must never be inserted into `games.json`
or sent through game create/update/publish/approval APIs. `/tools` is retained
only as an authenticated compatibility redirect to that canonical route.

### `china-clock/`

A standalone, self-contained static page (a Beijing-time analog clock) unrelated
to the backend. It ships with a `.vercel/` config and is deployed independently.
Treat it as an isolated artifact; changes there don't affect the server.

---

## 12. HTTP API Surface

The API is a flat set of `/api/*` routes under the single handler. Conventions:

- **JSON responses** via `sendJson(exchange, status, map(...))`; the `map(...)`
  helper builds ordered string maps. Errors use `{"error": "..."}`.
- **Auth by session cookie**, resolved before dispatch. `/api/*` returns `401`
  when unauthenticated (except the public whitelist).
- **Uploads are streamed.** Legacy form upload (`/api/upload-file-form`) returns
  `410 Gone`; use the streaming routes (`/api/upload-file-stream`,
  `/api/store-file-stream`, `/api/games/upload-binary`).

Representative routes by domain (non-exhaustive — see the `switch` blocks in
`RequestHandler` for the complete list):

**Auth & profile:** `POST /api/login`, `POST /api/register`,
`POST /api/check-user`, `GET|POST /logout` & `/api/logout`, `GET /api/me`,
`POST /api/profile/update`, `POST /api/profile/avatar`, `POST /api/profile/skin`,
`POST /api/profile/verify-password`, `POST /api/profile/delete-self`,
`POST /api/check-in`.

**Messaging:** `GET /api/messages`, `GET /api/messages/paged`,
`GET /api/last-messages`, `GET /api/events`, `GET /api/unread`,
`POST /api/send-message`, `POST /api/recall-message`, `POST /api/forward-message`,
`POST /api/forward-to-moment`.

**Friends:** `GET /api/friends`, `GET /api/friend-requests/sent`,
`GET /api/friend-requests/received`, `POST /api/send-friend-request`,
`POST /api/handle-friend-request`.

**Moments:** `GET /api/moments`, `GET /api/user/moments`, `POST /api/moments/post`,
`POST /api/moments/like`, `POST /api/moments/comment`, `POST /api/moments/delete`.

**Groups:** `GET /api/groups`, `GET /api/group/info`, `POST /api/group/create`,
`/invite`, `/kick`, `/set-admin`, `/transfer-owner`, `/join-as-admin`,
`/force-add-member`, `/leave`, `/rename`, `/mute`, `/mute-all`,
`/delete-old-messages`, `/icon`, `/description`.

**Public room:** `GET /api/public-room/config`, `POST /api/public-room/*`
(`mute-all`, `toggle-all-mute`, `add-admin`, `remove-admin`, `description`,
`mute-user`, `unmute-user`, `delete-old-messages`).

**Cloud drive:** `GET /api/cloud/list|recycle|shares|downloads|tasks|zip-tree|favorites|safebox`,
`POST /api/cloud/create-folder|create-file|rename|delete|restore|purge|share|save-share|move|copy|import-stored|unzip|compress|compress-batch|toggle-favorite|toggle-safebox|clear-downloads`.

**Music:** `GET /api/music/tracks|playlists|recommend|extract-meta|comments`,
`POST /api/music/upload|create-playlist|toggle-playlist|play|comment|update|delete|import-zip`.

**Video:** `GET /api/videos/list|categories|comments|danmaku`,
`POST /api/videos/create-category|upload|play|comment|danmaku`.

**Notes:** `GET /api/notes`, `GET /api/note`, `POST /api/note/create|update|delete|share`.

**Games:** `GET /api/games`, `GET /api/games/pending`,
`POST /api/games/upload|create|publish-version|update-meta|upload-asset|visit|approve`,
`POST /api/games/upload-binary`.

**AI:** `GET /api/ai/models|conversations|messages|tasks`,
`POST /api/ai/conversation/create|update|delete`, `POST /api/ai/send`,
`POST /api/ai/send-stream`.

**Sharing / misc:** `GET /api/share/data`, `POST /api/share/send-card`,
`GET /shared-cloud-files/<shareId>` (public cloud-share download),
`GET /api/stickers` + `POST /api/stickers/add`,
`POST /api/tools/decode-qr|encode-qr`,
`GET /api/search`, `GET /api/announcements` + `/latest`,
`POST /api/announcements/create`, `GET /api/feedback/list` +
`POST /api/feedback/create|status`, `POST /api/password-recovery/request`,
`POST /api/tutorial/complete`.

**Admin (super-admin gated):** `GET /api/admin/overview|users|groups|super-admins|password-recovery`,
`POST /api/admin/add-super-admin|remove-super-admin|quit-super-admin|set-user-tags|delete-group|delete-user|force-logout|set-user-password|ban-user|unban-user|set-feature-ban|grant-exp|set-level|set-user-quota|set-ai-tokens|password-recovery/status`.

---

## 13. Security & Configuration

Security is implemented by hand; understand these mechanisms before touching
auth-adjacent code.

- **Password storage:** PBKDF2-HMAC-SHA256, 120k iterations (`PasswordUtil`).
  Never weaken parameters or introduce plaintext paths.
- **Session cookies:** `sessionId` plus 12 HMAC-signed guard cookies
  (`SessionCookieSecurity`). Any mismatch, missing, extra, or tampered guard
  invalidates the session and forces re-login. Cookies are `HttpOnly` and
  `SameSite=Lax`.
- **Cookie secret:** `chatserver/cookie-secret.key`, auto-generated 48-byte
  secret. Rotating it invalidates all sessions. Never commit it.
- **Path-traversal defense:** `serveAsset`/`resolveAssetFile` reject names
  containing `..`, absolute paths, drive letters, or leading separators, and
  verify the resolved real path stays under an allowed base directory. Reuse
  this approach for any new file-serving route.
- **Access control on files:** `/files/...` is gated by a `canAccessFile` check
  inside `serveFile`; `/cloud-files/...` is owner-scoped in `serveCloudFile`.
- **Authorization tiers:** anonymous → authenticated user → group/room admin →
  super-admin (`SuperAdminService`) → developer. Note: `isDeveloper` currently
  matches hard-coded usernames (`陈梓仁` / `chenziren`); treat that as a known
  special case, not a pattern to copy.
- **Ban system:** account bans (`isCurrentlyBanned`, with reason and expiry) and
  per-feature bans (`isFeatureBanned("upload")`) are enforced in the router.
- **Rate limiting:** `msgRateLimit` (`ConcurrentHashMap<String,long[]>`) throttles
  messaging; expired buckets are swept via `cleanExpiredRateLimits`.

### Configuration reference

| Setting | Source | Default | Effect |
| --- | --- | --- | --- |
| Server port | CLI arg / `CHAT_SERVER_PORT` | `80` | Listen port |
| `LONGCAT_API_KEY` | env / `-Dlongcat.api.key` | empty | LongCat AI key |
| `VOLC_API_KEY` | env / `-Dvolc.api.key` | empty | Volcengine Ark key |
| `chat.cookie.secure` / `CHATSERVER_COOKIE_SECURE` | prop / env | off | Add `Secure` to cookies |
| `chat.testSendResponseDelayMs` / `CHATSERVER_TEST_SEND_RESPONSE_DELAY_MS` | prop / env | `0` | Test-only artificial send delay |

**Never** hard-code credentials or commit real keys, `cookie-secret.key`, or the
contents of `chatserver/`.

---

## 14. Internationalization (i18n)

Two parallel translation layers:

- **Server-side:** `com.chat.server.I18n` with a `ThreadLocal<String>
  CURRENT_LANG` set per request from the user's `language`. `I18n.t(text)`
  returns the input for `zh-CN`/null and switches on the source string for `en`.
  Default/base language is Simplified Chinese — write source strings in Chinese
  and add `en` cases as needed.
- **Client-side:** `assets/app-i18n.js` exposes `I18N_DICT` keyed by locale; the
  client picks translations at render time.

When adding user-facing text, add the Chinese source first, then extend both the
server `switch` (if server-rendered) and the client dictionary (if client-rendered).

---

## 15. External Integrations

Configured primarily in `AiRequestHandler`; AI calls use its bounded shared
`java.net.http.HttpClient` with connection/request timeouts. `RequestHandler`
retains only the super-admin `/ai` chat-proxy integration, with its own bounded
client.

- **LongCat Chat** — `https://api.longcat.chat/openai/v1/chat/completions`
  (OpenAI-compatible), key `LONGCAT_API_KEY`.
- **Volcengine Ark** — chat
  (`.../api/v3/chat/completions`), image
  (`.../api/v3/images/generations`), and async video
  (`.../api/v3/contents/generations/tasks`), key `VOLC_API_KEY`.
- **ZXing** (`com.google.zxing`) — QR generation and decoding
  (`POST /api/tools/encode-qr`, `POST /api/tools/decode-qr`).
- **jaudiotagger** — reads music metadata/artwork for uploaded tracks
  (`GET /api/music/extract-meta`).
- **KaTeX** — math rendering, vendored under `assets/katex/` with a CDN fallback.

AI media generation is task-based: submit → poll task status → fetch result
(`AiMediaTask`, `/api/ai/tasks`). Token accounting is tracked via `AiUsageLedger`.

---

## 16. Coding Style & Naming Conventions

- **Language/target:** Java 17, UTF-8 source encoding (enforced by the compiler
  plugin).
- **Indentation:** 4 spaces, no tabs.
- **Braces:** opening brace on the same line (K&R style).
- **Naming:** `PascalCase` for classes/interfaces, `camelCase` for
  methods/fields/locals, `UPPER_SNAKE_CASE` for constants.
- **Packages:** lowercase, rooted at `com.chat`, grouped by responsibility
  (`server`, `service`, `model`, `util`).
- **Singletons:** expose `getInstance()`; keep constructors private.
- **Comments & strings:** primarily Simplified Chinese, matching the product.
- **Imports:** the codebase mixes explicit imports with fully-qualified names
  (e.g. `java.nio.file.*` inline). Prefer explicit imports for new code, but
  match the surrounding file.
- **Concurrency:** use `ConcurrentHashMap` for shared indexes and
  `ReentrantReadWriteLock` for multi-step atomic operations, as existing services
  do.
- **Persistence:** always use `JsonUtil` atomic writers; never partial-write JSON.
- **Layering:** keep `RequestHandler` methods thin — parse input, call a service,
  format the response. All rules and storage live in services.
- **No JS in Java strings** for new client behavior — put it in `assets/`.

There is no automated formatter/linter configured; follow the conventions above
and keep diffs minimal and consistent with neighboring code.

---

## 17. Testing Guidelines

- **Framework:** JUnit 5 (`org.junit.jupiter:junit-jupiter:5.10.2`, `test`
  scope).
- **Location:** `src/test/java`, mirroring the production package structure
  (e.g. `com.chat.util.SessionCookieSecurityTest` tests
  `com.chat.util.SessionCookieSecurity`).
- **Naming:** unit tests end in `*Test`; integration tests end in `*IT`.
- **Runner config:** Surefire runs with
  `workingDirectory=${project.build.directory}/test-work`, so tests that touch
  the filesystem operate under `target/test-work` rather than the repo root.
- **Run all tests:** `mvn test`. **Run one class:**
  `mvn test -Dtest=SessionCookieSecurityTest`.

Existing tests focus on pure, deterministic logic — validation, security
invariants, model defaults, and deletion semantics:

- `UserServiceValidationTest` — username/nickname rules and boundaries.
- `SessionCookieSecurityTest` — requires all 12 signed guards; rejects
  tampered/extra/orphaned guards.
- `MomentDefaultsTest`, `MomentServiceDeletionTest`, `GameServiceCategoryTest`.

**Coverage expectations for new work:** cover success paths, invalid input,
authorization/permission branches, and persistence effects. Prefer testing
service and util logic directly (they are plain singletons/classes) over trying
to spin up the HTTP server. Do not use real credentials or production data;
never point tests at a real `chatserver/` directory.

---

## 18. Commit & Pull Request Guidelines

This working copy has no populated Git history to infer conventions from, so
follow these defaults:

- **Commit subjects:** short, imperative, and scoped, e.g.
  `fix: validate cloud upload paths`, `feat: add music playlist reorder`,
  `refactor: extract message pagination helper`. Keep the subject under ~72
  chars; add a body explaining *why* when the change is non-trivial.
- **One logical change per commit.** Don't mix unrelated refactors with features.
- **Never commit generated or secret material:** `target/`, `chatserver/`
  (runtime state and user data), `cookie-secret.key`, API keys, or the large
  auto-generated `项目详情.md` changes unless you regenerated it intentionally.

**Pull requests should:**

- Clearly describe the behavior change and the motivation.
- List the verification commands you ran (`mvn clean package`, `mvn test`, manual
  steps).
- Call out any on-disk format or storage impact (new/changed `chatserver/`
  files, migrations, backward-compatibility handling).
- Link related issues/tickets.
- Include screenshots or short clips for any UI change (login/chat/cloud/etc.).
- Note new configuration (env vars, system properties) and security-relevant
  changes explicitly.

---

## 19. Feature Domains Reference

A quick tour of what the platform does, so you know which service/route to touch.

- **Chat.** Private 1:1, group, and a global public room. Messages support
  recall, forwarding, pagination, unread counts, and a polling/event endpoint
  (`/api/events`). Rate-limited per user.
- **Friends.** Directed friend requests with accept/reject, then a friendship
  graph used to gate DMs and moments visibility.
- **Moments (朋友圈).** Timeline posts with likes and comments; messages can be
  forwarded into a moment.
- **Groups.** Ownership/admin/member roles, invites, kicks, ownership transfer,
  per-user and global mute, icons, descriptions, and bulk message cleanup.
- **Cloud drive.** Folder tree, upload/rename/move/delete, recycle bin with
  restore/purge, share links (with optional save-to-my-drive), favorites, a
  "safebox" (safe), zip tree preview, unzip, and (batch) compress. Blobs are
  per-user under `cloud-files/`.
- **Music.** Upload with metadata/artwork extraction, playlists, a daily
  recommendation list, play tracking, lyrics, and comments.
- **Video.** Categorized videos with danmaku (bullet comments), comments,
  playback-rate control, and lazy-loaded lists.
- **Notes.** Personal notes with optional public share links.
- **Mini-games.** A small game platform: upload/create games, publish versions,
  upload binary/asset payloads, admin approval workflow, visit counting, and
  server-side cover rendering.
- **AI assistant.** Multi-conversation chat with streaming
  (`/api/ai/send-stream`), model selection, and async image/video media tasks,
  with a per-user token ledger.
- **Admin.** Super-admin console: user/group overviews, bans (account + feature),
  quotas, EXP/level grants, AI token grants, forced logout, password resets,
  super-admin management, and password-recovery handling.
- **Misc.** Stickers, daily check-in, site announcements, feedback tickets,
  password recovery, global search, QR decode tool, and shareable card links.

---

## 20. Adding a New Feature: Step-by-Step

Use this checklist to stay consistent with the codebase.

1. **Model.** If you need new persistent data, add a POJO to `com.chat.model`
   with private fields + accessors and `long` epoch-millis timestamps.
2. **Service.** Create or extend a singleton in `com.chat.service`:
   - `private static final X INSTANCE = new X(); public static X getInstance()`.
   - In-memory `ConcurrentHashMap` index; load from `chatserver/...` on construct.
   - Mutations set `dirty = true`; add the daemon save thread + shutdown hook if
     new; persist via `JsonUtil.saveJsonAtomic`.
   - Put **all** validation and authorization here.
3. **Route.** Add a `case "/api/your-route":` in the appropriate `switch`
   (`handleGetApiRoute` for GET, the POST switch for POST) and a thin
   `handleYourRoute(...)` method that parses input, calls the service, and
   responds with `sendJson`.
4. **Auth.** Confirm the route is *not* accidentally added to a public whitelist;
   add explicit permission checks (owner/admin/super-admin) as needed.
5. **Frontend.** Add client logic to `assets/app-extra.js` (extend `window.X`),
   styles to the CSS files, and any strings to `assets/app-i18n.js` (+ server
   `I18n` if server-rendered).
6. **Tests.** Add `*Test` covering success, invalid input, authorization, and
   persistence. Run `mvn test`.
7. **Docs.** Update this guide's API/feature sections if you added a public
   surface.

---

## 21. Operational Notes & Gotchas

- **Working directory is pinned.** A static initializer sets `user.dir` to the
  JAR's parent, so `chatserver/` is always beside the JAR regardless of where you
  launch from. When running from an IDE/`target/classes`, paths resolve relative
  to the project instead — be aware when debugging data location.
- **First run bootstraps secrets.** `cookie-secret.key` is created automatically;
  deleting it logs everyone out.
- **Two upload paths, one deprecated.** `/api/upload-file-form` is `410 Gone`;
  always use the streaming endpoints.
- **Large files & memory.** Uploads are streamed and hashed incrementally; the
  handler catches `OutOfMemoryError` and returns `413`. Don't buffer whole files
  in memory in new code.
- **`项目详情.md` is generated.** Regenerate with `python generate_agents.py`
  (edit `project_root` inside if needed); don't hand-edit it.
- **No Git repo is initialized here** (only a `.git/refs` scaffold exists). If
  you need history/branches, initialize Git before starting.
- **The dev/test delay knob** (`chat.testSendResponseDelayMs`) exists to simulate
  slow sends in tests; leave it at `0` in production.

---

## 22. Agent-Specific Instructions

For AI agents and automated contributors working in this repo:

- **Respect the layering.** Add routing in `RequestHandler` but keep it thin;
  implement logic in a service. Do not embed new client JS/CSS in Java strings.
- **Use the established patterns** (singleton + `ConcurrentHashMap` +
  dirty-flag save + `JsonUtil` atomic writes). Do not introduce a database, a
  web framework, or a new persistence mechanism without explicit direction.
- **Never touch `chatserver/` data files directly** — go through the owning
  service. Never delete `chatserver/`, `cookie-secret.key`, or user data.
- **Never commit secrets** or real API keys; read them from env/system
  properties as the existing code does.
- **Keep security invariants intact:** PBKDF2 parameters, the 12-guard cookie
  scheme, path-traversal guards, and per-file access checks must not be weakened.
- **Match existing style** (4-space indent, same-line braces, Chinese
  comments/strings) and keep diffs minimal.
- **Verify before claiming done:** run `mvn clean package` and `mvn test`, and
  re-read the actual diff rather than trusting intent.
- **Update this guide** when you change architecture, routes, config, or
  conventions.
