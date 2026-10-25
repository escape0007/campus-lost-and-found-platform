# Campus Lost and Found Platform

Software Engineering course project by **D1-Team8 · 菜鸟驿站**, Macau University of Science and Technology.

This repository contains the project information website, planning summaries, and a runnable local Java implementation of **T08: notice creation, editing and archiving**. The complete planned product also includes discovery, private claims and confirmed collection.

**Project website:** <https://escape0007.github.io/campus-lost-and-found-platform/>

## Current status — 25 October 2026

- Requirements baseline: **Task2 User Requirements Specification (SRS), v1.2, 18 October 2026**.
- **W1 documentation and planning: 5/5 tasks delivered and reviewed successfully (100%), completed 19 October 2026.** T01–T05 cover requirements analysis, first-release scope, shared notice/state design, ownership/reuse planning, and the project website/plan update.
- **T08 is implemented locally** with a browser interface, authenticated author checks, persistent notices/photos, stable links, revision history, version conflicts and guarded archiving. **28 automated backend tests passed** with zero failures, errors or skips; an executable JAR was built.
- The build targets **Java 21**; the recorded build/test runtime is **JDK 25.0.2**. H2 file storage is the verified local configuration. PostgreSQL and a Java 21 runtime still require separate verification.
- **T09 is integrated from its delivered source archive**: account registration, persistent credentials, sign-in/out and authenticated browsing are available. Ordinary startup has no preset demo accounts.
- **T10 review:** 44 backend and 12 frontend VM checks passed; 9 local browser checks passed. Recovery fixes cover timeouts, post-save detail rendering, sign-out interaction and stale registration CSRF tokens. See [the T10 review](docs/t10-review.md).
- The full reporting-to-manual-search flow is **not released**: T11 search/filter implementation, six-person AT-02/04 evaluation and actual T15–T18 claim/return/moderation integration remain open.
- W1's planned deadline remains **20 October 2026**; its documentation and planning work was completed on 19 October. The other five week boundaries remain unchanged.

See the [T08 delivery and local run guide](docs/t08-delivery.md), [six-week project plan](docs/project-plan.md) for responsibilities, outstanding customer decisions and future acceptance work, and the [change log](CHANGELOG.md) for the reasons behind this update.

## First-release scope and owners

All F1–F5 outcomes remain required. The SRS maps them to 11 Must functional requirements, REQ-1.1–REQ-1.11.

| Outcome | Planned capability | Owner | Reviewer |
| --- | --- | --- | --- |
| F1 — Reporting and records | Create, update and archive notices; required-field guidance and history | 庄薇琪 | 刘昱彤 |
| F2 — Discovery and alerts | Manual search and filters, explained ranked matches, alert preferences and deduplication | 刘昱彤 | 姚宇佳 |
| F3–F5 — Claims, returns and moderation | Private evidence and conversation, claim decisions, single approval, two-party return confirmation and dispute review | 姚宇佳 | 庄薇琪 |

Implementation is planned in this order: **accounts, reporting and manual search → claims, returns and moderation → matching and alerts**. All members contribute to design, implementation, testing, documentation and demonstration; the six-week effort remains approximately 144 person-hours.

A match is a suggestion, not ownership proof. An item becomes **Returned** only after both handover parties confirm and any outstanding dispute is resolved. **Closed** means archived and does not certify physical return. Private cases are restricted to their participants and an assigned moderator under the current requirements baseline.

Automatic photo recognition, native mobile apps, payments, delivery, live tracking and WeChat/private-message integration are outside the first release. University single sign-on, official staff participation and paid hosting are not project dependencies.

## Run the integrated application locally

Use Maven and a JDK; the recorded verification used **JDK 25.0.2** with compiler release **21**. From the repository directory:

```sh
cd backend
mvn clean package
java -jar target/campus-lost-found-0.1.0.jar
```

Open <http://127.0.0.1:8080/app/>. The first Maven build downloads dependencies. Register a fictional account, then sign in. Ordinary startup has no preset accounts; `alice` and `bob` are test fixtures only. The application binds to loopback and does not verify campus identity.

When started from `backend`, H2 persists notices and re-encoded photos under **`backend/data/`**. Keep that directory to preserve data between restarts. The backend and static information website are separate: **GitHub Pages does not run or deploy this backend**.

## Preview the information website locally

From the repository root (not `backend`), run:

```sh
python3 -m http.server 8000 --bind 127.0.0.1
```

Open <http://127.0.0.1:8000/>. This serves the static project website; it does not start a lost-and-found application backend.

## Public documentation

- [T08 implementation, verification and local run guide](docs/t08-delivery.md)
- [Project plan and requirements summary](docs/project-plan.md)
- [Change log](CHANGELOG.md)

These summaries omit student numbers, private contact details, internal progress links and the original course documents.
