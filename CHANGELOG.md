# Change Log

Changes to the public project information and delivery plan. Dates use the project's Asia/Macau calendar. Planning updates do not imply implemented features or passed acceptance tests.

## 2026-11-01 — Feishu progress and source reconciliation

- Recorded the project owner's confirmation that W2 and W3 are complete: W1 5/5, W2 7/7, W3 4/4, W4 2/4, W5 0/10 and W6 0/5; total **18/35 (51.4%)**. The confirmation date does not replace an unknown actual completion date.
- Updated the 35-task snapshot, project plan and information website. Preserved the existing weekly calendar and kept delivery completion separate from integration and acceptance results.
- Integrated the cumulative T11 search, T12 ranked-match and T13 alert source attachments. Preserved T10 recovery fixes; reran **59 backend tests and 12 frontend VM scenarios**, all passing, and built the executable JAR.
- Preserved T15–T18 source deliveries separately under `submissions/t15-t18/`, including provenance and the gaps T19 must address. The running main application does not claim the archived claim/return/moderation code as integrated.
- Prepared the same content for the course and personal repositories as explicitly requested for this sync. The course repository remains the default destination for future development. Original repository histories are retained.
- Kept historical implementation reports unchanged. No deployment, customer policy approval or unexecuted acceptance result is implied.

## 2026-10-25 — T13 new-match alerts

- Added an in-site alert preference for REQ-1.6. New-match alerts is the proposed default and remains unconfirmed. Manual checking records no new alert.
- Existing alerts stay readable in manual mode and after a notice is closed. Search, suggestions and the notice itself stay available. There is no private case module in this task.
- Turning alerts back on reads the current commit order and does not replay earlier events. A delayed retry still uses the event's stored trigger order.
- The same user, lost notice, found notice and both material versions produce one alert. A photo-only or timestamp-only change does not use a new version. A qualifying alert is written after the notice commit and is readable within the 60-second budget.
- Claim, return and moderation workflows remain unimplemented. The 16-of-20 unmatched-case AT-06 protocol is not claimed as passed.

## 2026-10-25 — T12 ranked match suggestions

- Added explained suggestions for REQ-1.5 and PERF-03. Only Open notices of the opposite type are compared, using public category, words, area and date. Ties break by notice ID. Missing or vague detail lowers confidence without changing that order.
- A signed-in user can permanently ignore a pair. Later material edits and a later return to Open do not show that pair to the same user. Other users still see it.
- Publish, material edit and a committed return to Open are visible on the next suggestion read, inside the 10-second budget. Matching failure keeps the notice and manual search, and does not invent a suggestion.
- Claim, return and moderation workflows remain unimplemented. The 20-case AT-05 protocol is not claimed as passed.

## 2026-10-25 — T11 manual search and combined filters

- Added signed-in notice search for REQ-1.4 and CAP-03: optional keyword, Lost/Found type, category, occurrence-date range, campus area and Open-only status, with unfiltered browsing still available.
- Available results stay limited to Open and Claim under review, and continue to exclude Awaiting collection, Returned, Closed, hidden notices and active reservations. Open only drops Claim under review.
- Pages default to 20 notices, reject a size above 50, keep the selected filters, and order by publication time then notice ID. Opening a notice reads its current status.
- Added filter integration coverage. The six-volunteer AT-04 timing protocol is not claimed as passed.

## 2026-10-25 — T10 flow review and T09 integration

- Integrated the delivered T09 account/source archive on the T08 notice module; ordinary startup now requires registration.
- Fixed request timeout recovery, misleading post-save errors, sign-out interaction races and stale registration CSRF recovery.
- Passed 44 backend tests, 12 frontend VM scenarios and 9 in-app browser checks, including a committed-response timeout/retry and stale-window conflict.
- Recorded unexecuted T11 search/filter checks and six-person/multi-browser acceptance; the full manual-search flow remains unreleased. See [T10 review](docs/t10-review.md).

## 2026-10-22 — T08 local notice implementation

