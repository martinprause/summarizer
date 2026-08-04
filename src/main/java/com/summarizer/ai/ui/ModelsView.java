package com.summarizer.ai.ui;

import com.summarizer.ai.OllamaAdminService;
import com.summarizer.ai.OllamaClient;
import com.summarizer.settings.AppSettingsService;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.progressbar.ProgressBar;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.RolesAllowed;

import java.util.List;

/**
 * KI-Modelle (nur ADMIN): aktive Modelle wählen (nur installierte),
 * neue aus der Ollama-Bibliothek laden, installierte löschen.
 * Alles läuft lokal — keine Cloud-Provider, keine API-Keys.
 */
@Route("models")
@PageTitle("KI-Modelle — Summarizer Studio")
@RolesAllowed("ADMIN")
public class ModelsView extends VerticalLayout {

    private final OllamaClient ollama;
    private final OllamaAdminService admin;
    private final AppSettingsService settings;
    private final com.summarizer.ai.EmbeddingService embeddingService;
    private final com.summarizer.base.JobProgressService jobs;
    private final com.summarizer.ai.OllamaLibraryService library;

    private final ComboBox<String> chatModelBox = new ComboBox<>();
    private final ComboBox<String> embeddingModelBox = new ComboBox<>();
    private final Grid<OllamaClient.ModelInfo> grid = new Grid<>();
    private final Span pullInfo = new Span();

    public ModelsView(OllamaClient ollama, OllamaAdminService admin, AppSettingsService settings,
                      com.summarizer.ai.EmbeddingService embeddingService,
                      com.summarizer.base.JobProgressService jobs,
                      com.summarizer.ai.OllamaLibraryService library) {
        this.ollama = ollama;
        this.admin = admin;
        this.settings = settings;
        this.embeddingService = embeddingService;
        this.jobs = jobs;
        this.library = library;
        setPadding(true);
        addClassName("fade-in");

        add(new H2(getTranslation("models.heading")));
        buildActiveModelsSection();
        buildInstallSection();
        refresh();
    }

    // ---------- Aktive Modelle (nur installierte wählbar) ----------

    private void buildActiveModelsSection() {
        add(new H3(getTranslation("models.activeHeading")));
        add(new Paragraph(getTranslation("models.activeIntro")));

        chatModelBox.setLabel(getTranslation("models.chatModel"));
        chatModelBox.setWidth("270px");
        chatModelBox.setHelperText(getTranslation("models.chatModelHelper"));
        chatModelBox.addValueChangeListener(e -> {
            if (!e.isFromClient() || e.getValue() == null) {
                return;
            }
            settings.set(OllamaClient.CHAT_MODEL_KEY, e.getValue());
            ollama.resetModelCache();
            Notification.show(getTranslation("models.chatModelSet", e.getValue()));
        });

        embeddingModelBox.setLabel(getTranslation("models.embeddingModel"));
        embeddingModelBox.setWidth("270px");
        embeddingModelBox.setHelperText(getTranslation("models.embeddingModelHelper"));
        embeddingModelBox.addValueChangeListener(e -> {
            if (!e.isFromClient() || e.getValue() == null || e.getValue().equals(e.getOldValue())) {
                return;
            }
            confirmEmbeddingChange(e.getValue(), e.getOldValue());
        });

        HorizontalLayout row = new HorizontalLayout(chatModelBox, embeddingModelBox);
        row.setAlignItems(Alignment.END);
        row.getStyle().set("flex-wrap", "wrap").set("gap", "1.2em");
        add(row);
    }

    /** Embedding-Wechsel: warnen, dann Vektor-DB im Modal-Fenster neu aufbauen. */
    private void confirmEmbeddingChange(String newModel, String previousModel) {
        ConfirmDialog confirm = new ConfirmDialog();
        confirm.setHeader(getTranslation("models.embeddingConfirmHeader"));
        confirm.setText(getTranslation("models.embeddingConfirmText"));
        confirm.setCancelable(true);
        confirm.setCancelText(getTranslation("models.cancel"));
        confirm.setConfirmText(getTranslation("models.embeddingConfirmButton"));
        confirm.addCancelListener(e -> embeddingModelBox.setValue(previousModel));
        confirm.addConfirmListener(e -> {
            settings.set(OllamaClient.EMBEDDING_MODEL_KEY, newModel);
            ollama.resetModelCache();
            openReembedDialog();
        });
        confirm.open();
    }

