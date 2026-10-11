# Agent Guide

This file is the entry point for coding agents working in this repository.

## Start Here

Read these documents before changing behavior:

- `README.md` for supported workflows and user-facing behavior.
- `docs/agent-guide.md` for task routing and the extracted repository's working agreement.
- `docs/architecture.md` for application boundaries, storage, APIs, and security constraints.
- `docs/development.md` for repository conventions, build commands, and validation requirements.

## Development Rules

- Use Java 21 and keep the project buildable with the standalone root `pom.xml`.
- Keep import, validation, storage, and safe-path behavior in service classes. Controllers should map HTTP requests and
  responses and delegate business logic.
- Treat every uploaded archive, XML document, path, and mesh as untrusted input. Preserve all resource limits, ZIP-slip
  protection, XML external-entity protection, path normalization, and model-root confinement.
- Keep generic viewer behavior in the existing browser modules. Do not move model-loading or rendering logic into the
  Thymeleaf template.
- Keep the application independent from robot control, hardware, and robot-specific configuration.
- Add or update tests with behavior changes and run `mvn test` before finishing.
- If a local application or container is started, stop it before finishing and verify its port is free.
- Do not commit generated `target/`, `data/`, IDE, or log files.

## UI Rules

- Keep `index.html` structural and place behavior in the JavaScript modules under
  `src/main/resources/META-INF/resources/urdf-viewer/js/`.
- Reuse the existing CSS design tokens and responsive layout.
- Preserve load cancellation, atomic model replacement, resource disposal, bounds-based camera fitting, and visible
  loading/error states.
- Keep external browser dependencies pinned to explicit versions.
