# GitHub Pages project showcase and demo

The repository root contains the static project showcase (`index.html`) and the six-step workflow walkthrough (`demo.html`). They use relative CSS, JavaScript, image and document links so the same files work under a GitHub Pages project path or a local static server.

The course repository, [MUST-Workspace/D1-team8-project](https://github.com/MUST-Workspace/D1-team8-project), remains the primary source. The personal repository is a mirror for this synchronization only. This change does not create a new hosted Java service.

## What visitors can do

- Read the established project scope, delivery plan and evidence on the homepage.
- Switch among six explanatory steps: publish, manually discover, privately claim, decide, review a dispute and independently confirm return.
- Inspect actual local application screenshots using fictional test data.
- Download the course repository's source ZIP or the verified v0.1.0-t19 runtime ZIP, read the T19 integration report and run the complete application locally.

The static demo has no login, API calls, analytics, cookies or browser storage. Its state cards illustrate the completed behavior; they do not submit notices or claims. All six panels remain readable if JavaScript is disabled. With JavaScript enabled, the tablist supports Left/Right, Home/End and normal Tab navigation; previous/next controls also navigate the steps.

## Local preview

From the repository root:

```sh
python3 -m http.server 8000 --bind 127.0.0.1
```

Open `http://127.0.0.1:8000/` for the showcase and `http://127.0.0.1:8000/demo.html` for the walkthrough. The static server does not start the Java backend. Full application commands are shown in the demo's download section and in `README.md`.

The public demo entry point is [the existing personal-mirror Pages site](https://escape0007.github.io/campus-lost-and-found-platform/demo.html). The course repository remains the primary source; its Pages settings require repository administrator access.

## GitHub Pages publishing

Use the repository root as the GitHub Pages source when repository settings and access allow it. `.nojekyll` keeps this buildless site as plain static files. No npm installation, frontend build or third-party hosting service is required.

Publish `index.html`, `styles.css`, `demo.html`, `demo.css`, `demo.js`, `.nojekyll`, the `assets/demo/` images and the linked public `docs/` files together. Keeping the normal source repository layout provides these files. Site links are relative; do not change them to `/demo.html` or `/assets/...`, which would leave a project site's path prefix.

GitHub Pages cannot run the Java application. The public page explicitly states this and links to local run instructions. The source download uses GitHub's existing course-repository `main.zip` archive. The separately verified [v0.1.0-t19 prerelease](https://github.com/escape0007/campus-lost-and-found-platform/releases/tag/v0.1.0-t19) is hosted in the personal mirror for this synchronization. Its `T19-integrated-source-runtime-2026-11-01.zip` asset contains the built JAR under `app/`, the source under `source/`, launch scripts and the integration report. This package is 27,628,444 bytes with SHA-256 `4a9b2da4a3c6084ebebbde422faf1114f138f8861f9db2e5496cf0721759abbb`. It is a local course-demo prerelease, not a hosted backend or final acceptance release.

## Screenshot provenance and interpretation

- `assets/demo/return-completed.jpg`: unchanged screenshot from the 1 November 2026 local browser run. The fictional blue-bottle claim reached Returned after assigned review and a new two-party confirmation round.
- `assets/demo/mobile-returned.jpg`: unchanged screenshot from a 360-pixel viewport check. The capture itself is 345 pixels wide. A post-return dispute keeps the Returned outcome and history.
- Both screenshots use fictional identities, evidence and dispute text. Their publication is not a public view of real private case evidence.

The screenshots show actual UI; the guide's state cards are explanatory illustrations. The documented 78 backend tests and 18 frontend VM scenarios are the T19 integration evidence, not tests of the static site or a claim of full acceptance. T19 remains awaiting peer review. See `T19_联调与测试说明.txt` for the exact test scope and remaining limitations.

## Review checklist

Confirm that homepage demo/download links work, all six steps can be selected, previous/next wraps only at the final restart, keyboard focus is visible, and screenshots/documents/source download resolve. Check the homepage and demo at desktop and 360-pixel mobile widths. Confirm that no page-wide horizontal overflow occurs and that the displayed boundaries remain visible before a visitor starts the guide.
