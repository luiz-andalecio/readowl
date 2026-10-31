# 🦉 Readowl

A web platform for publishing and reading books, focused on supporting amateur literature in Brazil and connecting readers with writers.

The project will be built with **Java, Spring Boot, Angular, TypeScript, and PostgreSQL**, featuring a responsive Portuguese-language interface and free publishing as its initial offering.

> **Status:** the backend foundation, accounts, publishing, administration, public reading, catalog, and library are implemented; community and operations endpoints are not. See [backend/readowl/README.md](backend/readowl/README.md) for what runs today and [docs/backend-api-contract.md](docs/backend-api-contract.md) for the current HTTP contract. Unless stated otherwise, the features and commands in this document describe the project to be implemented.

---

## 📋 About the Project

Readowl will provide a space for writers to publish their works, organize volumes and chapters, and receive reader feedback. Readers will be able to discover stories, follow updates, and maintain a personal library.

The platform will prioritize clear navigation, a comfortable reading experience, and publishing tools that preserve authors' content.

### Goals

- Make independent authors' works easier to publish and discover.
- Organize stories into books, volumes, and chapters.
- Allow readers to follow works and interact with their authors.
- Provide an accessible interface on desktops, tablets, and mobile devices.
- Offer reporting and moderation tools.
- Protect accounts, drafts, and personal data.

### Initial Responsibility

| Name | Responsibility |
| --- | --- |
| Luiz Alberto Cury Andalécio | Project conception and lead development |

Additional contributions and responsibilities will be documented as the project team is established.

---

## 🎯 Planned Features

### Accounts and Profiles

- Email and password registration, email verification, and account recovery.
- Google sign-in through OpenID Connect.
- Public profiles with display name, avatar, biography, and published works.
- Self-service account editing and session termination.
- A single account can act as both reader and author.

### Publishing

- Creation and editing of books, synopses, covers, genres, and tags.
- Volume and chapter organization with explicit ordering.
- Rich text editor with formatting suitable for narrative writing.
- Draft autosaving, save-status feedback, and conflict handling.
- Separation between the draft being edited and the published revision.
- Chapter publishing and unpublishing.
- Revision history with a retention policy to be defined.
- Confirmation before deletion and other irreversible operations.

### Reading and Discovery

- Home page with featured works and recent updates.
- Catalog with search, genre and tag filters, work status, and sorting.
- Book pages with synopsis, author, volumes, and available chapters.
- Reader with chapter navigation, font adjustments, spacing, and themes.
- Readable URLs for works, chapters, and public profiles.
- Personal library for following works and tracking reading progress.

### Community

- Comments on books and chapters.
- Book ratings and chapter reactions.
- Notifications about new chapters in followed works.
- Content reports and administrative tracking of their resolution.
- Controls against spam and abusive repetition of interactions.

### Administration and Statistics

- Account management and moderation actions according to permissions.
- Records of administrative actions and sensitive changes.
- Reading statistics for authors.
- Public aggregate book view counts.

### Initial Scope

The MVP will include accounts, publishing, reading, catalog, library, comments, ratings, basic notifications, and essential moderation. Payments, subscriptions, AI recommendations, real-time collaborative editing, and a mobile application will require further planning.

---

## 🛠️ Main Stack

### Backend

| Technology | Purpose |
| --- | --- |
| Java 25 LTS | Main backend language |
| Spring Boot 4.1.2 | Application configuration and runtime |
| Spring MVC | REST API under `/api/v1` |
| Spring Security | Authentication and authorization |
| Spring OAuth2 Client | Google sign-in through OpenID Connect |
| Spring Session JDBC | Session store in PostgreSQL |
| Spring Data JPA / Hibernate | Persistence and transactions |
| Jakarta Bean Validation | DTO validation |
| Flyway | Versioned database migrations |
| Spring Mail | Transactional email service integration |
| Spring Boot Actuator | Health checks and operational metrics |
| Maven Wrapper | Reproducible builds |
| Lombok | Compile-time code generation through annotation processing |
| Spring Boot DevTools | Development-time restart support |

Spring Security, Hibernate, and other managed dependency versions will follow the Spring Boot BOM. Additional libraries will require compatibility checks.

### Backend Build Configuration

The supplied `pom.xml` establishes the following configuration:

