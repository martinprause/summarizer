package com.summarizer.base.ui;

import com.summarizer.ai.ui.ChatView;
import com.summarizer.category.ui.CategoriesView;
import com.summarizer.item.ui.DashboardView;
import com.summarizer.item.ui.InboxView;
import com.summarizer.token.ui.TokensView;
import com.summarizer.user.ui.UsersView;
import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.component.applayout.DrawerToggle;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.sidenav.SideNav;
import com.vaadin.flow.component.sidenav.SideNavItem;
import com.vaadin.flow.router.Layout;
import com.vaadin.flow.spring.security.AuthenticationContext;
import jakarta.annotation.security.PermitAll;

/**
 * Rahmen-Layout mit Drawer-Navigation für alle Studio-Views.
 */
@Layout
@PermitAll
@com.vaadin.flow.component.dependency.CssImport("./styles.css")
public class MainLayout extends AppLayout {

    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final com.summarizer.base.JobProgressService jobs;
    private final com.summarizer.base.CurrentUser currentUser;
    private final com.summarizer.ai.OllamaClient ollama;
    private final com.vaadin.flow.component.html.Span statusChip =
            new com.vaadin.flow.component.html.Span();
    private final com.vaadin.flow.component.html.Span healthChip =
            new com.vaadin.flow.component.html.Span();
    /** true sobald Chat- UND Embedding-Modell installiert sind — dann keine Checks mehr. */
    private volatile boolean modelsReady = false;
    /** Gesundheits-Snapshot, im Hintergrund-Thread befüllt (HTTP nicht im UI-Thread). */
    private volatile boolean dbDown;
    private volatile boolean ollamaDown;
    private volatile boolean whisperDown;

    private final com.summarizer.ai.WhisperClient whisper;
    private final com.summarizer.item.pipeline.PipelineEtaService eta;

    public MainLayout(AuthenticationContext authContext,
                      com.summarizer.settings.AppSettingsService settings,
                      org.springframework.jdbc.core.JdbcTemplate jdbc,
                      com.summarizer.base.JobProgressService jobs,
                      com.summarizer.base.CurrentUser currentUser,
                      com.summarizer.ai.OllamaClient ollama,
                      com.summarizer.ai.WhisperClient whisper,
                      com.summarizer.item.pipeline.PipelineEtaService eta) {
        this.jdbc = jdbc;
        this.jobs = jobs;
        this.currentUser = currentUser;
        this.ollama = ollama;
        this.whisper = whisper;
        this.eta = eta;
        com.vaadin.flow.component.html.Div logo = new com.vaadin.flow.component.html.Div();
        logo.setText("S");
        logo.addClassName("s-logo");

        H1 title = new H1("Summarizer Studio");
        title.getStyle().set("font-size", "1.05rem").set("margin", "0")
                .set("font-weight", "750");

        SideNav nav = new SideNav();
        nav.addItem(new SideNavItem(getTranslation("nav.overview"), WelcomeView.class, VaadinIcon.HOME.create()));
        nav.addItem(new SideNavItem(getTranslation("nav.dashboard"), DashboardView.class, VaadinIcon.GRID_BIG_O.create()));
        nav.addItem(new SideNavItem(getTranslation("nav.archiveChat"), ChatView.class, VaadinIcon.COMMENTS.create()));
        nav.addItem(new SideNavItem(getTranslation("nav.knowledgeGraph"), com.summarizer.graph.ui.GraphView.class,
                VaadinIcon.CLUSTER.create()));
        nav.addItem(new SideNavItem(getTranslation("nav.tasks"), com.summarizer.task.ui.TasksView.class,
                VaadinIcon.TASKS.create()));
        nav.addItem(new SideNavItem(getTranslation("nav.inbox"), InboxView.class, VaadinIcon.INBOX.create()));
        nav.addItem(new SideNavItem(getTranslation("nav.categories"), CategoriesView.class, VaadinIcon.TAGS.create()));
        nav.addItem(new SideNavItem(getTranslation("nav.apiTokens"), TokensView.class, VaadinIcon.KEY.create()));
        nav.addItem(new SideNavItem(getTranslation("nav.users"), UsersView.class, VaadinIcon.USERS.create()));
        nav.addItem(new SideNavItem(getTranslation("nav.aiModels"), com.summarizer.ai.ui.ModelsView.class,
                VaadinIcon.AUTOMATION.create()));
        nav.addItem(new SideNavItem(getTranslation("nav.system"), SystemView.class, VaadinIcon.COG.create()));
        nav.getStyle().set("margin", "0.5em");

        Button darkMode = new Button(VaadinIcon.MOON_O.create());
        darkMode.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        darkMode.setTooltipText(getTranslation("nav.themeToggle"));
        darkMode.getStyle().set("margin-left", "auto");
        // Aura folgt color-scheme; Wahl in localStorage persistieren
        darkMode.getElement().executeJs("""
                const saved = localStorage.getItem('summarizer-theme');
                if (saved) document.documentElement.style.colorScheme = saved;
                this.addEventListener('click', () => {
                    const current = document.documentElement.style.colorScheme === 'dark' ? 'light' : 'dark';
                    document.documentElement.style.colorScheme = current;
                    localStorage.setItem('summarizer-theme', current);
                });
                """);

        Button logout = new Button(VaadinIcon.SIGN_OUT.create(), e -> authContext.logout());
        logout.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        logout.setTooltipText(getTranslation("nav.logout"));
        logout.getStyle().set("margin-right", "0.5em");
        logout.setVisible(com.summarizer.security.SecurityConfig.isLoginEnabled(settings));

        // Statusleiste: laufende Verarbeitung (Pipeline + Hintergrund-Jobs)
        statusChip.getStyle().set("font-size", "0.8rem").set("font-weight", "600")
                .set("background", "var(--s-accent-soft, #eef)")
                .set("color", "var(--s-accent, #3b4bd8)")
                .set("border-radius", "999px").set("padding", "0.25em 0.9em")
                .set("white-space", "nowrap");
        statusChip.setVisible(false);

        // Rote Warnleiste: Datenbank/Ollama/Whisper nicht erreichbar
        healthChip.getStyle().set("font-size", "0.8rem").set("font-weight", "700")
                .set("background", "#c62828").set("color", "white")
                .set("border-radius", "999px").set("padding", "0.25em 0.9em")
                .set("white-space", "nowrap");
        healthChip.setVisible(false);

        HorizontalLayout navbar = new HorizontalLayout(new DrawerToggle(), logo, title,
                healthChip, statusChip, darkMode, logout);
        navbar.setAlignItems(HorizontalLayout.Alignment.CENTER);
        navbar.setWidthFull();

        addToDrawer(nav);
        addToNavbar(navbar);
    }

