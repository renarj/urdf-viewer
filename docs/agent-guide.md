# Agent Instructions

This guide carries the relevant working conventions from the Robot Studio repository into the extracted URDF Viewer
project. The root `AGENTS.md` remains the entry point and takes precedence if these documents ever diverge.

## Task Routing

- Import, archive, validation, or storage work starts in `service/ModelImportService.java`,
  `service/UrdfValidationService.java`, and `service/ModelLibraryService.java`.
- Model asset and path-resolution work starts in `service/ModelAssetService.java`.
- HTTP/API work starts in `controller/`; keep the implementation in a service rather than a controller.
- Browser rendering work starts in `urdf-model-loader.js` and `urdf-scene-viewer.js`.
- Page workflow, import controls, model selection, and errors start in `viewer-page.js`.
- Layout and visual styling belong in `viewer.css`; keep `templates/index.html` structural.
- Packaging and runtime changes must validate both the executable JAR and `Dockerfile` paths.

## Working Agreement

- Inspect the relevant source and tests before changing behavior; documentation helps locate the source of truth but does
  not replace it.
- Preserve unrelated user changes and avoid destructive Git commands.
- Use service-layer classes for business rules and thin controllers for transport mapping.
- Do not add robot-hardware, actuator, telemetry, gait, or Robot Studio configuration concepts to this application.
- Never relax upload or asset-boundary protections merely to accept a model. Extend validation deliberately and cover the
  accepted and rejected cases with tests.
- Keep the normal service path testable with temporary filesystem storage; tests must not depend on sibling repositories
  or developer-specific absolute paths.
- Keep browser feature logic in modules, pin third-party dependency versions, and avoid per-frame allocations where a
  stable scene object can be updated.
- Run `mvn test` after Java or resource changes. Run `mvn clean package` for POM or release changes, and build/run the
  container after Docker changes.
- Stop local servers and validation containers before completing work.

## Completion Checklist

1. Behavior is covered by focused tests.
2. `mvn test` passes from the repository root without a Robot Studio checkout.
3. Documentation and configuration examples match the current behavior.
4. Generated files remain excluded by `.gitignore` and `.dockerignore`.
5. Any started server or container has been stopped.