| Setting | Value |
| --- | --- |
| Build tool | Maven |
| Parent | `org.springframework.boot:spring-boot-starter-parent:4.1.2` |
| Group ID | `br.com.readowl` |
| Artifact ID | `readowl` |
| Application version | `0.0.1-SNAPSHOT` |
| Java target | `25` |
| Packaging | JAR, Maven's default when packaging is omitted |
| Application packaging plugin | `spring-boot-maven-plugin` |
| Compiler plugin | `maven-compiler-plugin` |
| Annotation processor | Lombok for main and test compilation |

`0.0.1-SNAPSHOT` identifies the application as a development version; the Spring Boot parent remains a stable release. The POM currently leaves name, description, URL, license, developer, and SCM metadata empty. These fields will be completed when their values are established. Package layout and YAML configuration are project conventions, not settings verified by this POM.

### Dependencies Included in the POM

Unless stated otherwise, Spring Boot artifacts below use the `org.springframework.boot` group. Versions are managed by the Spring Boot parent.

| Dependency | Purpose / Scope |
| --- | --- |
| `spring-boot-starter-actuator` | Health checks and operational metrics |
| `spring-boot-starter-data-jpa` | JPA persistence and Hibernate integration |
| `spring-boot-starter-flyway` | Flyway integration |
| `org.flywaydb:flyway-database-postgresql` | PostgreSQL-specific Flyway support |
| `spring-boot-starter-mail` | Email integration |
| `spring-boot-starter-security` | Authentication, authorization, and CSRF |
| `spring-boot-starter-security-oauth2-client` | OAuth2 / OpenID Connect client support |
| `spring-boot-starter-session-jdbc` | JDBC-backed session support |
| `spring-boot-starter-validation` | Jakarta Bean Validation |
| `spring-boot-starter-webmvc` | Servlet-based REST API |
| `spring-boot-devtools` | Runtime scope; optional development dependency |
| `org.postgresql:postgresql` | Runtime PostgreSQL driver |
| `org.projectlombok:lombok` | Optional compile-time dependency and annotation processor |

The POM also includes these test-scoped dependencies:

- `spring-boot-starter-actuator-test`
- `spring-boot-starter-data-jpa-test`
- `spring-boot-starter-flyway-test`
- `spring-boot-starter-mail-test`
- `spring-boot-starter-security-oauth2-client-test`
- `spring-boot-starter-security-test`
- `spring-boot-starter-session-jdbc-test`
- `spring-boot-starter-validation-test`
- `spring-boot-starter-webmvc-test`
- `spring-boot-testcontainers`
- `org.testcontainers:testcontainers-junit-jupiter`
- `org.testcontainers:testcontainers-postgresql`

Dependency declarations provide tooling support; they do not mean the corresponding features or tests have been implemented. S3 integration is not declared in the POM and remains an implementation task. Bouncy Castle (`bcprov-jdk18on`) provides the Argon2id implementation and is the one dependency whose version is pinned in the POM, because the Spring Boot BOM does not manage it.

### Session Store

Sessions are stored in PostgreSQL through Spring Session JDBC, the only session starter in the POM. Introducing Redis later for rate limiting or caching does not require Redis-backed sessions.

### Frontend

| Technology | Purpose |
| --- | --- |
| Angular 22 | Feature-based web application |
| TypeScript | Frontend typing and development |
| Angular Router | Routing and lazy loading |
| Angular SSR | Hybrid page rendering |
| Reactive Forms | Forms and validation for usability |
| HttpClient | API communication |
| Signals | Local and derived UI state |
| RxJS | Asynchronous flows and request composition |
| Tailwind CSS | Styling and visual identity |
| Angular CDK | Accessibility and interaction utilities |
| Tiptap | Chapter editor |

The editor will be encapsulated in a dedicated component. Angular integration, SSR compatibility, and extension licenses will be validated before final adoption. The editor will initialize in the browser.

### Data and Services

| Technology | Purpose |
| --- | --- |
| PostgreSQL | Relational data, chapters, and sessions |
| Full-text search and `pg_trgm` | Text search and similarity |
| S3-compatible storage | Covers and avatars |
| CDN, when adopted | Public image delivery |
| Redis, as needed | Distributed limits, deduplication, and caching |
| Transactional email service | Verification and account recovery delivery |

Redis is not part of the current stack and is not required at runtime. It may be introduced for distributed limits, deduplication, or caching when a need is demonstrated. Security controls must not silently disappear during failures.

### Infrastructure and Quality