    private com.vaadin.flow.shared.Registration notifierRegistration;

    @Override
    protected void onAttach(com.vaadin.flow.component.AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        com.vaadin.flow.component.UI ui = attachEvent.getUI();
        // KEIN UI-Poll: ein dauerhafter Poll kollidiert mit der Client-Navigation
        // (Menü-Klicks verpuffen). Updates kommen ausschließlich per @Push.
        ui.setPollInterval(-1);
        refreshStatus();
        startStatusWatcher(ui);
        notifierRegistration = com.summarizer.base.UiNotifier.register(message ->
                ui.access(() -> com.vaadin.flow.component.notification.Notification.show(
                        message, 10000,
                        com.vaadin.flow.component.notification.Notification.Position.BOTTOM_END)));
        notifyBackgroundWork();
    }

    /** Einmaliger Hinweis beim Öffnen, wenn im Hintergrund noch verarbeitet wird
     *  (z. B. Import-Rückstand nach Neustart oder Telegram-Nachholen). */
    private void notifyBackgroundWork() {
        try {
            Integer pending = jdbc.queryForObject("""
                    SELECT count(*) FROM items
                    WHERE user_id = ? AND status IN ('PENDING', 'PROCESSING')
                    """, Integer.class, currentUser.id());
            if (pending != null && pending > 0) {
                com.vaadin.flow.component.notification.Notification.show(
                        getTranslation("nav.backgroundWork", pending), 8000,
                        com.vaadin.flow.component.notification.Notification.Position.BOTTOM_END);
            }
        } catch (Exception ignored) {
            // DB nicht erreichbar — Statusleiste meldet ohnehin
        }
    }

    @Override
    protected void onDetach(com.vaadin.flow.component.DetachEvent detachEvent) {
        if (notifierRegistration != null) {
            notifierRegistration.remove();
            notifierRegistration = null;
        }
        super.onDetach(detachEvent);
    }