    /** Modales Fenster mit Fortschrittsbalken während des Neuaufbaus. */
    private void openReembedDialog() {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle(getTranslation("models.reembedTitle"));
        dialog.setModal(true);
        dialog.setCloseOnEsc(false);
        dialog.setCloseOnOutsideClick(false);
        dialog.setWidth("460px");

        Paragraph hint = new Paragraph(getTranslation("models.reembedHint"));
        ProgressBar bar = new ProgressBar();
        bar.setIndeterminate(true);
        bar.setWidthFull();
        Span status = new Span(getTranslation("models.reembedStarting"));
        status.getStyle().set("font-size", "0.9em");

        Button close = new Button(getTranslation("models.reembedBackground"), e -> dialog.close());
        close.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        HorizontalLayout footer = new HorizontalLayout(close);
        footer.setWidthFull();
        footer.setJustifyContentMode(JustifyContentMode.END);
        dialog.getFooter().add(footer);

        dialog.add(new VerticalLayout(hint, bar, status));
        dialog.open();

        embeddingService.reembedAllAsync();

        // Fortschritt per Push nachziehen
        com.vaadin.flow.component.UI ui = com.vaadin.flow.component.UI.getCurrent();
        String key = com.summarizer.base.JobProgressService.reembedKey();
        // Übersetzungen im UI-Thread auflösen (im Hintergrund-Thread kein VaadinService)
        String progressPattern = getTranslation("models.reembedProgress");
        String donePattern = getTranslation("models.reembedDone");
        String closeText = getTranslation("models.close");
        Thread.ofVirtual().start(() -> {
            try {
                for (int i = 0; i < 3600; i++) {
                    Thread.sleep(1500);
                    var progress = jobs.get(key).orElse(null);
                    if (progress == null) {
                        continue;
                    }
                    ui.access(() -> {
                        if (progress.running()) {
                            bar.setIndeterminate(false);
                            bar.setValue(progress.fraction());
                            status.setText(java.text.MessageFormat.format(progressPattern,
                                    String.valueOf(progress.done()), String.valueOf(progress.total())));
                        } else {
                            bar.setIndeterminate(false);
                            bar.setValue(1);
                            status.setText(java.text.MessageFormat.format(donePattern, progress.result()));
                            close.setText(closeText);
                        }
                    });
                    if (!progress.running()) {
                        return;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                // Fenster/Session geschlossen
            }
        });
    }

    // ---------- Installierte Modelle + Download ----------

    private void buildInstallSection() {
        add(new H3(getTranslation("models.installedHeading")));

        ComboBox<String> available = new ComboBox<>(getTranslation("models.newModel"));
        available.setPlaceholder(getTranslation("models.searchLibrary"));
        available.setItems(libraryWithInstalledFirst());
        available.setAllowCustomValue(true);
        available.addCustomValueSetListener(e -> available.setValue(e.getDetail()));
        available.setWidth("240px");
        available.setHelperText(getTranslation("models.newModelHelper",
                library.availableModels().size()));

        ComboBox<String> tag = new ComboBox<>(getTranslation("models.size"));
        tag.setPlaceholder(getTranslation("models.variant"));
        tag.setWidth("150px");
        tag.setAllowCustomValue(true);
        tag.addCustomValueSetListener(e -> tag.setValue(e.getDetail()));
        available.addValueChangeListener(e -> {
            String value = e.getValue();
            if (value == null || value.isBlank() || value.contains(":")) {
                tag.setItems(List.of());
                tag.clear();
                return;
            }
            List<String> tags = library.tagsFor(value);
            tag.setItems(tags);
            tag.setValue(tags.contains("latest") ? "latest" : tags.getFirst());
        });

        Button download = new Button(getTranslation("models.download"), e -> {
            String model = available.getValue();
            if (model == null || model.isBlank()) {
                Notification.show(getTranslation("models.selectFirst"));
                return;
            }
            model = model.strip();
            if (!model.contains(":") && tag.getValue() != null && !tag.getValue().isBlank()) {
                model = model + ":" + tag.getValue().strip();
            }
            admin.pullAsync(model);
            Notification.show(getTranslation("models.downloadStarted", model));
            refresh();
        });
        download.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        Button refreshButton = new Button(getTranslation("models.refresh"), e -> refresh());

        // Einheitliche Grundlinie: Felder mit Label, Buttons daneben ausgerichtet
        HorizontalLayout row = new HorizontalLayout(available, tag, download, refreshButton);
        row.setAlignItems(Alignment.BASELINE);
        row.setDefaultVerticalComponentAlignment(Alignment.END);
        row.getStyle().set("flex-wrap", "wrap").set("gap", "0.8em");
        add(row);

        add(new com.summarizer.base.ui.JobProgressBar(jobs,
                com.summarizer.base.JobProgressService.reembedKey()));
        pullInfo.getStyle().set("font-size", "0.85em");
        add(pullInfo);

        grid.addComponentColumn(model -> {
            Span name = new Span(model.name());
            boolean isChat = sameModel(model.name(), ollama.chatModel());
            boolean isEmbedding = sameModel(model.name(), ollama.embeddingModel());
            if (isChat || isEmbedding) {
                name.getStyle().set("font-weight", "700");
            }
            HorizontalLayout cell = new HorizontalLayout(name);
            cell.setAlignItems(Alignment.CENTER);
            cell.setSpacing(false);
            cell.getStyle().set("gap", "0.5em");
            if (isChat) {
                cell.add(badge(getTranslation("models.badgeChat"), "#1a73e8"));
            }
            if (isEmbedding) {
                cell.add(badge(getTranslation("models.badgeEmbedding"), "#2e7d32"));
            }
            return cell;
        }).setHeader(getTranslation("models.gridModel")).setFlexGrow(1);
        grid.addColumn(m -> "%.1f GB".formatted(m.sizeBytes() / 1024.0 / 1024.0 / 1024.0))
                .setHeader(getTranslation("models.size")).setAutoWidth(true).setFlexGrow(0);
        grid.addComponentColumn(model -> {
            Button delete = new Button(getTranslation("models.delete"), e -> confirmDelete(model.name()));
            delete.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ERROR);
            // Aktive Modelle nicht löschbar
            boolean active = isActiveModel(model.name());
            delete.setEnabled(!active);
            if (active) {
                delete.setTooltipText(getTranslation("models.activeTooltip"));
            }
            return delete;
        }).setHeader("").setAutoWidth(true).setFlexGrow(0);
        grid.setWidthFull();
        grid.setAllRowsVisible(true);
        add(grid);
    }

    /** Bibliotheksliste: bereits installierte Modelle zuerst, markiert mit ✓. */
    private List<String> libraryWithInstalledFirst() {
        java.util.Set<String> installedBase = ollama.listModels().stream()
                .map(m -> m.name().contains(":") ? m.name().substring(0, m.name().indexOf(':')) : m.name())
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        List<String> all = library.availableModels();
        List<String> ordered = new java.util.ArrayList<>();
        installedBase.stream().filter(all::contains).forEach(ordered::add);
        all.stream().filter(m -> !installedBase.contains(m)).forEach(ordered::add);
        return ordered;
    }

    private Span badge(String text, String color) {
        Span badge = new Span(text);
        badge.getStyle().set("background", color).set("color", "white")
                .set("border-radius", "999px").set("padding", "0.1em 0.7em")
                .set("font-size", "0.72em").set("font-weight", "600");
        return badge;
    }

    /** Aktives Chat- oder Embedding-Modell — auch wenn nur als ":latest" installiert. */
    private boolean isActiveModel(String name) {
        return sameModel(name, ollama.chatModel()) || sameModel(name, ollama.embeddingModel());
    }

    /** "modell" und "modell:latest" bezeichnen dasselbe Modell. */
    private boolean sameModel(String installed, String active) {
        return installed.equals(active)
                || installed.equals(active + ":latest")
                || active.equals(installed + ":latest");
    }

    private void confirmDelete(String model) {
        if (isActiveModel(model)) {
            Notification.show(getTranslation("models.activeInUse"));
            return;
        }
        ConfirmDialog dialog = new ConfirmDialog();
        dialog.setHeader(getTranslation("models.deleteHeader"));
        dialog.setText(getTranslation("models.deleteText", model));
        dialog.setCancelable(true);
        dialog.setCancelText(getTranslation("models.cancel"));
        dialog.setConfirmText(getTranslation("models.delete"));
        dialog.setConfirmButtonTheme("error primary");
        dialog.addConfirmListener(e -> {
            // Erneut prüfen — Auswahl kann sich seit Öffnen des Dialogs geändert haben
            if (isActiveModel(model)) {
                Notification.show(getTranslation("models.nowActive"));
                refresh();
                return;
            }
            try {
                ollama.deleteModel(model);
                Notification.show(getTranslation("models.deleted", model));
            } catch (Exception ex) {
                Notification.show(getTranslation("models.deleteFailed", ex.getMessage()));
            }
            refresh();
        });
        dialog.open();
    }

    private void refresh() {
        List<OllamaClient.ModelInfo> installed = ollama.listModels();
        grid.setItems(installed);

        // Nur installierte Modelle zur Auswahl anbieten
        List<String> names = installed.stream().map(OllamaClient.ModelInfo::name).sorted().toList();
        chatModelBox.setItems(names);
        embeddingModelBox.setItems(names);
        setIfPresent(chatModelBox, names, ollama.chatModel());
        setIfPresent(embeddingModelBox, names, ollama.embeddingModel());

        var status = admin.status();
        if (status.isEmpty()) {
            pullInfo.setText("");
        } else {
            StringBuilder sb = new StringBuilder(getTranslation("models.downloads")).append(' ');
            status.forEach((model, state) -> sb.append(model).append(" → ").append(state).append("   "));
            pullInfo.setText(sb.toString());
        }
    }

    /** Aktives Modell vorauswählen — auch wenn es nur als ":latest" installiert ist. */
    private void setIfPresent(ComboBox<String> box, List<String> names, String active) {
        if (names.contains(active)) {
            box.setValue(active);
            return;
        }
        names.stream()
                .filter(n -> n.equals(active + ":latest") || n.startsWith(active + ":"))
                .findFirst()
                .ifPresentOrElse(box::setValue, box::clear);
    }
}