- Docker and Docker Compose for reproducible environments.
- NGINX as the reverse proxy and HTTPS entry point.
- Git and Pull Request reviews.
- Build, test, and dependency analysis pipeline.
- JUnit and Testcontainers integration tests for the backend.
- Component tests using tools compatible with the selected Angular version.
- Playwright for end-to-end tests.
- API contract maintained in Markdown ([docs/backend-api-contract.md](docs/backend-api-contract.md)).
- ESLint and Prettier for the frontend; Java formatting standards enforced during the build.

### Version Policy

The backend POM pins Spring Boot to **4.1.2** and requires Java **25**. At milestone M0, the exact JDK distribution and patch, Maven Wrapper version, Angular, Node.js, TypeScript, PostgreSQL, and remaining dependencies will be pinned. Node.js 24 LTS is the proposed initial line for builds and SSR, subject to Angular's compatibility matrix.

The repository will include Maven Wrapper, a frontend lockfile, and versioned container images. Production will not rely on `latest` tags.

---

## 🏗️ Architecture

The backend will be a **modular monolith**, organized by feature. The frontend and API can evolve separately while maintaining a documented contract.

| Module | Responsibility |
| --- | --- |
| `user` | Users, roles, and public profiles |
| `auth` | Registration, authentication, sessions, and recovery |
| `genre` | Genres and tags |
| `book` | Books and discovery |
| `publishing` | Volumes, chapters, drafts, and revisions |
| `library` | Followed works and reading progress |
| `community` | Comments, ratings, reactions, and chapter views |
| `banner` | Home page banners |
| `notification` | Internal alerts and email delivery |
| `moderation` | Reports and actions on accounts and content |
| `media` | Image uploads and processing |
| `shared` | Cross-cutting configuration, errors, and security |

Each module will organize its endpoints, use cases, domain rules, and persistence. JPA entities will not be exposed directly through the API. DTOs will define input and output data.

NGINX will route `/api` to Spring Boot, SSR pages to the Angular rendering process, and static files to their appropriate destinations. The database and internal services will not be directly exposed to the public in production.

### Rendering Strategies

| Page | Strategy |
| --- | --- |
| Home, catalog, and search | SSR |
| Books, published chapters, and public profiles | SSR with controlled caching |
| About, terms, and privacy policy | SSG / prerendering |
| Editor, personal library, and administration | CSR |

SSR and SSG will provide HTML for the initial request; after hydration, interactions and navigation can take place in the browser. The Node.js SSR process will handle rendering, while business rules will remain in the Java backend.

Comments, reactions, and notifications may be fetched in the browser after the main content. Publishing, editing, and removing content must invalidate the corresponding caches. Personalized responses and drafts must not enter public caches.

### SEO and URLs

- Work- and chapter-specific titles and descriptions.
- Open Graph metadata for sharing.
- Canonical URLs, sitemap, and indexing rules.
- Appropriate HTTP responses for missing or unavailable pages.
- Slugs that follow the name: a book uses its author-chosen acronym plus its title (`sra-shihais-remanescente-da-aura`), and renaming a book or chapter changes its URL.
- Redirects when an address change is necessary.
- Drafts and private areas excluded from indexing, in addition to server-side access protection.

---

## 🗃️ Persistence and Content

### Initial Model

The model will cover users, login identities, books, volumes, chapters, revisions, genres, tags, library relationships, reading progress, comments, ratings, reactions, notifications, reports, media, and audit records.

- Books will have an explicit relationship with their author.
- Volumes will belong to a book; chapter ownership relationships will be validated.
- Ordering, uniqueness, and referential integrity will be enforced in the database where applicable.
- Dates will be stored as UTC instants and displayed according to the UI context.
- Queries will have pagination and defined limits.
- Indexes will be selected according to queries and verified through measurements.
- Optimistic locking with `@Version` will protect concurrent changes.
- Hibernate will validate the production schema; changes will use Flyway migrations.

### Chapters and Revisions

The editor's structured document will be stored in JSONB with a format version. Plain text and reading HTML will be derived from the same revision through a controlled process.

The server will validate allowed elements and sanitize HTML. Scripts, event handlers, and disallowed URLs will be rejected or removed according to the content policy.

Saving a draft will not replace the published revision. Publishing will promote a validated revision to public content. The interface will report conflicts rather than silently overwrite work from another tab.

The MVP will explicitly define draft, published, and unpublished states, along with moderation restrictions. Revision deletion and retention will have their own rules.

### Search

Initial search will use PostgreSQL, relational filters, full-text search, and `pg_trgm` similarity indexes as needed. Only content visible to the requester may appear in results.

An external search engine will be considered if measurements demonstrate a need for additional features or performance.

