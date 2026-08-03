const serverUrl = document.getElementById("serverUrl");
const token = document.getElementById("token");
const status = document.getElementById("status");

chrome.storage.sync.get(["serverUrl", "token"]).then(stored => {
    serverUrl.value = stored.serverUrl || "";
    token.value = stored.token || "";
});

document.getElementById("save").addEventListener("click", async () => {
    const url = serverUrl.value.trim().replace(/\/+$/, "");
    const tokenValue = token.value.trim();
    status.textContent = "Teste Verbindung …";
    status.className = "";
    try {
        const response = await fetch(url + "/api/v1/items", {
            headers: { "Authorization": "Bearer " + tokenValue }
        });
        if (response.status === 401) {
            throw new Error("Token ungültig (401)");
        }
        if (!response.ok) {
            throw new Error("Server antwortete mit HTTP " + response.status);
        }
        await chrome.storage.sync.set({ serverUrl: url, token: tokenValue });
        status.textContent = "Verbindung OK — gespeichert ✓";
        status.className = "ok";
    } catch (e) {
        status.textContent = "Fehler: " + e.message;
        status.className = "error";
    }
});
