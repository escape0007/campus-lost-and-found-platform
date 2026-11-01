# Campus Lost and Found Platform

Software Engineering course project by **D1-Team8 · 菜鸟驿站**, Macau University of Science and Technology.

This repository contains the project information website, delivery records and a runnable Java application for accounts, notice publishing, search, ranked suggestions and in-site alerts. The separately delivered claim/return/moderation source is preserved under `submissions/t15-t18/` for T19 integration.

**Primary course repository:** [MUST-Workspace/D1-team8-project](https://github.com/MUST-Workspace/D1-team8-project)  
**Personal mirror for this sync:** [escape0007/campus-lost-and-found-platform](https://github.com/escape0007/campus-lost-and-found-platform)  
**Project information website:** <https://escape0007.github.io/campus-lost-and-found-platform/>

## Current status — 1 November 2026

The Feishu task records and the project owner's confirmation record **18 of 35 tasks complete (51.4%)**. **W2 and W3 are complete**; that confirmation was received on 1 November, which is not an inferred historical completion date. These figures track task delivery, not end-to-end acceptance.

| Week | Completed tasks | Delivery status |
| --- | ---: | --- |
| W1 | 5/5 · 100% | Completed; documentation/planning finished 19 October |
| W2 | 7/7 · 100% | Completed; T06–T11 and T35 |
| W3 | 4/4 · 100% | Completed; T15–T18 source delivered, T19 integration pending |
| W4 | 2/4 · 50% | T12/T13 completed early; T14/T19 not started |
| W5 | 0/10 · 0% | Not started |
| W6 | 0/5 · 0% | Not started |

- **Runnable main application:** T08–T13 cover notices, account access, flow-recovery fixes, combined search, explained matching, alert preferences and deduplication. T11–T13 were integrated from the cumulative Feishu source attachments without removing the T10 fixes.
- **Verification on 1 November:** `mvn verify` passed **59 backend tests**, with zero failures, errors or skips; **12 frontend VM regression scenarios** passed. An executable JAR was built. These checks do not constitute six-person usability, held-out matching evaluation or full workflow acceptance.
- **W3 delivery:** [T15–T18 source archive](submissions/t15-t18/README.md) preserves the supplied code. It is a separate implementation based on an older snapshot. T19 must reconcile shared notice state, reservations, concurrent approval, dispute checks and assigned-reviewer access before integrating it into `backend/`.
- **Requirements:** Task2 SRS v1.2, 18 October 2026, remains the baseline. T35 is marked complete in the tracker; this sync does not invent customer approval for policy choices without their decision evidence.
- **Environment:** Java compiler release 21, verified with JDK 25.0.2, Maven 3.9.16, Spring Boot 3.5.16 and H2. A Java 21 runtime, PostgreSQL, network deployment and the full acceptance matrix still need separate verification.

See the [complete 35-task snapshot, provenance and evidence](docs/progress-2026-11-01.md), [current project plan](docs/project-plan.md), and [change log](CHANGELOG.md). The [25 October T10 report](docs/t10-review.md) remains a historical record of that review's scope.

## First-release scope and owners

All F1–F5 outcomes remain required. The SRS maps them to 11 Must functional requirements, REQ-1.1–REQ-1.11.

| Outcome | Planned capability | Owner | Reviewer |
| --- | --- | --- | --- |
| F1 — Reporting and records | Create, update and archive notices; required-field guidance and history | 庄薇琪 | 刘昱彤 |
| F2 — Discovery and alerts | Manual search and filters, explained ranked matches, alert preferences and deduplication | 刘昱彤 | 姚宇佳 |
| F3–F5 — Claims, returns and moderation | Private evidence and conversation, claim decisions, single approval, two-party return confirmation and dispute review | 姚宇佳 | 庄薇琪 |

The planned dependency order is **accounts, reporting and manual search → claims, returns and moderation → full integration**. Matching and alert modules were delivered early; the complete return journey still depends on T19. All members contribute to design, implementation, testing, documentation and demonstration; the six-week effort remains approximately 144 person-hours.

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

- [Current progress, all tasks and source provenance](docs/progress-2026-11-01.md)
- [T15–T18 source delivery and integration findings](submissions/t15-t18/README.md)
- [T08 implementation, verification and local run guide](docs/t08-delivery.md)
- [Project plan and requirements summary](docs/project-plan.md)
- [Change log](CHANGELOG.md)

These summaries omit student numbers, private contact details, internal progress links and the original course documents.
