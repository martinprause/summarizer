import { sendItem, sendImage } from "./api.js";

chrome.runtime.onInstalled.addListener(() => {
    chrome.contextMenus.create({
        id: "save-page",
        title: "Seite an Summarizer senden",
        contexts: ["page"]
    });
    chrome.contextMenus.create({
        id: "save-selection",
        title: "Auswahl an Summarizer senden",
        contexts: ["selection"]
    });
    chrome.contextMenus.create({
        id: "save-image",
        title: "Bild an Summarizer senden",
        contexts: ["image"]
    });
});

chrome.contextMenus.onClicked.addListener(async (info, tab) => {
    try {
        if (info.menuItemId === "save-page") {
            await sendItem({ type: "WEBPAGE", url: info.pageUrl, title: tab ? tab.title : null });
        } else if (info.menuItemId === "save-selection") {
            await sendItem({
                type: "TEXT",
                text: info.selectionText,
                title: (tab && tab.title ? tab.title + " — " : "") + "Auswahl",
                url: info.pageUrl
            });
        } else if (info.menuItemId === "save-image") {
            await sendImage(info.srcUrl, info.pageUrl);
        }
        flashBadge(true);
    } catch (e) {
        console.error("Summarizer:", e);
        flashBadge(false);
        notifyError(e.message);
    }
});

function flashBadge(ok) {
    chrome.action.setBadgeText({ text: ok ? "✓" : "!" });
    chrome.action.setBadgeBackgroundColor({ color: ok ? "#2e7d32" : "#c62828" });
    setTimeout(() => chrome.action.setBadgeText({ text: "" }), 3000);
}

function notifyError(message) {
    chrome.notifications.create({
        type: "basic",
        iconUrl: "icons/icon48.png",
        title: "Summarizer — Fehler",
        message: message || "Senden fehlgeschlagen"
    });
}