    /** Hintergrund-Wächter: schiebt Status-Updates per Push in die UI. */
    private void startStatusWatcher(com.vaadin.flow.component.UI ui) {
        Thread.ofVirtual().start(() -> {
            int tick = 0;
            try {
                while (true) {
                    // Health-Checks (HTTP) laufen hier im Hintergrund-Thread,
                    // alle 12 Sekunden — nicht bei jedem UI-Refresh
                    if (tick % 3 == 0) {
                        checkHealth();
                    }
                    tick++;
                    ui.access(this::refreshStatus);
                    Thread.sleep(4000);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                // Seite geschlossen (UI detached) — Wächter beenden
            }
        });
    }

    /** DB-, Ollama- und Whisper-Erreichbarkeit prüfen (Whisper nur, wenn Audio wartet). */
    private void checkHealth() {
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            dbDown = false;
        } catch (Exception e) {
            dbDown = true;
        }
        try {
            ollamaDown = !ollama.isAvailable();
        } catch (Exception e) {
            ollamaDown = true;
        }
        try {
            // Whisper ist optional — nur warnen, wenn Sprachnachrichten darauf warten
            boolean audioPending = !dbDown && Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT count(*) > 0 FROM items
                    WHERE type = 'AUDIO' AND status IN ('PENDING', 'PROCESSING')
                    """, Boolean.class));
            whisperDown = audioPending && !whisper.isAvailable();
        } catch (Exception e) {
            whisperDown = false;
        }
    }

    /** Fehlende Pflicht-Modelle (Chat + Embedding), leer sobald alles installiert ist. */
    private java.util.List<String> missingModels() {
        if (modelsReady) {
            return java.util.List.of();
        }
        try {
            java.util.List<String> installed = ollama.listModels().stream()
                    .map(com.summarizer.ai.OllamaClient.ModelInfo::name).toList();
            java.util.List<String> missing = new java.util.ArrayList<>();
            for (String required : java.util.List.of(ollama.chatModel(), ollama.embeddingModel())) {
                boolean present = installed.stream().anyMatch(name -> name.equals(required)
                        || name.equals(required + ":latest") || required.equals(name + ":latest"));
                if (!present) {
                    missing.add(required);
                }
            }
            if (missing.isEmpty()) {
                modelsReady = true;
            }
            return missing;
        } catch (Exception e) {
            return java.util.List.of();   // Ollama nicht erreichbar — meldet die System-Seite
        }
    }

    private void refreshStatus() {
        // Rote Warnleiste zuerst — unabhängig vom normalen Status-Chip
        java.util.List<String> down = new java.util.ArrayList<>();
        if (dbDown) {
            down.add(getTranslation("nav.service.db"));
        }
        if (ollamaDown) {
            down.add(getTranslation("nav.service.ollama"));
        }
        if (whisperDown) {
            down.add(getTranslation("nav.service.whisper"));
        }
        healthChip.setText(down.isEmpty() ? ""
                : getTranslation("nav.unreachable", String.join(" · ", down)));
        healthChip.setVisible(!down.isEmpty());
        try {
            Integer active = jdbc.queryForObject("""
                    SELECT count(*) FROM items
                    WHERE user_id = ? AND status IN ('PENDING', 'PROCESSING')
                    """, Integer.class, currentUser.id());
            StringBuilder text = new StringBuilder();
            java.util.List<String> missing = missingModels();
            if (!missing.isEmpty()) {
                text.append(getTranslation("nav.modelsLoading", String.join(", ", missing)));
            }
            jobs.anyRunning().ifPresent(job -> {
                if (!text.isEmpty()) {
                    text.append("  ·  ");
                }
                text.append("🔄 ").append(job.label())
                        .append(' ').append(job.done()).append('/').append(job.total());
            });
            if (active != null && active > 0) {
                if (!text.isEmpty()) {
                    text.append("  ·  ");
                }
                text.append(getTranslation("nav.status.processing", active));
                // Ab 6 wartenden Items: geschätzte Restdauer aus dem Durchsatz
                if (active > 5) {
                    eta.remaining(active).ifPresent(duration ->
                            text.append(' ').append(getTranslation("nav.status.eta",
                                    formatDuration(duration))));
                }
            }
            statusChip.setText(text.toString());
            statusChip.setVisible(!text.isEmpty());
        } catch (Exception e) {
            statusChip.setVisible(false);
        }
    }

    /** "~3 min", "~1 h 20 min", unter einer Minute "< 1 min". */
    private static String formatDuration(java.time.Duration duration) {
        long minutes = duration.toMinutes();
        if (minutes < 1) {
            return "< 1 min";
        }
        if (minutes < 60) {
            return "~" + minutes + " min";
        }
        long rest = minutes % 60;
        return "~" + duration.toHours() + " h" + (rest > 0 ? " " + rest + " min" : "");
    }
}
