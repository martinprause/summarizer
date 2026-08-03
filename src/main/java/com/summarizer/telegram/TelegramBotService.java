package com.summarizer.telegram;

import com.summarizer.base.i18n.TranslationProvider;
import com.summarizer.item.Item;
import com.summarizer.item.ItemRepository;
import com.summarizer.item.pipeline.IngestPipeline;
import com.summarizer.settings.AppSettingsService;
import com.summarizer.user.User;
import com.summarizer.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Telegram-Bot per Long-Polling — kein Tunnel/Webhook nötig, alles ausgehend.
 * Empfängt Text, Links, Fotos, Sprachnachrichten, Dokumente und schickt sie
 * durch die normale Ingest-Pipeline. Pairing per Deep-Link-QR:
 * t.me/BOT?start=CODE verknüpft den Telegram-Chat mit dem Studio-User.
 */
@Service
public class TelegramBotService implements ApplicationRunner {

    public static final String TOKEN_KEY = "telegram.bot-token";
    public static final String LAST_CATCHUP_KEY = "telegram.last-catchup";

    private static final Logger log = LoggerFactory.getLogger(TelegramBotService.class);

    private final AppSettingsService settings;
    private final UserRepository users;
    private final ItemRepository items;
    private final IngestPipeline pipeline;
    private final TranslationProvider i18n;
    private final Path filesDir;

    /** Pairing-Code → (userId, Ablauf) — 15 Minuten gültig. */
    private final Map<String, PendingPairing> pendingPairings = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    private volatile String botUsername;
    private long updateOffset = 0;
    private boolean lastPollFailed = false;

    /** Beim Hochfahren: Rückstand aus der Telegram-Warteschlange nachholen. */
    private boolean catchUpDone = false;
    private int caughtUpCount = 0;

    private record PendingPairing(Long userId, Instant expires) {
    }

    public TelegramBotService(AppSettingsService settings, UserRepository users,
                              ItemRepository items, IngestPipeline pipeline,
                              TranslationProvider i18n,
                              @Value("${summarizer.files.dir}") String filesDir) {
        this.settings = settings;
        this.users = users;
        this.items = items;
        this.pipeline = pipeline;
        this.i18n = i18n;
        this.filesDir = Path.of(filesDir);
    }

    /** Übersetzung in der konfigurierten App-Sprache (Bot-Antworten, Fallback-Titel). */
    private String tr(String key, Object... params) {
        return i18n.getTranslation(key,
                TranslationProvider.localeFor(settings.get(TranslationProvider.LANGUAGE_KEY, "de")),
                params);
    }

    // ---------- Public API für die UI ----------

    public boolean isConfigured() {
        return !settings.get(TOKEN_KEY, "").isBlank();
    }

    /** Bot-Username (via getMe), leer wenn Token fehlt/ungültig. */
    public Optional<String> botUsername() {
        if (botUsername != null) {
            return Optional.of(botUsername);
        }
        String token = settings.get(TOKEN_KEY, "");
        if (token.isBlank()) {
            return Optional.empty();
        }
        try {
            Map<?, ?> response = client().get().uri("/bot" + token + "/getMe").retrieve().body(Map.class);
            if (response != null && response.get("result") instanceof Map<?, ?> result
                    && result.get("username") instanceof String name) {
                botUsername = name;
                return Optional.of(name);
            }
        } catch (Exception e) {
            log.warn("Telegram getMe fehlgeschlagen: {}", e.getMessage());
        }
        return Optional.empty();
    }

    public void setToken(String token) {
        settings.set(TOKEN_KEY, token == null ? "" : token.strip());
        botUsername = null;   // neu auflösen
    }

    /** Erzeugt Pairing-Link für den QR-Code: t.me/BOT?start=CODE */
    public Optional<String> createPairingLink(Long userId) {
        return botUsername().map(name -> {
            byte[] bytes = new byte[8];
            random.nextBytes(bytes);
            String code = HexFormat.of().formatHex(bytes);
            pendingPairings.put(code, new PendingPairing(userId, Instant.now().plusSeconds(900)));
            return "https://t.me/" + name + "?start=" + code;
        });
    }

    public boolean isLinked(Long userId) {
        return users.findById(userId).map(u -> u.getTelegramChatId() != null).orElse(false);
    }

    // ---------- Long-Polling ----------

    @Override
    public void run(ApplicationArguments args) {
        Thread.ofVirtual().name("telegram-poller").start(this::pollLoop);
    }

