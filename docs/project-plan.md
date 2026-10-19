# Project Plan and Requirements Summary

**Project:** Campus Lost and Found Platform · D1-Team8 · 菜鸟驿站  
**Public summary updated:** 19 October 2026  
**Requirements baseline:** Task2 SRS v1.2, 18 October 2026

## Evidence and document status

The project proposal is dated 6 October 2026. Task2 v1.0 was drafted on 15 October; v1.1 integrated the Team7 questionnaire on 18 October, and v1.2 clarified wording, workflow exceptions, alert timing and test conditions on the same date. T03 records shared notice fields and case states; T04 confirms ownership and records reuse candidates and environment constraints.

T01–T04 are recorded as completed, with review passed, in the team's current progress tracker. This describes the planning deliverables. Application functions, component integration and acceptance results remain unverified.

The customer evidence consists of **two student responses from Team7, collected on 18 October 2026**. One respondent reported a recent loss. Both selected a phone as their likely device. Reporting and search received the most selections, while matching and alerts received none in the priority question; all F1–F5 outcomes nevertheless remain in scope. That question requested three choices per respondent but its export contains seven selections, so it does not establish a reliable full ranking. The sample does not establish campus-wide demand, performance or usability.

The six volunteers in the future usability protocol are not six completed customer interviews. No acceptance test results are reported in these source documents.

## Scope and implementation order

All **REQ-1.1–REQ-1.11 are Must requirements** for the first release:

| Requirements | Intended behaviour |
| --- | --- |
| REQ-1.1 | Accounts, sign-in/out and server-enforced permissions |
| REQ-1.2–1.3 | Create, update and archive stable notices with validation and history |
| REQ-1.4 | Manual browsing, search and consistent filters |
| REQ-1.5–1.6 | Explained ranked suggestions and optional new-match alerts with deduplication |
| REQ-1.7–1.9 | Private claims, withdrawal, case conversation and one approved claim per item |
| REQ-1.10 | Independent confirmation by both parties and atomic linked-notice closure |
| REQ-1.11 | Reports, assigned moderation, dispute handling and neutral campus-help guidance |

Build accounts, reporting and manual search first; claims, returns and moderation next; matching and alerts after the core return flow. This changes implementation order from the proposal, without dropping F1–F5 or the original acceptance targets.

The current baseline requires sign-in to browse notices. Private evidence/messages are accessible only to the claimant, found-notice author and assigned moderator. Accounts do not verify university affiliation. A suggested match is not ownership proof, and an archived **Closed** record does not imply **Returned**. Both parties must independently confirm a return; a dispute before completion blocks it.

The release uses phone/laptop browsers. Automatic image recognition, native mobile applications, payments, courier delivery, live tracking and WeChat/private-message integration remain outside scope. No university system integration, official staff involvement or paid demonstration hosting is required.

## Six-week delivery plan

| Week | Calendar dates | Planned work and evidence |
| --- | --- | --- |
| **W1 — Confirm requirements** | **12–20 October 2026** | Complete the Team7 survey analysis and SRS v1.2; agree first-release scope, notice fields, states and responsibilities; record reuse candidates; update the information website and plan. |
| **W2 — Design and reporting** | **19–25 October 2026** | Produce UML use-case/state diagrams, modules and mobile page sketches; build accounts, reporting, basic browsing and manual search; review outstanding decisions with Team7. |
| **W3 — Claims, returns and moderation** | **26 October–1 November 2026** | Implement private evidence and conversations, claim decisions, atomic single approval, two-party return confirmation, assigned review and dispute handling; demonstrate the core return flow without requiring matching. |
| **W4 — Matching, alerts and integration** | **2–8 November 2026** | Add explained ranking, alert preferences and deduplication; calibrate with practice cases; integrate manual-search and suggested-match journeys with claims and linked closure. |
| **W5 — Evaluation and revision** | **9–15 November 2026** | Freeze matching rules and reserved cases; run matching, six-volunteer, concurrent-approval, privacy and quality checks; record first results, fix failures and label retests separately. |
| **W6 — Delivery preparation** | **16–22 November 2026** | Finish the user guide, UML and installation instructions; review source and evidence; reproduce the demonstration; rehearse, prepare an offline fallback and disclose known limitations. |

The W1 deadline was changed to **20 October 2026** at the project owner's request on 18 October. W2 still starts on 19 October: the two-day overlap reflects the W1 deadline adjustment, not a shift of the other weeks. All remaining week boundaries are retained. Performance, security, accessibility and recovery work should be addressed during implementation before final verification in W5.

## Ownership and review

| Owner | Accountable outcome | Reviewer |
| --- | --- | --- |
| **庄薇琪 — team leader** | **F1:** notice creation, updates and closure, required-field prompts and history; coordinates meetings, progress, change records and delivery | 刘昱彤 |
| **刘昱彤** | **F2:** filters, ranked matching and alerts; owns matching quality and repeat-alert prevention | 姚宇佳 |
| **姚宇佳** | **F3–F5:** private evidence, decisions, collection and moderation; owns privacy and single-approval checks | 庄薇琪 |

Rotating pairs check reporting/discovery, matching/claims and returns/moderation. Pair work does not transfer the owner's accountability. Every member contributes to requirements, design, programming, tests, documentation and presentation. The proposal allocates eight hours per member per week: approximately **144 person-hours over six weeks**.

## Decisions awaiting Team7 review

