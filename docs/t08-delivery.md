# T08 Delivery: Notice Creation, Editing and Archiving

**Date:** 22 October 2026
**Requirements baseline:** Task2 SRS v1.2, REQ-1.2/1.3; T03 and T06 shared design
**Scope:** runnable local notice module; complete platform acceptance remains open

## Implemented behaviour

The Java backend and browser interface implement the notice part of F1:

- Create Lost/Found notices with a stable ID and link, Open status, a controlled category/area, occurrence date, approximate-date flag and public description.
- Enforce title length 5–80 and description length 20–1,000 Unicode code points. Interpret dates in Asia/Macau and reject future dates.
- Accept zero to three optional JPEG/PNG images, at most 5 MB each. Decode actual image content, reject invalid files and re-encode pixels without the source filename or EXIF/GPS metadata. The current processor also resizes large images and rejects excessive dimensions with a field error.
- Persist notice revisions, current state, photos, audit records, integration events and idempotency records in the same database transaction. A failed transaction does not leave a partly saved notice or photo.
- Permit editing/archiving only by the authenticated author. The server derives that author from the session and rejects unsupported client-supplied fields.
- Check the submitted version, return a conflict for stale edits and keep the same notice ID. The interface preserves draft input while the user reviews a newer saved version.
- Archive an eligible notice with a reason. **Closed means archived, not physically Returned**. The stable detail link and change history remain available after archiving; the available list excludes archived records.
- Lock editing and archiving for Pending/Held/Approved guards or active reservations, including when the notice is the linked lost notice. Returned/Closed records are read-only; hidden records and their images are unavailable to ordinary student sessions.
- Keep ordinary revision and material revision separate. Only title, category, date, area or description changes increase the material version; photo-only changes do not. Persist events for later discovery/alert integration.
- Reuse an idempotency key for identical retries and reject a reused key with different content. The backend checks permissions and current state, rather than relying on interface buttons.

The application provides **My notices**, a basic available-notice list, current detail/history, a create/edit form and an archive form. These support T08 demonstration; they do not establish completion of the full search/filter, matching or alert features.

## Build and start

The project uses Spring Boot **3.5.16** and Maven. Its compiler release target is **21**. The recorded automated build/test execution used **JDK 25.0.2**; Java 21 runtime execution has not been separately verified.

From the repository root:

```sh
cd backend
mvn clean package
java -jar target/campus-lost-found-0.1.0.jar
```

The first Maven build needs access to the dependency repository. A successful package build produces `backend/target/campus-lost-found-0.1.0.jar`; the JAR contains the backend and application UI. Open:

<http://127.0.0.1:8080/app/>

To use another local port, start the JAR with `--server.port=8081` and use that port in the browser. Stop it with Ctrl+C in the terminal. Start it again from the same `backend` directory to reuse the same database.

## Fictional local accounts

| Username | Password | Purpose |
| --- | --- | --- |
| `alice` | `campus-demo-2026` | Create and manage the first author's notices |
| `bob` | `campus-demo-2026` | Check another author's read access and denied edit/archive operations |

Spring Security creates authenticated local sessions, checks CSRF tokens for writes and invalidates the session on logout. The application only permits this known-credential configuration when `server.address` is a loopback address. It does not provide registration, university identity verification or the complete T09 account policies.

These are fictional local credentials, not credentials for any university or cloud service. The local application is intended for course testing with fictional notices.

## Data storage and transaction boundary

The verified default URL is:

```text
jdbc:h2:file:./data/notices;MODE=PostgreSQL;DB_CLOSE_ON_EXIT=FALSE
```

The path is relative to the process working directory. Starting from `backend` creates local state under `backend/data/`, including the H2 database file. This directory and build outputs are ignored by Git. Restarting with the same path preserves records; the build command does not delete this database directory.

Photos are database BLOBs. Pixel re-encoding happens before the save transaction; notice metadata, photo bytes, revision, audit/event and idempotency inserts then commit or roll back together. There is no separately served upload directory that could bypass notice visibility.

