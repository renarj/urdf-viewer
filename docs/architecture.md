# Architecture

## Purpose

URDF Viewer is a standalone Spring Boot application for importing and visually inspecting URDF models with STL meshes.
It deliberately has no robot runtime, actuator, CAN, telemetry, or hardware dependency.

## Runtime Shape

```text
Browser (Thymeleaf + Three.js)
        |
        | HTTP/JSON and model assets
        v
Spring MVC controllers
        |
        v
Import, validation, library, and asset services
        |
        v
Filesystem model library (manifest.json + source files)
```

## Backend Boundaries

- `controller`: page and REST transport adapters.
- `service`: import staging, validation, persistence, listing, deletion, and safe asset resolution.
- `model`: manifests, validation reports, viewer definitions, and API result records.
- `config`: typed `urdf.viewer.*` configuration.

The filesystem manifests are the source of truth. The in-memory library is reconstructed from them at startup, which
makes the configured storage directory portable and easy to back up.

## Browser Boundaries

- `viewer-page.js`: imports, library selection, deletion, API errors, and page state.
- `urdf-scene-viewer.js`: Three.js scene lifecycle, camera, controls, joint sliders, and metrics.
- `urdf-model-loader.js`: URDF/STL loading, same-origin URL checks, model-root confinement, limits, cancellation, and
  geometry/material disposal.

Three.js and `urdf-loader` are pinned in the page import map and are currently loaded from jsDelivr. Consequently, the
browser needs internet access on first use unless those dependencies are hosted locally in a future change.

## API

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/` | Render the viewer page. |
| `GET` | `/api/models` | List stored models. |
| `POST` | `/api/models/import` | Import ZIP bundles or a browser directory upload. |
| `GET` | `/api/models/{id}` | Return the manifest and viewer definition. |
| `GET` | `/api/models/{id}/urdf` | Return the validated URDF. |
| `GET` | `/api/models/{id}/assets/**` | Return a safely resolved model asset. |
| `DELETE` | `/api/models/{id}` | Delete a model and its stored files. |

## Security Invariants

- Reject absolute paths, traversal, ZIP slip, symbolic links, duplicate normalized paths, and remote asset URLs.
- Disable DTD and external XML entities.
- Enforce compressed, extracted, individual-file, file-count, mesh-count, STL-byte, and triangle limits.
- Publish staged imports only after complete validation.
- Resolve every returned asset beneath the selected immutable model root.

There is currently no authentication. Run the service only on a trusted network or place it behind an authenticated
reverse proxy when exposing it beyond localhost.