---

## 👀 Chapter View Rules

- Published chapters will be publicly readable.
- Only authenticated visits will be eligible for view counting.
- The author's own views will not count.
- At most one view per user and chapter will count every two minutes.
- The user-agent filter may ignore known crawlers, but will not be treated as sufficient abuse protection.
- The authoritative counter will remain in PostgreSQL.
- Deduplication must be atomic and consistent across instances.
- Aggregate book view counts will be public.
- Individual chapter counts will be restricted to the author and authorized administrators.

The event will indicate an eligible chapter visit, not proof of completed reading. This feature will not store IP addresses as view identifiers; operational abuse controls will have separate purposes and retention rules.

SSR will not record views. The browser will submit the event after opening the chapter, and the API will determine eligibility.

---

## 🔐 Security and Privacy

### Authentication

- Server-side sessions stored in PostgreSQL through Spring Session JDBC.
- HttpOnly session cookie, Secure in production, and SameSite=Lax, without a Domain attribute.
- Session identifier rotation on login and revocation on logout.
- Inactivity timeouts, maximum lifetime, and a stay-signed-in option will be defined before implementation.
- Passwords protected with Argon2id and parameters calibrated for the runtime environment.
- Google sign-in validated by the backend, with identity linking subject to secure confirmation.
- Random, single-use recovery tokens stored as hashes with expiration.
- Password changes or account suspension will revoke the relevant sessions.

The frontend and API will be exposed under the same origin. CSRF will protect state-changing operations, including login and logout. The CSRF token readable by Angular will be separate from the HttpOnly authentication cookie.

### Authorization

Decisions will combine role, resource ownership, and content state.

| Profile | Planned Access |
| --- | --- |
| Visitor | Reading and discovering public content |
| Authenticated user | Library, interactions, and publishing their own works |
| Moderator | Handling reports and authorized moderation actions |
| Administrator | Full access to the content of any author, plus genres and tags; can view users but not edit them |
| Root | Everything an administrator can do, plus editing users, including their roles |

Drafts are accessible only to their authors and to administrator and root accounts, which have full access to all content.

Checks will apply to endpoints, services, queries, downloads, and asynchronous tasks. Angular guards will support navigation; effective authorization will be enforced by the server.

### Protections and Data Handling

- HTTPS in production and secrets kept outside the repository and images.
- Per-account and per-IP limits for login, recovery, uploads, and interactions.
- Recovery responses that do not reveal whether an account exists.
- Server-side validation and safe handling of displayed content.
- Logs without passwords, cookies, tokens, or private chapter text.
- Administrative audits with actor, action, resource, outcome, UTC timestamp, and correlation.
- Personal data policy, retention, and request-handling procedures defined before public operation.
- Backups and restore drills, with RPO and RTO defined and demonstrated according to the provisioned infrastructure.

---

## 🖼️ Images and Uploads

Covers and avatars will be stored as objects, with metadata in PostgreSQL. The MVP will accept explicitly allowed raster formats.

- File size, dimension, and pixel-count limits.
- Actual file content verification beyond the extension and declared MIME type.
- Image reprocessing and removal of unnecessary metadata.
- Server-generated object names.
- Optimized variants for different screen sizes.
- Public images eligible for CDN delivery; private materials subject to access authorization.
- Controlled cleanup of unreferenced objects.

Images will use versioned keys to avoid stale cached content. Storage credentials will never be sent to the browser.

---

## 🔔 Notifications and Asynchronous Processing

Internal notifications will be persisted and polled periodically by Angular, with pagination and read status.

Publishing a chapter may record an outbox event in the same transaction. A worker with limited concurrency will produce notifications in batches and process email deliveries.

Tasks will support idempotency, limited retries, and failure records. Publishing will not depend on immediate email delivery. WebSocket will not be an MVP requirement.

---

## ♿ Interface and User Experience

- Portuguese-language interface and responsive layout.
- Keyboard navigation, visible focus, and accessible labels.
- Adequate contrast and error messages associated with their fields.
- Loading, empty, success, and failure states.
- Reading preferences for font, spacing, and theme.
- Text formatting preserved between editing and publishing.
- Protection against accidental navigation with unsaved changes.
- Clear confirmations for irreversible actions.

The reading experience will be validated with long chapters and mobile devices, in addition to registration and administration screens.

---

## 📁 Proposed Repository Structure

