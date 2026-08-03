package com.summarizer.base.ui;

import com.summarizer.ai.OllamaClient;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

@Route("system")
@PageTitle("System — Summarizer Studio")
@PermitAll
public class SystemView extends VerticalLayout {

    public SystemView(JdbcTemplate jdbc, OllamaClient ollama, com.summarizer.ai.WhisperClient whisper,
                      com.summarizer.telegram.TelegramBotService telegram,
                      com.summarizer.token.QrCodeService qrCodes,
                      com.summarizer.base.CurrentUser currentUser,
                      com.summarizer.settings.AppSettingsService settings,
                      @Value("${summarizer.ollama.chat-model}") String chatModel,
                      @Value("${summarizer.ollama.embedding-model}") String embeddingModel) {
        add(new H2(getTranslation("system.status.title")));
        try {
            String pgVersion = jdbc.queryForObject("SELECT version()", String.class);
            String vectorVersion = jdbc.queryForObject(
                    "SELECT extversion FROM pg_extension WHERE extname = 'vector'", String.class);
            Integer itemCount = jdbc.queryForObject("SELECT count(*) FROM items", Integer.class);
            Integer chunkCount = jdbc.queryForObject("SELECT count(*) FROM item_embeddings", Integer.class);
            add(statusLine(getTranslation("system.status.postgres", shorten(pgVersion)), true));
            add(statusLine(getTranslation("system.status.pgvector", vectorVersion), true));
            add(statusLine(getTranslation("system.status.counts", itemCount, chunkCount), true));
        } catch (Exception e) {
            add(statusLine(getTranslation("system.status.dbDown"), false));
        }
        boolean ollamaUp = ollama.isAvailable();
        add(statusLine(getTranslation("system.status.ollama",
                getTranslation(ollamaUp ? "system.status.reachable" : "system.status.unreachable")), ollamaUp));
        add(statusLine(getTranslation("system.status.models",
                ollama.chatModel(), ollama.embeddingModel()), true));
        boolean whisperUp = whisper.isAvailable();
        add(statusLine(getTranslation("system.status.whisper",
                getTranslation(whisperUp ? "system.status.reachable" : "system.status.whisperDown")),
                whisperUp));

        Anchor export = new Anchor("export/items.json", getTranslation("system.export.json"));
        export.getStyle().set("display", "block").set("margin-top", "0.8em");
        add(export);

        Anchor backup = new Anchor("export/archive.zip", "");
        backup.getElement().setAttribute("download", true);
        com.vaadin.flow.component.button.Button backupButton =
                new com.vaadin.flow.component.button.Button(getTranslation("system.backup.button"),
                        com.vaadin.flow.component.icon.VaadinIcon.DOWNLOAD_ALT.create());
        backup.add(backupButton);
        backup.getStyle().set("display", "inline-block").set("margin-top", "0.4em");
        add(backup);
        Span backupHint = new Span(getTranslation("system.backup.hint"));
        backupHint.getStyle().set("color", "var(--lumo-secondary-text-color)")
                .set("font-size", "0.85em").set("display", "block");
        add(backupHint);


        addTelegramSection(telegram, qrCodes, currentUser, settings);
        addLanguageSection(settings);
        addAccessSection(settings, currentUser);
    }

    /** Sprache der Oberfläche: Deutsch oder Englisch, gilt für alle Sitzungen. */
    private void addLanguageSection(com.summarizer.settings.AppSettingsService settings) {
        add(new com.vaadin.flow.component.html.H2(getTranslation("system.language.title")));
        com.vaadin.flow.component.select.Select<String> language =
                new com.vaadin.flow.component.select.Select<>();
        language.setItems("de", "en");
        language.setItemLabelGenerator(code -> "de".equals(code) ? "Deutsch" : "English");
        language.setWidth("200px");
        language.setValue(settings.get(
                com.summarizer.base.i18n.TranslationProvider.LANGUAGE_KEY, "de"));
        language.addValueChangeListener(e -> {
            if (!e.isFromClient() || e.getValue() == null) {
                return;
            }
            settings.set(com.summarizer.base.i18n.TranslationProvider.LANGUAGE_KEY, e.getValue());
            com.vaadin.flow.server.VaadinSession.getCurrent().setLocale(
                    com.summarizer.base.i18n.TranslationProvider.localeFor(e.getValue()));
            com.vaadin.flow.component.UI.getCurrent().getPage().reload();
        });
        add(language);
    }

