# AGENTS.md — CupThread Android SDK

## Repository Purpose
`CupThreadAndroidSDK` is the official Android SDK for [CupThread.com](https://cupthread.com) (Kotlin + Jetpack Compose, minSdk 26).

## Multi-Repo Ecosystem
- **CupThread Platform**: [`CupThread.com`](https://cupthread.com) (Main SaaS website & backend API)
- **Apple SDK**: [`CupThread/CupThreadSwiftSDK`](https://github.com/CupThread/CupThreadSwiftSDK) (SwiftUI / SPM / XCFramework)
- **Android SDK**: [`CupThread/CupThreadAndroidSDK`](https://github.com/CupThread/CupThreadAndroidSDK) (Kotlin + Jetpack Compose)
- **Agentic Coding & CLI**: [`CupThread/CupThreadAgenticCoding`](https://github.com/CupThread/CupThreadAgenticCoding) (AI Skills, CLI tools)

## Agentic Coding Friendly
This repository is optimized for autonomous agents and LLM pair programmers. AI Skills, CLI integrations, and agent workflows for working across CupThread repositories are available at [`CupThread/CupThreadAgenticCoding`](https://github.com/CupThread/CupThreadAgenticCoding).

## Architecture & API Contract
- Public endpoints live under `/api/v1/public/*` and `/api/v1/*` on `https://api.cupthread.com`:
  - `GET /api/v1/public/config/:appKey` — App configuration, theme, allowed platforms, changelog copy.
  - `GET /api/v1/public/columns/:appKey` — Kanban board columns for roadmap.
  - `GET /api/v1/public/versions/:appKey` — App release versions.
  - `GET /api/v1/public/apps/:appKey/changelog` — Published release notes & changelog entries.
  - `POST /api/v1/public/apps/:appKey/changelog/subscribe` — Email subscription.
  - `POST /api/v1/public/apps/:appKey/changelog/unsubscribe` — Unsubscribe from updates.
  - `PUT /api/v1/public/apps/:appKey/user` — Report user attributes (paying status, MRR).
  - `GET /api/v1/feature-requests` — Feature requests list and search with `q` query parameter.
  - `POST /api/v1/feature-requests` — Submit new feature request.
  - `POST /api/v1/feature-requests/:id/vote` — Toggle vote on a feature request.
  - `POST /api/v1/feedback` — Submit feedback draft with attachments.
  - `POST /api/v1/uploads/images` & `POST /api/v1/uploads/r2` — Media and log attachment uploads.

## Development & Testing
- Run test suite: `./gradlew :feedback:testDebugUnitTest`
- Assemble demo app: `./gradlew :demo:assembleDebug`
- Release SDK: `node scripts/release.mjs --version <semver>`

## Quality Rules
1. Maintain Compose + Material 3 best practices with minimal external dependencies.
2. Support minSdk 26+ (Android 8.0+).
3. Maintain type and API contract consistency with CupThread Public API schema.
