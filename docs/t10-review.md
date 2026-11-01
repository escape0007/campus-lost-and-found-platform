# T10 Review of Reporting and Browsing

Date: 25 October 2026. Baseline: Task2 SRS v1.2, AT-01 to AT-04 and COM-01, with the delivered T07 design and T08/T09 implementation.

## Result

The implemented account, publication and unfiltered browsing paths were reviewed. The review record is ready for teammate review. **The full reporting-to-manual-search flow is not released:** T11 keyword and combined filtering are still absent. Six-person AT-02/04 evaluation, the full browser/zoom matrix and actual claim/return integration have not been performed by this review.

## Integrated input

T09 was supplied as `T09_账号与浏览_2026-10-24.zip` rather than as a repository branch. Its SHA-256 is `d80f9214cb3f47da9d57491665008672e0b8b456e1080c378d90d3d9d3105c73`. The source continues from T08 commit `49f68461fc63926401b198bd76d2db402adafdf8`. T10 integrates that source before testing; the original delivered archive is retained separately.

Ordinary application startup now requires registration. The `alice` and `bob` fixtures exist only in tests. Registration does not confer campus verification or moderator permissions. The application remains bound to loopback and uses local H2 storage.

## Fixed during review

- Added a 15-second request timeout covering response headers and body. A timed-out write restores the form while retaining its draft and retry key; writes are never automatically replayed.
- Render the complete notice returned by a successful write. A subsequent detail-read failure no longer reports an already committed save as failed.
- Lock the workspace and navigation while signing out, and restore interaction after a failed sign-out. Successful sign-out clears the authenticated interface even if the next session probe fails.
- Refresh a stale registration CSRF token without discarding form input or silently replaying registration. Existing 401/403 recovery retains notice drafts.

## Verification

`mvn verify` passed **44 backend tests, zero failures, errors or skips**: 28 notice regressions, 8 account tests, 1 real HTTP cookie test and 7 new T10 real-session flow tests. The new tests register accounts and use actual session/CSRF headers; they cover independent authors, visibility, denied writes, version history, logout, explicit session invalidation, parameter boundaries and stable pagination.

The actual frontend source also passed **12 Node VM regression scenarios** with DOM/fetch substitutes. These isolate recovery logic; they are not browser or backend acceptance results.

In the in-app browser, **9 checks passed and 2 coverage groups remained unexecuted**. A loopback-only fault proxy withheld an already committed publication response for 18 seconds: the form recovered at its 15-second timeout and a manual retry returned the same ID, v1 and single history entry. With detail reads blocked, publication/editing still displayed their complete write results. Two windows exercised a stale-version conflict, preserved the draft and required explicit review before saving. Archiving retained the same link, image and five revisions and removed the notice from the available list. Logging out in another window invalidated an older edit session; reauthentication restored its draft. During delayed sign-out the workspace was inert and the sign-out button disabled. Layout checks found no horizontal page overflow at 1280×720 and 360×800.

The session-invalidity checks did not wait 30 wall-clock minutes. The test contract separately checks the 1,800-second inactivity setting. No real campus data was used in the browser checks.

## Reproduction

From `backend`, run `mvn verify`, then `java -jar target/campus-lost-found-0.1.0.jar`. Open `http://127.0.0.1:8080/app/`, register a fictional account and sign in. Start from the same directory to retain the local `data` database. Use a separate database path for destructive test fixtures.

Verification used macOS, JDK 25.0.2, compiler release 21, Maven 3.9.16 and Spring Boot 3.5.16. This review does not establish PostgreSQL, Java 21 runtime or Windows/browser-matrix results for the new build. The static GitHub Pages information site does not deploy the backend.

## Remaining work

T11 must implement keyword/combined filtering before the blocked search checks can be rerun. Actual claim, reservation, return and assigned-reviewer workflows must later replace guard fixtures. T07 cancellation/access wording needs reconciliation with SRS rules before those workflows are implemented; prototype and tracker completion are not runtime proof. AT-02/04 require the specified six-person protocols and cannot be marked passed using automated regressions alone.
