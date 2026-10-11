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

## Docker Hub Publishing

The workflow in [`.github/workflows/docker-publish.yml`](../.github/workflows/docker-publish.yml) runs entirely on
standard GitHub-hosted Ubuntu runners. No laptop, self-hosted runner, or Docker Hub automated-build subscription is
required. The root Dockerfile builds the executable JAR with Java 21 and Maven, runs Java tests, and packages the runtime.
The workflow also runs Java and browser geometry tests before building, then starts an AMD64 container and checks the
model-library API, home page, and bundled license/attribution URLs before publishing. ARM64 builds use QEMU and run the
Dockerfile's Maven tests; the application startup smoke test runs on AMD64 only.

The root `LICENSE` and `NOTICE` files are bundled by Maven under `META-INF/resources/urdf-viewer/legal` in the JAR and
served through the viewer footer links. Docker copies both files into the build stage so the image carries the same
license and attribution. Update the root files rather than maintaining separate copies inside `src/`.

### One-time configuration

1. Create a Docker Hub personal access token for the account that can push to `renarj/urdf-viewer`. Grant Read & Write
   permission; Delete permission is unnecessary.
2. In the GitHub repository, open **Settings → Environments → dev-build** (create that environment if needed).
3. Under **Environment secrets**, create `DOCKERHUB_USERNAME` with the value `renarj`.
4. Under **Environment secrets**, create `DOCKERHUB_TOKEN` containing the access token.
5. Push the workflow to `main`. Follow its progress in the repository's **Actions** tab.

Both credentials are read through the `secrets` context. The separate publishing job references `environment: dev-build`,
which makes that environment's secrets available. Java/browser tests and container verification do not use the environment;
pull requests, including those from forks, never run the publishing job. Never commit the token.
If `dev-build` restricts deployment branches and tags, allow the `main` branch and `v*` tags for the configured triggers.
Any required environment approval applies only to publishing, after tests and container verification succeed.

Publishing is restricted to push events in `renarj/urdf-viewer`; forks must update
the repository guard and image name to publish to their own registry. If credentials are missing, tests and the container
smoke test still run, but publishing fails with a configuration message. After configuring credentials, rerun that workflow.
If you changed the workflow itself, commit and push the updated file to start a new run; rerunning an old failed run uses
the workflow from its original commit.

### Image tags and releases

| Git event | Docker Hub tags |
| --- | --- |
| Pull request targeting `main` | None; tests and AMD64 container smoke test only. |
| Push to `main` | `edge`, `sha-<full-commit>` |
| Push a release tag, e.g. `v1.2.3` | `1.2.3`, `1.2`, `latest` |

Both architectures are published under each image tag. `latest` refers to the most recently published release, while
`edge` tracks development on `main`. Release tags must use `vMAJOR.MINOR.PATCH`; prerelease and other `v*` tags are rejected.
The image version comes from the Git tag independently of the Maven project version. Use a new tag for every release.

For example, after the intended release commit is on `main`:

```bash
git switch main
git pull --ff-only
git tag -a v1.0.0 -m "Release 1.0.0"
git push origin v1.0.0
```

After the workflow succeeds, run the published image with persistent storage:

```bash
docker run --rm --name urdf-viewer \
  -p 8081:8081 \
  -v urdf-viewer-data:/data/urdf-viewer \
  renarj/urdf-viewer:1.0.0
```

Actions are pinned to commit hashes, and Dependabot checks for action updates weekly. The workflow uses read-only
repository permissions and GitHub Actions build caches. Docker Hub authentication follows
[Docker's GitHub Actions guide](https://docs.docker.com/guides/gha/).
