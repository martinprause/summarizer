// Gemeinsame API-Helfer für Popup und Service Worker.

export async function getSettings() {
    const { serverUrl, token } = await chrome.storage.sync.get(["serverUrl", "token"]);
    return { serverUrl: (serverUrl || "").replace(/\/+$/, ""), token: token || "" };
}

export function configured(settings) {
    return settings.serverUrl !== "" && settings.token !== "";
}

export async function sendItem(body) {
    const settings = await getSettings();
    if (!configured(settings)) {
        throw new Error("Nicht konfiguriert — Server-URL und Token in den Optionen setzen.");
    }
    const response = await fetch(settings.serverUrl + "/api/v1/items", {
        method: "POST",
        headers: {
            "Authorization": "Bearer " + settings.token,
            "Content-Type": "application/json"
        },
        body: JSON.stringify(body)
    });
    if (!response.ok) {
        throw new Error("Server antwortete mit HTTP " + response.status);
    }
    return response.json();
}

export async function sendImage(imageUrl, pageUrl) {
    const settings = await getSettings();
    if (!configured(settings)) {
        throw new Error("Nicht konfiguriert — Server-URL und Token in den Optionen setzen.");
    }
    const imageResponse = await fetch(imageUrl);
    if (!imageResponse.ok) {
        throw new Error("Bild konnte nicht geladen werden (HTTP " + imageResponse.status + ")");
    }
    const blob = await imageResponse.blob();
    const filename = imageUrl.split("/").pop().split("?")[0] || "bild";
    const form = new FormData();
    form.append("file", blob, filename);
    form.append("title", "Bild von " + (pageUrl || imageUrl));
    const response = await fetch(settings.serverUrl + "/api/v1/items", {
        method: "POST",
        headers: { "Authorization": "Bearer " + settings.token },
        body: form
    });
    if (!response.ok) {
        throw new Error("Server antwortete mit HTTP " + response.status);
    }
    return response.json();
}