```text
readowl/
├── backend/readowl/
│   ├── src/main/java/br/com/readowl/readowl/
│   │   ├── auth/
│   │   ├── user/
│   │   ├── genre/
│   │   ├── book/
│   │   ├── publishing/
│   │   ├── library/
│   │   ├── community/
│   │   ├── banner/
│   │   ├── notification/
│   │   ├── moderation/
│   │   ├── media/
│   │   └── shared/
│   ├── src/main/resources/
│   │   └── db/migration/
│   ├── src/test/
│   ├── pom.xml
│   ├── mvnw
│   └── mvnw.cmd
├── frontend/
│   ├── src/app/
│   │   ├── core/
│   │   ├── shared/
│   │   └── features/
│   ├── public/
│   ├── angular.json
│   └── package.json
├── infra/
│   └── nginx/
├── docs/
│   ├── architecture/
│   ├── api/
│   └── decisions/
├── compose.yaml
├── .env.example
└── README.md
```

The `shared` directory will contain only cross-cutting resources. Each feature's rules will remain in its corresponding module.

---

## 🚀 Local Development

> The following workflow is the proposed contract for the future scaffold. Files, services, variables, and scripts must be implemented and verified at milestone M0 before these commands can be used as executable setup instructions.

### Prerequisites

- JDK 25 LTS, also selected in the IDE, Maven runtime, CI, and backend container.
- Node.js 24 LTS in a version compatible with the pinned Angular release.
- npm.
- Docker Engine or Docker Desktop with Compose v2.
- Git.
- IDE with Java and Angular support.

### 1. Get the Repository and Prepare Configuration

```bash
git clone <REPOSITORY_URL>
cd readowl
cp .env.example .env
```

On Windows, copy the file using File Explorer or `Copy-Item .env.example .env` in PowerShell.

### 2. Configure Services

The `.env.example` file will document at least the following variables. Their mapping to Spring and Compose will be implemented in the scaffold:

| Group | Proposed Variables |
| --- | --- |
| Database | `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` |
| Application | `APP_PUBLIC_URL`, `SPRING_PROFILES_ACTIVE` |
| Google | `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` |
| Email | `SMTP_HOST`, `SMTP_PORT`, `SMTP_USER`, `SMTP_PASSWORD`, `MAIL_FROM` |
| Objects | `S3_ENDPOINT`, `S3_REGION`, `S3_BUCKET`, `S3_ACCESS_KEY`, `S3_SECRET_KEY` |
| Redis, if enabled | `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD` |

Spring Boot will not load `.env` automatically. Compose and local scripts must explicitly provide variables to the processes. Actual secrets will not be included in `.env.example`.

### 3. Start Dependencies

Compose will provide PostgreSQL, S3-compatible storage, and a local email inbox. The proposed service names are `postgres`, `object-storage`, and `mailpit`.

```bash
docker compose up -d postgres object-storage mailpit
```

Optional services can be started through profiles. Development ports will bind to localhost where possible.

### 4. Verify and Start the Backend

Follow [backend/readowl/README.md](backend/readowl/README.md), which documents the implemented setup: database creation, the `.env` file, and the build, test, and run commands.

### 5. Start the Frontend

In another terminal, from the project root:

```bash
cd frontend
npm ci
npm start
```

Angular CLI and its development proxy will be configured to forward `/api` and the necessary authentication routes to the backend.

### Planned Addresses

| Service | Development Address |
| --- | --- |
| Frontend | `http://localhost:4200` |
| Backend | `http://localhost:8080` |
| API | `http://localhost:8080/api/v1` |

The interface will use relative API URLs. Google sign-in will use the callback configured by Spring Security, exposed through the same public origin; the exact address will be documented once the registration ID and proxy routes are defined.

Development emails will be captured by the local inbox. Demo data will be restricted to the development environment.

---

## 🧪 Testing and Quality

### Backend

Integration tests run against real PostgreSQL, either a dedicated local database or Testcontainers, which requires Docker. See [backend/readowl/README.md](backend/readowl/README.md).

- Domain rule and use case tests.
- Integration tests with real PostgreSQL through Testcontainers.
- Authentication, authorization, CSRF, and account recovery checks.
- Transactional publishing, editing conflicts, and view deduplication.
- Migration validation on an empty database and upgrades to an existing database.

Command:

```bash
cd backend/readowl
./mvnw verify
```

### Frontend and End-to-End

- Form, component, and error-state tests.
- Registration, login, publishing, reading, and moderation flows with Playwright.
- Rendered HTML, metadata, and public page response checks.
- Keyboard, focus, contrast, and mobile reading checks.
- Editor compatibility tests with long chapters and pasted content.

