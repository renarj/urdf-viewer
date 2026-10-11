import { createUrdfSceneViewer } from "./urdf-scene-viewer.js?v=20261011-floor-v12";

const elements = {
    library: document.querySelector("#modelLibrary"),
    modelCount: document.querySelector("#modelCount"),
    emptyLibrary: document.querySelector("#emptyLibrary"),
    zipForm: document.querySelector("#zipImportForm"),
    zipInput: document.querySelector("#zipBundles"),
    folderForm: document.querySelector("#folderImportForm"),
    folderInput: document.querySelector("#modelFolder"),
    displayName: document.querySelector("#displayName"),
    alert: document.querySelector("#alert"),
    modelTitle: document.querySelector("#modelTitle"),
    modelMetadata: document.querySelector("#modelMetadata"),
    deleteButton: document.querySelector("#deleteModel"),
    viewport: document.querySelector("#viewerScene"),
    loading: document.querySelector("#viewerLoading"),
    loadingText: document.querySelector("#viewerLoadingText"),
    metrics: document.querySelector("#viewerMetrics"),
    joints: document.querySelector("#jointControls")
};

const viewer = createUrdfSceneViewer({
    container: elements.viewport,
    loadingElement: elements.loading,
    loadingTextElement: elements.loadingText,
    metricsElement: elements.metrics,
    jointsElement: elements.joints,
    onStatus: status => {
        if (status.error) {
            showAlert(status.error, true);
        }
    }
});

let models = [];
let selectedModelId = null;

bind();
await refreshLibrary();

function bind() {
    elements.zipForm.addEventListener("submit", event => {
        event.preventDefault();
        importZipBundles();
    });
    elements.folderForm.addEventListener("submit", event => {
        event.preventDefault();
        importFolder();
    });
    elements.deleteButton.addEventListener("click", deleteSelectedModel);
    document.querySelector("#rotateLeft").addEventListener("click", viewer.rotateLeft);
    document.querySelector("#rotateRight").addEventListener("click", viewer.rotateRight);
    document.querySelector("#resetView").addEventListener("click", viewer.resetView);
    document.querySelector("#resetPose").addEventListener("click", viewer.resetPose);
}

async function refreshLibrary(preferredId = selectedModelId) {
    try {
        models = await requestJson("/api/models");
        renderLibrary();
        const next = models.some(model => model.id === preferredId) ? preferredId : models[0]?.id;
        if (next) {
            await selectModel(next);
        } else {
            selectedModelId = null;
            renderEmptySelection();
        }
    } catch (error) {
        showAlert(error.message, true);
    }
}

function renderLibrary() {
    elements.modelCount.textContent = String(models.length);
    elements.emptyLibrary.hidden = models.length > 0;
    const fragment = document.createDocumentFragment();
    models.forEach(model => {
        const button = document.createElement("button");
        button.type = "button";
        button.className = "model-list-item";
        button.classList.toggle("is-selected", model.id === selectedModelId);
        button.dataset.modelId = model.id;
        const name = document.createElement("strong");
        name.textContent = model.displayName;
        const detail = document.createElement("span");
        detail.textContent = `${model.meshCount} meshes · ${formatBytes(model.totalBytes)}`;
        const date = document.createElement("small");
        date.textContent = new Date(model.importedAt).toLocaleString();
        button.append(name, detail, date);
        button.addEventListener("click", () => selectModel(model.id));
        fragment.append(button);
    });
    elements.library.querySelectorAll(".model-list-item").forEach(item => item.remove());
    elements.library.append(fragment);
}

async function selectModel(modelId) {
    if (!modelId) {
        return;
    }
    selectedModelId = modelId;
    renderLibrary();
    showAlert("", false);
    try {
        const details = await requestJson(`/api/models/${encodeURIComponent(modelId)}`);
        if (selectedModelId !== modelId) {
            return;
        }
        renderModelDetails(details.manifest);
        await viewer.load(details.viewer);
    } catch (error) {
        showAlert(error.message, true);
    }
}