    private void pollLoop() {
        log.info("Telegram-Poller gestartet (aktiv sobald Token konfiguriert)");
        while (true) {
            try {
                String token = settings.get(TOKEN_KEY, "");
                if (token.isBlank()) {
                    Thread.sleep(5000);
                    continue;
                }
                poll(token);
                if (lastPollFailed) {
                    log.info("Telegram-Polling wieder aktiv");
                    lastPollFailed = false;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                // Typisch beim Neustart/DB-Ausfall: DB kurz weg. Nur einmal warnen,
                // dann still weiter versuchen — Nachrichten bleiben bei Telegram liegen.
                if (!lastPollFailed) {
                    log.warn("Telegram-Polling pausiert: {}", e.getMessage());
                    lastPollFailed = true;
                }
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void poll(String token) {
        // Während des Nachholens kurzes timeout=0, damit das Ende des Rückstands
        // sofort erkannt wird; danach normales Long-Polling.
        int timeout = catchUpDone ? 25 : 0;
        Map<?, ?> response = client().get()
                .uri("/bot" + token + "/getUpdates?timeout=" + timeout + "&offset=" + updateOffset)
                .retrieve()
                .body(Map.class);
        if (response == null || !(response.get("result") instanceof List<?> updates)) {
            return;
        }
        if (!catchUpDone && updates.isEmpty()) {
            finishCatchUp();
            return;
        }
        for (Map<String, Object> update : (List<Map<String, Object>>) updates) {
            updateOffset = ((Number) update.get("update_id")).longValue() + 1;
            try {
                if (update.get("message") instanceof Map<?, ?> message) {
                    if (!catchUpDone) {
                        caughtUpCount++;
                    }
                    handleMessage(token, (Map<String, Object>) message);
                }
            } catch (Exception e) {
                log.warn("Telegram-Nachricht fehlgeschlagen: {}", e.getMessage());
            }
        }
    }

    /** Rückstand abgearbeitet: Ergebnis loggen, speichern und per Push in die UI melden. */
    private void finishCatchUp() {
        catchUpDone = true;
        String time = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
        if (caughtUpCount > 0) {
            String info = tr("telegram.catchup.info", caughtUpCount, time);
            settings.set(LAST_CATCHUP_KEY, info);
            log.info("Telegram-Rückstand nachgeholt: {}", info);
            com.summarizer.base.UiNotifier.broadcast(tr("telegram.catchup.broadcast", info));
        } else {
            log.info("Telegram-Warteschlange leer — kein Rückstand");
        }
    }

    // ---------- Nachrichten-Verarbeitung ----------

    @SuppressWarnings("unchecked")
    private void handleMessage(String token, Map<String, Object> message) {
        long chatId = ((Number) ((Map<String, Object>) message.get("chat")).get("id")).longValue();
        String text = (String) message.get("text");

        // Pairing: /start CODE
        if (text != null && text.startsWith("/start")) {
            handleStart(token, chatId, text);
            return;
        }

        Optional<User> user = users.findByTelegramChatId(chatId);
        if (user.isEmpty()) {
            send(token, chatId, tr("telegram.notlinked"));
            return;
        }
        Long userId = user.get().getId();

        Item item = null;
        if (message.get("voice") instanceof Map<?, ?> voice) {
            item = downloadToItem(token, userId, (String) voice.get("file_id"), ".ogg",
                    Item.Type.AUDIO, tr("telegram.title.voice"));
        } else if (message.get("audio") instanceof Map<?, ?> audio) {
            item = downloadToItem(token, userId, (String) audio.get("file_id"), ".mp3",
                    Item.Type.AUDIO, str(audio, "file_name", tr("telegram.title.audio")));
        } else if (message.get("photo") instanceof List<?> photos && !photos.isEmpty()) {
            Map<String, Object> largest = ((List<Map<String, Object>>) photos).getLast();
            item = downloadToItem(token, userId, (String) largest.get("file_id"), ".jpg",
                    Item.Type.IMAGE, caption(message, tr("telegram.title.photo")));
        } else if (message.get("document") instanceof Map<?, ?> document) {
            String fileName = str(document, "file_name", tr("telegram.title.file"));
            String mime = str(document, "mime_type", "");
            Item.Type type = mime.startsWith("image/") ? Item.Type.IMAGE
                    : mime.startsWith("audio/") ? Item.Type.AUDIO : Item.Type.FILE;
            String ext = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.')) : "";
            item = downloadToItem(token, userId, (String) document.get("file_id"), ext, type, fileName);
        } else if (text != null && !text.isBlank()) {
            boolean isUrl = text.strip().matches("https?://\\S+");
            if (isUrl) {
                // Dedup wie in der API: gleiche URL beim gleichen User nicht doppelt speichern
                Optional<Item> existing = items.findFirstByUserIdAndSourceUrl(userId, text.strip());
                if (existing.isPresent()) {
                    send(token, chatId, tr("telegram.duplicate",
                            existing.get().getTitle() == null
                                    ? text.strip() : existing.get().getTitle()));
                    return;
                }
            }
            item = new Item(userId, isUrl ? Item.Type.WEBPAGE : Item.Type.TEXT);
            if (isUrl) {
                item.setSourceUrl(text.strip());
            } else {
                item.setRawText(text);
            }
            items.save(item);
        }

        if (item == null) {
            send(token, chatId, tr("telegram.unsupported"));
            return;
        }

        pipeline.process(item.getId());
        send(token, chatId, tr("telegram.saved"));
        notifyWhenDone(token, chatId, item.getId());
    }

    private void handleStart(String token, long chatId, String text) {
        String[] parts = text.split("\\s+", 2);
        String code = parts.length > 1 ? parts[1].strip() : "";
        PendingPairing pairing = pendingPairings.remove(code);
        if (pairing == null || pairing.expires().isBefore(Instant.now())) {
            send(token, chatId, tr("telegram.pairing.invalid"));
            return;
        }
        users.findById(pairing.userId()).ifPresent(user -> {
            // alte Verknuepfung desselben Chats loesen
            users.findByTelegramChatId(chatId).ifPresent(old -> {
                old.setTelegramChatId(null);
                users.save(old);
            });
            user.setTelegramChatId(chatId);
            users.save(user);
            send(token, chatId, tr("telegram.pairing.linked", user.getUsername()));
        });
    }

    /** Nach Abschluss der Pipeline Kategorie/Tags als zweite Nachricht melden. */
    private void notifyWhenDone(String token, long chatId, Long itemId) {
        Thread.ofVirtual().start(() -> {
            try {
                for (int i = 0; i < 60; i++) {
                    Thread.sleep(5000);
                    Optional<Item> current = items.findById(itemId);
                    if (current.isEmpty()) {
                        return;
                    }
                    Item item = current.get();
                    if (item.getStatus() == Item.Status.DONE) {
                        String info;
                        if (item.getSummary() != null && !item.getSummary().isBlank()) {
                            info = tr("telegram.done.summary", item.getSummary());
                        } else if (item.getRawText() != null && item.getType() == Item.Type.AUDIO) {
                            info = tr("telegram.done.transcript",
                                    item.getRawText().substring(0, Math.min(300, item.getRawText().length())));
                        } else {
                            info = tr("telegram.done");
                        }
                        send(token, chatId, info);
                        return;
                    }
                    if (item.getStatus() == Item.Status.FAILED) {
                        send(token, chatId, item.getErrorMessage() != null
                                ? tr("telegram.failed.reason", item.getErrorMessage())
                                : tr("telegram.failed"));
                        return;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    // ---------- Helfer ----------

    private String str(Map<?, ?> map, String key, String fallback) {
        Object value = map.get(key);
        return value instanceof String s && !s.isBlank() ? s : fallback;
    }

    private String caption(Map<String, Object> message, String fallback) {
        Object caption = message.get("caption");
        return caption instanceof String s && !s.isBlank() ? s : fallback;
    }

    private Item downloadToItem(String token, Long userId, String fileId, String extension,
                                Item.Type type, String title) {
        try {
            Map<?, ?> response = client().get()
                    .uri("/bot" + token + "/getFile?file_id=" + fileId)
                    .retrieve().body(Map.class);
            if (response == null || !(response.get("result") instanceof Map<?, ?> result)) {
                return null;
            }
            String filePath = (String) result.get("file_path");
            byte[] data = client().get()
                    .uri("https://api.telegram.org/file/bot" + token + "/" + filePath)
                    .retrieve().body(byte[].class);
            if (data == null) {
                return null;
            }
            Path dir = filesDir.resolve(String.valueOf(LocalDate.now().getYear()));
            Files.createDirectories(dir);
            Path target = dir.resolve(UUID.randomUUID() + extension);
            Files.write(target, data);

            Item item = new Item(userId, type);
            item.setTitle(title);
            item.setFilePath(target.toString());
            items.save(item);
            return item;
        } catch (Exception e) {
            log.warn("Telegram-Download fehlgeschlagen: {}", e.getMessage());
            return null;
        }
    }

    private void send(String token, long chatId, String text) {
        try {
            client().post()
                    .uri("/bot" + token + "/sendMessage")
                    .header("Content-Type", "application/json")
                    .body(Map.of("chat_id", chatId, "text", text))
                    .retrieve().toBodilessEntity();
        } catch (Exception e) {
            log.warn("Telegram sendMessage fehlgeschlagen: {}", e.getMessage());
        }
    }

    private RestClient client() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(40));   // Long-Poll timeout=25 + Puffer
        return RestClient.builder().baseUrl("https://api.telegram.org").requestFactory(factory).build();
    }
}