H2 PostgreSQL compatibility mode is **not PostgreSQL integration verification**. A PostgreSQL driver is present as a later integration dependency, but the current validation uses H2. The T04 PostgreSQL environment, final dependency/licence records, production storage and backup/restore verification remain separate tasks.

## Claim and matching integration boundaries

`claim_guards` and `active_reservations` supply the notice module with unresolved-claim and reservation facts. The notice service checks both the found and optional linked-lost relationship and applies the T06 lock rules. Tests insert controlled guard records directly; there is no public fixture or arbitrary-status-writing endpoint.

These tables do **not** implement claim submission, private evidence/messages, author approval, two-party confirmation or assigned moderation. Those belong to T15–T18 and must later populate the guards with the same transaction/locking discipline. Guard tests prove the T08 response to an existing condition, not that the real claim workflow creates that condition correctly.

Notice events similarly provide a later integration seam. Ranking, match alerts, preference changes and delivery are not implemented by saving an event. T12/T13 must consume events and recheck current eligibility, material versions and recipient preferences.

## API boundaries for integration

| Endpoint | Current purpose |
| --- | --- |
| `GET /api/session` | Session status and current CSRF token/header name |
| `POST /api/login`, `POST /api/logout` | Local test-account sign-in/out |
| `GET /api/options` | Controlled categories/areas and today's Asia/Macau date |
| `GET /api/notices?scope=mine&page=0` | Author's notices, paginated at 20 items |
| `GET /api/notices?scope=available&page=0` | Current available notices, excluding hidden/terminal/reserved records |
| `GET /api/notices/{id}` | Current stable detail and significant revision history |
| `POST /api/notices` | Multipart creation: JSON `notice` part and optional `photos` parts |
| `PUT /api/notices/{id}` | Multipart edit with `version` and current `retainedPhotoIds` |
| `POST /api/notices/{id}/close` | Archive with current `version` and public `reason` |
| `GET /api/notices/{id}/photos/{photoId}` | Authenticated download after notice-visibility checks |

Writes require the session's CSRF token. Notice creation, editing and archiving also require `Idempotency-Key`. The interface obtains tokens and manages retry keys; direct integration callers must preserve the same contract. Public DTOs do not contain password hashes, private claim evidence or messages.

## Recorded verification and remaining acceptance

The automated backend report records **28 tests, zero failures, zero errors and zero skips**. An executable JAR was produced successfully. Verification ran on **JDK 25.0.2**, with compiler release **21**, using an isolated test database rather than the user's persistent local notices.

Covered checks include notice lifecycle/history, Unicode boundaries, invalid fields/dates, pagination, real form login, missing authentication/CSRF, foreign ownership, forged fields, stale versions, unresolved-claim and reservation guards, terminal/hidden visibility, image validation and metadata removal, photo retention limits, idempotent retries, forced transaction failures and simultaneous edits.

This is a **T08 implementation test result**, not completion of every SRS acceptance target:

- Complete **AT-02** still includes six-volunteer reporting tasks and the 5/6-within-three-minutes target.
- Complete **AT-03** still needs integration with actual claim, return and moderation modules, not only inserted guard fixtures.
- The full multi-browser/zoom acceptance matrix, the 20-active-account capacity/performance envelope, the three-browser/zoom matrix, PostgreSQL and Java 21 runtime execution, and backup/recovery targets are not established by the 28-test report.
- This record does not claim approval from a named teammate, Team7 or a teaching assistant. Review decisions belong in their actual review records.

The GitHub Pages address serves the **static project information website**. It does not start this JAR, host the H2 data or deploy the backend. No network backend deployment is claimed here.

## Local browser verification

On 22 October, the in-app browser completed real sign-in, publication with a PNG, description editing and archiving. The same notice retained its ID and three revisions (v1/v2/v3), and disappeared from the available list. After stopping and restarting the JAR using the same H2 path and signing in again, the original link, Closed state, image and three revisions remained visible. Layout checks at 1280×800 and 360×800 found no horizontal page overflow. These are local smoke checks, not the full multi-browser, performance or volunteer acceptance matrix. See [the Chinese delivery and test record](T08_测试与交付说明.txt).