    /** Zugriff: Login an/aus — rein lokaler Betrieb ohne Anmeldung möglich. */
    private void addAccessSection(com.summarizer.settings.AppSettingsService settings,
                                  com.summarizer.base.CurrentUser currentUser) {
        if (!"ADMIN".equals(currentUser.get().getRole())) {
            return;
        }
        add(new com.vaadin.flow.component.html.H2(getTranslation("system.access.title")));
        com.vaadin.flow.component.checkbox.Checkbox loginToggle =
                new com.vaadin.flow.component.checkbox.Checkbox(getTranslation("system.access.loginRequired"));
        loginToggle.setValue(com.summarizer.security.SecurityConfig.isLoginEnabled(settings));
        loginToggle.addValueChangeListener(e -> {
            if (!e.isFromClient()) {
                return;
            }
            settings.set(com.summarizer.security.SecurityConfig.LOGIN_ENABLED_KEY,
                    Boolean.TRUE.equals(e.getValue()) ? "true" : "false");
            com.vaadin.flow.component.notification.Notification.show(
                    getTranslation("system.access.saved"));
        });
        add(loginToggle);
    }

    /** Telegram-Bot: Token setzen, Status, QR-Pairing über Deep-Link. */
    private void addTelegramSection(com.summarizer.telegram.TelegramBotService telegram,
                                    com.summarizer.token.QrCodeService qrCodes,
                                    com.summarizer.base.CurrentUser currentUser,
                                    com.summarizer.settings.AppSettingsService settings) {
        add(new com.vaadin.flow.component.html.H2(getTranslation("system.telegram.title")));
        add(new com.vaadin.flow.component.html.Paragraph(getTranslation("system.telegram.intro")));
        com.vaadin.flow.component.html.Paragraph queueHint =
                new com.vaadin.flow.component.html.Paragraph(
                        getTranslation("system.telegram.queueHint"));
        queueHint.getStyle().set("color", "var(--lumo-secondary-text-color)")
                .set("font-size", "0.9em");
        add(queueHint);
        String lastCatchUp = settings.get(
                com.summarizer.telegram.TelegramBotService.LAST_CATCHUP_KEY, "");
        if (!lastCatchUp.isBlank()) {
            add(statusLine(getTranslation("system.telegram.lastCatchUp", lastCatchUp), true));
        }

        com.vaadin.flow.component.textfield.PasswordField tokenField =
                new com.vaadin.flow.component.textfield.PasswordField(getTranslation("system.telegram.tokenLabel"));
        tokenField.setWidth("380px");
        tokenField.setPlaceholder("123456789:AA...");
        if (telegram.isConfigured()) {
            tokenField.setValue("••••••••••");
        }

        com.vaadin.flow.component.button.Button save =
                new com.vaadin.flow.component.button.Button(getTranslation("system.telegram.save"), e -> {
                    String value = tokenField.getValue();
                    if (!value.isBlank() && !value.startsWith("•")) {
                        telegram.setToken(value);
                        com.vaadin.flow.component.notification.Notification.show(
                                telegram.botUsername()
                                        .map(name -> getTranslation("system.telegram.connected", name))
                                        .orElse(getTranslation("system.telegram.tokenFailed")));
                    }
                });
        save.addThemeVariants(com.vaadin.flow.component.button.ButtonVariant.LUMO_PRIMARY);

        com.vaadin.flow.component.button.Button pair =
                new com.vaadin.flow.component.button.Button(getTranslation("system.telegram.pair"), e ->
                        telegram.createPairingLink(currentUser.id()).ifPresentOrElse(link -> {
                            com.vaadin.flow.component.dialog.Dialog dialog =
                                    new com.vaadin.flow.component.dialog.Dialog();
                            dialog.setHeaderTitle(getTranslation("system.telegram.dialogTitle"));
                            dialog.add(new com.vaadin.flow.component.html.Paragraph(
                                    getTranslation("system.telegram.dialogText")));
                            dialog.add(new com.vaadin.flow.component.Html(qrCodes.toSvg(link, 240)));
                            com.vaadin.flow.component.html.Anchor anchor =
                                    new com.vaadin.flow.component.html.Anchor(link, link);
                            anchor.setTarget("_blank");
                            dialog.add(anchor);
                            dialog.open();
                        }, () -> com.vaadin.flow.component.notification.Notification.show(
                                getTranslation("system.telegram.needToken"))));

        com.vaadin.flow.component.orderedlayout.HorizontalLayout row =
                new com.vaadin.flow.component.orderedlayout.HorizontalLayout(tokenField, save, pair);
        row.setAlignItems(com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment.END);
        add(row);

        String status = telegram.botUsername()
                .map(name -> telegram.isLinked(currentUser.id())
                        ? getTranslation("system.telegram.statusLinked", name)
                        : getTranslation("system.telegram.statusNotLinked", name))
                .orElse(telegram.isConfigured()
                        ? getTranslation("system.telegram.tokenNoBot")
                        : getTranslation("system.telegram.noBot"));
        add(statusLine(status, telegram.botUsername().isPresent()));
    }

    private Span statusLine(String text, boolean ok) {
        Span span = new Span((ok ? "✅ " : "❌ ") + text);
        span.getStyle().set("font-family", "monospace").set("display", "block");
        return span;
    }

    private String shorten(String version) {
        return version == null ? "?" : version.split(" on ")[0];
    }
}
