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
                      com.summarizer.user.UserRepository users,
                      org.springframework.security.crypto.password.PasswordEncoder passwordEncoder,
                      @Value("${summarizer.ollama.chat-model}") String chatModel,
                      @Value("${summarizer.ollama.embedding-model}") String embeddingModel,
                      @Value("${summarizer.version:dev}") String appVersion,
                      com.summarizer.item.pipeline.IngestPipeline pipeline,
                      com.summarizer.base.JobProgressService jobs) {
        add(new H2(getTranslation("system.status.title")));
        add(statusLine(getTranslation("system.status.version", appVersion), true));
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
        addImportSection(settings, currentUser);
        addArchitectSection(settings, currentUser);
        addResummarizeSection(pipeline, jobs, currentUser);
        addDangerSection(jdbc, currentUser);
        addAccessSection(settings, currentUser, users, passwordEncoder);
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

    /** Import: max. Links pro Lesezeichen-Import, für Admins einstellbar. */
    private void addImportSection(com.summarizer.settings.AppSettingsService settings,
                                  com.summarizer.base.CurrentUser currentUser) {
        if (!"ADMIN".equals(currentUser.get().getRole())) {
            return;
        }
        add(new com.vaadin.flow.component.html.H2(getTranslation("system.import.title")));
        com.vaadin.flow.component.textfield.IntegerField limit =
                new com.vaadin.flow.component.textfield.IntegerField(
                        getTranslation("system.import.limit"));
        limit.setMin(1);
        limit.setMax(100000);
        limit.setStepButtonsVisible(true);
        limit.setWidth("260px");
        limit.setHelperText(getTranslation("system.import.limitHelper"));
        int current;
        try {
            current = Integer.parseInt(settings.get(
                    com.summarizer.item.ui.DashboardView.IMPORT_LIMIT_KEY,
                    String.valueOf(com.summarizer.item.ui.DashboardView.IMPORT_LIMIT_DEFAULT)));
        } catch (NumberFormatException e) {
            current = com.summarizer.item.ui.DashboardView.IMPORT_LIMIT_DEFAULT;
        }
        limit.setValue(current);
        limit.addValueChangeListener(e -> {
            if (!e.isFromClient() || e.getValue() == null || e.getValue() < 1) {
                return;
            }
            settings.set(com.summarizer.item.ui.DashboardView.IMPORT_LIMIT_KEY,
                    String.valueOf(e.getValue()));
            com.vaadin.flow.component.notification.Notification.show(
                    getTranslation("system.import.saved"));
        });
        add(limit);
    }

    /** Automatik: Kategorie-Architekt an/aus + Konfidenz-Schwelle. */
    private void addArchitectSection(com.summarizer.settings.AppSettingsService settings,
                                     com.summarizer.base.CurrentUser currentUser) {
        if (!"ADMIN".equals(currentUser.get().getRole())) {
            return;
        }
        add(new com.vaadin.flow.component.html.H2(getTranslation("system.architect.title")));
        com.vaadin.flow.component.html.Paragraph hint =
                new com.vaadin.flow.component.html.Paragraph(getTranslation("system.architect.hint"));
        hint.getStyle().set("color", "var(--lumo-secondary-text-color)").set("font-size", "0.9em");
        add(hint);

        com.vaadin.flow.component.checkbox.Checkbox enabled =
                new com.vaadin.flow.component.checkbox.Checkbox(
                        getTranslation("system.architect.enabled"));
        enabled.setValue(!"false".equals(settings.get(
                com.summarizer.category.CategoryArchitectService.ENABLED_KEY, "true")));
        enabled.addValueChangeListener(e -> {
            if (e.isFromClient()) {
                settings.set(com.summarizer.category.CategoryArchitectService.ENABLED_KEY,
                        Boolean.TRUE.equals(e.getValue()) ? "true" : "false");
                com.vaadin.flow.component.notification.Notification.show(
                        getTranslation("system.import.saved"));
            }
        });

        com.vaadin.flow.component.textfield.IntegerField threshold =
                new com.vaadin.flow.component.textfield.IntegerField(
                        getTranslation("system.architect.threshold"));
        threshold.setMin(10);
        threshold.setMax(100);
        threshold.setStepButtonsVisible(true);
        threshold.setStep(5);
        threshold.setWidth("220px");
        try {
            threshold.setValue(Math.round(Float.parseFloat(settings.get(
                    com.summarizer.category.CategoryArchitectService.THRESHOLD_KEY, "0.70")) * 100));
        } catch (NumberFormatException e) {
            threshold.setValue(70);
        }
        threshold.addValueChangeListener(e -> {
            if (e.isFromClient() && e.getValue() != null) {
                settings.set(com.summarizer.category.CategoryArchitectService.THRESHOLD_KEY,
                        String.valueOf(e.getValue() / 100.0f));
                com.vaadin.flow.component.notification.Notification.show(
                        getTranslation("system.import.saved"));
            }
        });
        add(enabled, threshold);
    }

    /** Wartung: alle Zusammenfassungen neu im Stichpunkt-Format erzeugen. */
    private void addResummarizeSection(com.summarizer.item.pipeline.IngestPipeline pipeline,
                                       com.summarizer.base.JobProgressService jobs,
                                       com.summarizer.base.CurrentUser currentUser) {
        add(new com.vaadin.flow.component.html.H2(getTranslation("system.resummarize.title")));
        com.vaadin.flow.component.html.Paragraph hint =
                new com.vaadin.flow.component.html.Paragraph(getTranslation("system.resummarize.hint"));
        hint.getStyle().set("color", "var(--lumo-secondary-text-color)").set("font-size", "0.9em");
        add(hint);

        Long userId = currentUser.id();
        String key = com.summarizer.base.JobProgressService.resummarizeKey(userId);
        com.vaadin.flow.component.button.Button run = new com.vaadin.flow.component.button.Button(
                getTranslation("system.resummarize.button"));
        if (jobs.isRunning(key)) {
            run.setEnabled(false);
            run.setText(getTranslation("system.resummarize.running"));
        }
        run.addClickListener(e -> {
            if (jobs.isRunning(key)) {
                com.vaadin.flow.component.notification.Notification.show(
                        getTranslation("system.resummarize.running"));
                return;
            }
            com.vaadin.flow.component.confirmdialog.ConfirmDialog confirm =
                    new com.vaadin.flow.component.confirmdialog.ConfirmDialog(
                            getTranslation("system.resummarize.title"),
                            getTranslation("system.resummarize.confirm"),
                            getTranslation("system.resummarize.button"), ev -> {
                                pipeline.resummarizeAll(userId);
                                run.setEnabled(false);
                                run.setText(getTranslation("system.resummarize.running"));
                                com.vaadin.flow.component.notification.Notification.show(
                                        getTranslation("system.resummarize.started"));
                            },
                            getTranslation("system.danger.cancel"), ev -> { });
            confirm.open();
        });
        add(run);
    }

    /** Zugriff: Login an/aus — Standard ist ohne Login (rein lokaler Betrieb). */
    private void addAccessSection(com.summarizer.settings.AppSettingsService settings,
                                  com.summarizer.base.CurrentUser currentUser,
                                  com.summarizer.user.UserRepository users,
                                  org.springframework.security.crypto.password.PasswordEncoder encoder) {
        if (!"ADMIN".equals(currentUser.get().getRole())) {
            return;
        }
        add(new com.vaadin.flow.component.html.H2(getTranslation("system.access.title")));
        com.vaadin.flow.component.html.Paragraph accessHint =
                new com.vaadin.flow.component.html.Paragraph(getTranslation("system.access.hint"));
        accessHint.getStyle().set("color", "var(--lumo-secondary-text-color)")
                .set("font-size", "0.9em");
        add(accessHint);
        // Warnung, solange das Standard-Passwort "admin" aktiv ist
        boolean defaultPassword = users.findByUsername("admin")
                .map(admin -> admin.getPasswordHash() != null
                        && encoder.matches("admin", admin.getPasswordHash()))
                .orElse(false);
        if (defaultPassword) {
            add(statusLine(getTranslation("system.access.defaultPw"), false));
        }
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

    /** Gefahrenzone: alle Inhalte des Users löschen — doppelt bestätigt. */
    private void addDangerSection(JdbcTemplate jdbc, com.summarizer.base.CurrentUser currentUser) {
        if (!"ADMIN".equals(currentUser.get().getRole())) {
            return;
        }
        com.vaadin.flow.component.html.H2 heading =
                new com.vaadin.flow.component.html.H2(getTranslation("system.danger.title"));
        heading.getStyle().set("color", "#c62828");
        add(heading);
        com.vaadin.flow.component.html.Paragraph hint =
                new com.vaadin.flow.component.html.Paragraph(getTranslation("system.danger.hint"));
        hint.getStyle().set("color", "var(--lumo-secondary-text-color)").set("font-size", "0.9em");
        add(hint);

        com.vaadin.flow.component.button.Button wipe = new com.vaadin.flow.component.button.Button(
                getTranslation("system.danger.button"), e -> openWipeDialog(jdbc, currentUser.id()));
        wipe.addThemeVariants(com.vaadin.flow.component.button.ButtonVariant.LUMO_ERROR);
        add(wipe);
    }

    private void openWipeDialog(JdbcTemplate jdbc, Long userId) {
        com.vaadin.flow.component.dialog.Dialog dialog = new com.vaadin.flow.component.dialog.Dialog();
        dialog.setHeaderTitle(getTranslation("system.danger.confirmTitle"));
        dialog.setWidth("480px");
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM items WHERE user_id = ?", Long.class, userId);
        com.vaadin.flow.component.html.Paragraph warning =
                new com.vaadin.flow.component.html.Paragraph(
                        getTranslation("system.danger.confirmText", count == null ? 0 : count));
        warning.getStyle().set("color", "#c62828").set("font-weight", "600");

        com.vaadin.flow.component.textfield.TextField confirmField =
                new com.vaadin.flow.component.textfield.TextField(
                        getTranslation("system.danger.typeToConfirm"));
        confirmField.setWidthFull();
        confirmField.setPlaceholder("LÖSCHEN");
        confirmField.setValueChangeMode(com.vaadin.flow.data.value.ValueChangeMode.EAGER);

        com.vaadin.flow.component.button.Button doDelete = new com.vaadin.flow.component.button.Button(
                getTranslation("system.danger.execute"));
        doDelete.addThemeVariants(com.vaadin.flow.component.button.ButtonVariant.LUMO_ERROR,
                com.vaadin.flow.component.button.ButtonVariant.LUMO_PRIMARY);
        doDelete.setEnabled(false);
        confirmField.addValueChangeListener(e ->
                doDelete.setEnabled("LÖSCHEN".equals(e.getValue()) || "DELETE".equals(e.getValue())));
        doDelete.addClickListener(e -> {
            wipeContent(jdbc, userId);
            dialog.close();
            com.vaadin.flow.component.notification.Notification.show(
                    getTranslation("system.danger.done"), 6000,
                    com.vaadin.flow.component.notification.Notification.Position.MIDDLE);
        });
        com.vaadin.flow.component.button.Button cancel = new com.vaadin.flow.component.button.Button(
                getTranslation("system.danger.cancel"), e -> dialog.close());
        cancel.addThemeVariants(com.vaadin.flow.component.button.ButtonVariant.LUMO_TERTIARY);

        dialog.add(warning, confirmField);
        dialog.getFooter().add(cancel, doDelete);
        dialog.open();
    }

    /** Inhalte + abgeleitete Daten löschen; Kategorien, Aufgaben und Einstellungen bleiben. */
    private void wipeContent(JdbcTemplate jdbc, Long userId) {
        java.util.List<String> files = new java.util.ArrayList<>();
        files.addAll(jdbc.queryForList(
                "SELECT file_path FROM items WHERE user_id = ? AND file_path IS NOT NULL",
                String.class, userId));
        files.addAll(jdbc.queryForList(
                "SELECT snapshot_path FROM items WHERE user_id = ? AND snapshot_path IS NOT NULL",
                String.class, userId));
        // Reihenfolge egal — Kaskaden räumen embeddings, item_tags, item_entities, item_tasks
        jdbc.update("DELETE FROM entities WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM tags WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM chat_messages WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM items WHERE user_id = ?", userId);
        for (String path : files) {
            try {
                java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(path));
            } catch (Exception ignored) {
                // Datei fehlt/gesperrt — Datenbank ist die Wahrheit
            }
        }
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