- Added the Java/Spring Boot notice backend and browser interface for creating, editing and archiving notices, with stable links, history, author permissions, optimistic version checks and idempotent writes.
- Added optional JPEG/PNG upload validation and pixel re-encoding without source metadata. Photos are stored as database BLOBs in the same transaction as notice changes.
- Added local H2 file persistence and fictional loopback-only `alice`/`bob` sessions. The full T09 account system and PostgreSQL verification remain separate work.
- Added integration guards for unresolved claims and active reservations, including linked lost notices. These guards and test fixtures do not implement the T15–T18 claim/return/moderation workflows.
- Recorded **28 automated backend tests passed, zero failures/errors/skips**, and successful executable JAR packaging. The compiler target is Java 21; the recorded verification runtime is JDK 25.0.2, not a separately tested Java 21 runtime.
- Added the [T08 delivery and local run guide](docs/t08-delivery.md). Complete AT-02/03 user and workflow-integration acceptance remains open; a local browser lifecycle and restart-persistence check passed, while full browser/volunteer acceptance and backend deployment remain open.

## 2026-10-19 — Follow-up review and W1 progress reconciliation

- Rechecked the public site's requirements coverage, schedule, responsibilities, links and separation of planning progress from application validation.
- Reconciled the latest tracker: **T01–T05 are complete and have passed review, 5/5 (100%)**, with W1 documentation and planning completed on **19 October 2026**. The planned deadline remains **20 October**.
- Updated the website's W1 card, current-status summary and project plan to use that same scope and progress record. This does not claim implemented business features, passed acceptance tests, or approval from Team7 or a teaching assistant.
- Clarified the detailed plan's private-case access rule: the claimant, found-notice author and assigned moderator are the permitted participants.

## 2026-10-19 — T05 website and plan update

- Updated the public project summary to the **Task2 SRS v1.2** requirements baseline dated 18 October 2026.
- Recorded the reviewed planning deliverables, while separating document completion from unverified business functions and acceptance targets; the follow-up entry above records the latest full W1 status.
- Added the six-week calendar, named owners and reviewers, Team7 decisions awaiting review, future acceptance work and technical candidates in the [project plan](docs/project-plan.md).
- Kept the **20 October W1 deadline** and all other week boundaries; documented the resulting overlap with W2 rather than shifting the project calendar.
- Explained why the private return workflow precedes matching/alerts and retained all F1–F5 outcomes.
- Added local preview instructions and public documentation links. Kept student numbers, private contact details, original course files and internal tracker links out of the public summary.

## 2026-10-18 — Requirements revision and schedule decision

- Integrated the **two-response Team7 questionnaire**, collected on 18 October, into SRS v1.1. SRS v1.2 then clarified workflow rules, dispute handling, alert timing and test conditions.
- Prioritised **accounts, reporting and manual search**, followed by **claims, returns and moderation**, then **matching and alerts**. The initial customer input favoured reporting/search, although its small sample and priority-question inconsistency limit the conclusions. All 11 functional requirements and F1–F5 acceptance commitments remain in scope.
- Moved claims/returns/moderation to **W3** and matching/alerts to **W4**, reversing those two stages from the original proposal. This supports a complete manual return flow before discovery enhancements.
- Added user choice between new-match alerts and manual checking; left its default and wording for Team7 review.
- Retained sign-in, private case isolation and assigned-moderator access while mixed guest/visibility responses await clarification. Added neutral campus-help guidance without implying university partnership or sending private records.
- Changed the **W1 deadline to 20 October 2026 at the project owner's request**. W2–W6 dates were retained.
- Kept capacity, retention periods, field limits and cancellation exceptions as team proposals awaiting customer review; recorded test protocols as future work.

## 2026-10-15 — Initial requirements specification

- Prepared SRS v1.0; customer validation was pending.

## 2026-10-06 — Project proposal

- Defined the first-release F1–F5 outcomes, three-member ownership model, six-week/approximately 144-person-hour plan, and acceptance targets for reporting, discovery, matching, privacy and confirmed return.