The `test`, `lint`, `build`, and `test:e2e` scripts will be defined in `package.json`. The pipeline must run them as each test suite becomes available.

### Minimum Acceptance Criteria

- Users cannot edit other authors' works or access drafts without permission.
- Drafts do not silently change the published version.
- Published content can be accessed directly by URL and appears in the initial HTML.
- Logout and account suspension invalidate the intended sessions.
- Views follow eligibility and deduplication rules.
- Core flows work on small screens and through keyboard navigation.
- Backups can be restored in a documented drill.

---

## ☁️ Deployment and Operations

- Separate development, staging, and production environments.
- Containers without elevated privileges, with resources sized through testing.
- NGINX exposed over HTTPS; Spring Boot and Angular SSR on an internal network.
- PostgreSQL, storage, and Redis without public administrative ports.
- Secrets supplied by the runtime environment.
- Immutable images, compatible migrations, and documented rollback procedures.
- Monitoring of availability, errors, latency, queues, and resource usage.
- Restricted operational endpoints, without indiscriminate Actuator exposure.
- Database backups and object protection according to the retention policy.

Provider selection, budget, capacity, and availability commitments will follow measurements. Docker Compose will be the initial operational foundation; additional orchestration will depend on demonstrated need.

---

## 🗓️ Development Milestones

| Milestone | Planned Deliverables |
| --- | --- |
| M0 — Foundation | Versions, scaffold, database, migrations, initial contract, and pipeline |
| M1 — Accounts | Registration, verification, login, Google, and recovery |
| M2 — Publishing | Books, volumes, editor, drafts, and publishing |
| M3 — Reading | SSR, reader, catalog, search, URLs, and SEO |
| M4 — Community | Library, comments, ratings, reactions, and views |
| M5 — Operations | Notifications, reports, administration, and observability |
| M6 — Launch | Acceptance testing, load testing, accessibility, restoration, and deployment |

An initial validation will cover login, chapter creation, publishing, and SSR reading to verify integration before expanding the screens.

---

## 📓 Commit Convention

The project will adopt Conventional Commits:

```text
<type>(<scope>): <short description>
```

| Type | Usage |
| --- | --- |
| `feat` | New feature |
| `fix` | Behavior correction |
| `docs` | Documentation |
| `style` | Formatting without behavior changes |
| `refactor` | Restructuring without functional changes |
| `perf` | Performance |
| `test` | Tests |
| `build` | Build and dependencies |
| `ci` | Pipeline |
| `chore` | Maintenance |

Examples:

```text
feat(publishing): add chapter draft saving
fix(security): deny access to another author's draft
docs(setup): document local environment
```

---

## 🪢 Branches and Pull Requests

- `main`: stable version intended for production.
- `dev`: integration of development deliveries.
- Task branches: short lowercase descriptions separated by hyphens.

Examples: `login-form`, `chapter-editor`, `catalog-search`.

### Contribution Workflow

```bash
git checkout dev
git pull origin dev
git checkout -b chapter-editor
```

After implementing and verifying the task:

```bash
git add <TASK_FILES>
git commit -m "feat(publishing): add chapter editor"
git push -u origin chapter-editor
```

Pull Requests will target `dev` and describe the problem, delivered behavior, and validation performed. Stable deliveries will be promoted from `dev` to `main` after review and checks.

Secrets, production personal data, and environment files will not be committed to Git.

---

## 📚 Documentation and Decisions

The `docs/` directory will contain requirements, the data model, API contract, architecture decisions, operational instructions, and acceptance criteria.

Decisions on authentication, publishing states, revision retention, popularity metrics, and usage limits will be recorded before implementation. Relevant changes must update the README and related documents.

### Technical References

- [Spring Boot System Requirements](https://docs.spring.io/spring-boot/system-requirements.html)
- [Spring Security](https://docs.spring.io/spring-security/reference/index.html)
- [Angular Version Compatibility](https://angular.dev/reference/versions)
- [Angular Rendering Strategies](https://angular.dev/guide/routing/rendering-strategies)
- [Tiptap Documentation](https://tiptap.dev/docs/editor/getting-started/overview)
- [PostgreSQL Documentation](https://www.postgresql.org/docs/)

---

## 📄 License

The code license will be defined before public distribution. Rights to published works will remain with their respective holders, according to the terms of use to be established. Making a work available for reading will not imply general permission to reproduce or redistribute it.
