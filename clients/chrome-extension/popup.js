import { sendItem, getSettings, configured } from "./api.js";

const status = document.getElementById("status");
const savePage = document.getElementById("savePage");
const saveSelection = document.getElementById("saveSelection");

let currentTab;

async function init() {
    [currentTab] = await chrome.tabs.query({ active: true, currentWindow: true });
    document.getElementById("pageTitle").textContent = currentTab ? currentTab.title : "";

    const settings = await getSettings();
    if (!configured(settings)) {
        setStatus("Bitte zuerst Server-URL und Token in den Einstellungen hinterlegen.", false);
        savePage.disabled = true;
        saveSelection.disabled = true;
    }
}

function setStatus(text, ok) {
    status.textContent = text;
    status.className = ok ? "ok" : "error";
}

savePage.addEventListener("click", async () => {
    try {
        savePage.disabled = true;
        await sendItem({ type: "WEBPAGE", url: currentTab.url, title: currentTab.title });
        setStatus("Gespeichert ✓ — wird im Hintergrund verarbeitet", true);
    } catch (e) {
        setStatus(e.message, false);
        savePage.disabled = false;
    }
});

saveSelection.addEventListener("click", async () => {
    try {
        const [{ result: selection }] = await chrome.scripting.executeScript({
            target: { tabId: currentTab.id },
            func: () => window.getSelection().toString()
        });
        if (!selection || selection.trim() === "") {
            setStatus("Keine Textauswahl auf der Seite.", false);
            return;
        }
        await sendItem({
            type: "TEXT",
            text: selection,
            title: currentTab.title + " — Auswahl",
            url: currentTab.url
        });
        setStatus("Auswahl gespeichert ✓", true);
    } catch (e) {
        setStatus(e.message, false);
    }
});

document.getElementById("openOptions").addEventListener("click", e => {
    e.preventDefault();
    chrome.runtime.openOptionsPage();
});

init();