| Topic | Current baseline or proposal | Confirmation still required |
| --- | --- | --- |
| Access and visibility | Sign-in required; no public contact fields; only assigned moderators may access private cases | Guest browsing, notice audiences and moderator access, because the survey choices are mixed |
| Claims and disputes | Two-party confirmation, one approval, held competing claims and assigned dispute review | Approved-cancellation rules, held-claim behaviour and dispute exceptions |
| Alerts | Users can choose new-match alerts or manual checking | Default choice and preference wording |
| Operating limits | Field/capacity limits and data-retention periods are team proposals | Final numeric values, including 90-day case-content removal, 180-day minimal audit retention and seven-day backup expiry |
| Campus assistance | Neutral guidance without transmitting private records or promising staff intervention | Verify any named service point before publishing its details |
| Mobile walkthrough | Reporting/search first, then the private return flow, then matching/alerts | Review the workflow and record the customer's decisions and their effects on requirements/tests |

The team leader records scope changes, their reasons and affected acceptance tests. An absent response is not customer approval; the current access and privacy baseline remains in effect until a reviewed decision changes it.

## Future acceptance work — targets, not results

| Area | Planned method and target |
| --- | --- |
| Reporting — AT-02 | At least **5 of 6** volunteers publish all required details within **3 minutes**, unaided. |
| Search — AT-04 | At least **5 of 6** find the correct notice; median time improves by at least **30%** against a chronological list. Three volunteers use the platform first and three the list first; both methods use a frozen time limit and comparable tasks. |
| Matching/alerts — AT-05/06 | Prepare **100 fictional notices** and reserve **20 matched + 20 unmatched cases**, separate from practice data. At least **16/20** matched cases place the correct item in the top five; at least **16/20** unmatched cases produce no alert. Freeze rules/threshold before the first run. |
| Claims, privacy and closure — AT-01/03/07–11 | Exercise rejection, pending/held withdrawal, permission checks, cancellation, retries, disputes before/after return and linked closure. Run simultaneous approval attempts **20 times on fresh cases**, with exactly one success each time; require zero dual approvals and zero unauthorised private reads. |
| Performance/capacity — AT-12 | Proposed envelope: **1,000 accounts, 2,000 notices, 10,000 case messages and 20 active concurrent accounts**. Measure 95% of queries/details and business operations within **3 seconds**; photo processing within **7 seconds** after upload; matching within **10 seconds**; eligible alerts within **60 seconds**. |
| Security — AT-13 | Check HTTPS, password hashing, protected sessions, server permissions, CSRF, safe text/uploads, private-file access and dated retention fixtures. Use fictional records. |
| Availability/recovery — AT-14 | Proposed target: **99%** during pre-agreed trial sessions, minute-by-minute probing; daily recoverable backups, seven daily versions; latest-backup restoration within **2 hours**, at most **24 hours** of data loss. |
| Compatibility/accessibility — AT-15 | Check core workflows at **360 px** and **1366 px**, keyboard use, visible focus, labels, text status and **200% zoom**; record stable Chrome/Safari/Edge versions and verify Chinese/English notice text. |
| Reproducibility — AT-16 | Version the matching configuration and supply setup instructions, fictional data and a short guide so a reviewer can reproduce the core demonstration. |

Record the first evaluation results, failures and limitations. Later fixes and retests must be identified separately. Privacy or conflicting-claim defects block a public trial. The platform records handovers but cannot guarantee physical recovery.

## Technical candidates and unresolved checks

T04 retains a **Java-based** implementation direction and records the following candidates. They have not been adopted, installed or validated by this documentation update.

| Purpose | Candidate recorded in T04 | Outstanding evidence |
| --- | --- | --- |
| Runtime/build/framework | Eclipse Temurin 21, Apache Maven, Spring Boot 3.x | Pin exact versions, archive licence texts, scan transitive dependencies and verify the environment |
| Accounts | Spring Security and BCrypt | Integrate authentication and permissions; measure a suitable BCrypt work factor and verify session behaviour |
| Images | Apache Tika and Apache Commons Imaging | Verify actual JPEG/PNG content, corruption/size checks, location-metadata removal and authorised reads |
| Storage | PostgreSQL 16 with JDBC; generated image filenames in a local directory | Design schema and transactions; verify atomic approval/closure, private-file authorisation and restore behaviour |
| Domain logic | Team-written matching, alerts, claims and returns | Implement and test the requirements rather than delegate their rules to a generic recommendation/message component |

The reference test proposal is at least **4 logical cores, 8 GB RAM and 40 GB free active-data space**. A specific machine, network and browser versions still need to be recorded. Network-accessible deployments require HTTPS; a written local demonstration fallback must remain available.

At maximum photo limits, images alone could occupy about **30 GB**. T04 estimates at least **80 GB on a separate backup volume** for an incremental approach; seven full copies are estimated at approximately **224 GB**. These are storage estimates, not purchased capacity or successful recovery tests. The active-data allowance does not cover seven full backups. Exact capacity and the two-hour restore target remain unverified.

## Source record

This public summary draws on the team's Task1 proposal (6 October 2026), Task2 SRS v1.2 (18 October 2026), T03 shared notice/state design, T04 responsibilities/reuse document, and the reviewed task statuses current on 19 October. The original course files and internal tracker are retained by the team; this repository publishes summaries without student numbers, private contact details or internal links.
