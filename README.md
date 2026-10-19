# Campus Lost and Found Platform

Software Engineering course project by **D1-Team8 · 菜鸟驿站**, Macau University of Science and Technology.

This repository hosts the project information website and public planning summaries. The planned product is a responsive website that brings campus lost-and-found notices into one searchable place and supports private verification and confirmed collection.

**Project website:** <https://escape0007.github.io/campus-lost-and-found-platform/>

## Current status — 19 October 2026

- Requirements baseline: **Task2 User Requirements Specification (SRS), v1.2, 18 October 2026**.
- **W1 documentation and planning: 5/5 tasks delivered and reviewed successfully (100%), completed 19 October 2026.** T01–T05 cover requirements analysis, first-release scope, shared notice/state design, ownership/reuse planning, and the project website/plan update.
- T03 defines fields and workflow rules. T04 confirms responsibilities and records component candidates; final component selection and environment validation remain open.
- Business features and acceptance targets have **not yet been verified**. Completed planning documents do not establish an implemented or tested application.
- W1's planned deadline remains **20 October 2026**; its documentation and planning work was completed on 19 October. The other five week boundaries remain unchanged.

See the [six-week project plan](docs/project-plan.md) for responsibilities, outstanding customer decisions and future acceptance work, and the [change log](CHANGELOG.md) for the reasons behind this update.

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

## Preview the information website locally

From the repository directory, run:

```sh
python3 -m http.server 8000 --bind 127.0.0.1
```

Open <http://127.0.0.1:8000/>. This serves the static project website; it does not start a lost-and-found application backend.

## Public documentation

- [Project plan and requirements summary](docs/project-plan.md)
- [Change log](CHANGELOG.md)

These summaries omit student numbers, private contact details, internal progress links and the original course documents.