function renderModelDetails(manifest) {
    elements.modelTitle.textContent = manifest.displayName;
    elements.modelMetadata.textContent = `${manifest.robotName} · ${manifest.meshFileCount} mesh files · `
            + `${Number(manifest.triangleCount).toLocaleString()} triangles · ${manifest.urdfPath}`;
    elements.deleteButton.disabled = false;
}

function renderEmptySelection() {
    viewer.clear();
    elements.modelTitle.textContent = "No model selected";
    elements.modelMetadata.textContent = "Import a URDF model bundle to begin.";
    elements.metrics.textContent = "No model loaded";
    elements.deleteButton.disabled = true;
    elements.joints.innerHTML = '<p class="empty-copy">Joint controls appear after a model loads.</p>';
}

async function importZipBundles() {
    const bundles = [...elements.zipInput.files];
    if (bundles.length === 0) {
        showAlert("Select at least one ZIP bundle.", true);
        return;
    }
    const form = new FormData();
    bundles.forEach(bundle => form.append("bundles", bundle));
    appendDisplayName(form, bundles.length);
    await runImport(form, elements.zipForm);
}

async function importFolder() {
    const files = [...elements.folderInput.files];
    if (files.length === 0) {
        showAlert("Select a model folder.", true);
        return;
    }
    const form = new FormData();
    files.forEach(file => form.append("files", file, file.webkitRelativePath || file.name));
    appendDisplayName(form, 1);
    await runImport(form, elements.folderForm);
}

function appendDisplayName(form, modelCount) {
    const displayName = elements.displayName.value.trim();
    if (displayName && modelCount === 1) {
        form.append("displayName", displayName);
    }
}

async function runImport(form, formElement) {
    const submit = formElement.querySelector('button[type="submit"]');
    submit.disabled = true;
    showAlert("Importing and validating model…", false);
    try {
        const result = await requestJson("/api/models/import", { method: "POST", body: form });
        const successes = result.results.filter(item => item.success);
        const failures = result.results.filter(item => !item.success);
        const message = [
            successes.length ? `Imported ${successes.length} model${successes.length === 1 ? "" : "s"}.` : "",
            ...failures.map(item => `${item.sourceName}: ${item.error}`)
        ].filter(Boolean).join(" ");
        showAlert(message, failures.length > 0);
        formElement.reset();
        if (successes.length > 0) {
            elements.displayName.value = "";
            await refreshLibrary(successes[0].model.id);
        }
    } catch (error) {
        showAlert(error.message, true);
    } finally {
        submit.disabled = false;
    }
}

async function deleteSelectedModel() {
    const selected = models.find(model => model.id === selectedModelId);
    if (!selected || !window.confirm(`Delete “${selected.displayName}” and all of its uploaded files?`)) {
        return;
    }
    try {
        const response = await fetch(`/api/models/${encodeURIComponent(selected.id)}`, { method: "DELETE" });
        if (!response.ok) {
            throw new Error(await responseError(response));
        }
        selectedModelId = null;
        showAlert(`Deleted ${selected.displayName}.`, false);
        await refreshLibrary();
    } catch (error) {
        showAlert(error.message, true);
    }
}

async function requestJson(url, options) {
    const response = await fetch(url, options);
    if (!response.ok) {
        throw new Error(await responseError(response));
    }
    return response.json();
}

async function responseError(response) {
    try {
        const body = await response.json();
        return body.message || body.detail || `Request failed with HTTP ${response.status}`;
    } catch (_error) {
        return `Request failed with HTTP ${response.status}`;
    }
}

function showAlert(message, error) {
    elements.alert.hidden = !message;
    elements.alert.classList.toggle("is-error", error);
    elements.alert.textContent = message;
}

function formatBytes(bytes) {
    if (bytes < 1024 * 1024) {
        return `${Math.max(1, Math.round(bytes / 1024))} KiB`;
    }
    return `${(bytes / (1024 * 1024)).toFixed(1)} MiB`;
}
