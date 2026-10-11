# Development Guide

These instructions are adapted from the agent and UI guidance in the Robot Studio repository from which this project
was extracted.

## Prerequisites

- JDK 21
- Maven 3.9 or newer
- A WebGL-capable browser
- Docker or another OCI builder for container validation (optional)

## Commands

```bash
mvn test
npm ci && npm test
mvn spring-boot:run
mvn clean package
java -jar target/urdf-viewer-1.0.0-SNAPSHOT.jar
docker build -t urdf-viewer .
```

The application listens on port `8081` and stores imported models below `./data/urdf-viewer` by default.
The browser geometry regression tests require Node.js 20 or newer; their Three.js dependency matches the pinned browser
version. Node.js is only needed for these tests, not to build or run the application.

## Backend Conventions

- Controllers remain thin and delegate model operations to services.
- Validation is completed before a staged import is moved into the model library.
- The manifest files on disk are the library index source of truth.
- Asset responses must pass through `ModelAssetService`; never expose the storage directory as a general static resource.
- User-visible errors must be actionable without disclosing server filesystem paths.
- Any new format must receive the same path, size, parser, and resource-limit review as URDF/STL.

## Browser Conventions

- `viewer-page.js` owns page/API interaction.
- `urdf-scene-viewer.js` owns the stable Three.js scene, camera, controls, joint controls, and resource disposal.
- `urdf-model-loader.js` owns URDF/STL loading, URL confinement, safety accounting, and load cancellation.
- Avoid rebuilding scene objects on each frame and dispose replaced geometry and materials.
- Keep the page usable at narrow viewport widths and provide visible loading and error states.

## Validation Checklist

1. Run `mvn test`.
2. Run `mvn clean package` and start the packaged JAR.
3. Import a ZIP containing a URDF and its referenced STL files.
4. Switch models, articulate joints, reset the view, and delete a model.
5. Confirm a restart reloads the stored manifests.
6. For container changes, build the image and mount a writable volume at `/data/urdf-viewer`.
