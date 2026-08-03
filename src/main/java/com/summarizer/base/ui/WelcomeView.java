package com.summarizer.base.ui;

import com.vaadin.flow.component.Html;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Startseite: zeigt, was die App kann — mit Live-Zahlen aus dem eigenen Archiv.
 */
@Route("willkommen")
@PageTitle("Übersicht — Summarizer Studio")
@PermitAll
public class WelcomeView extends VerticalLayout {

    private record Feature(String icon, String title, String text, String link, String linkText) {
    }

    public WelcomeView(JdbcTemplate jdbc, com.summarizer.base.CurrentUser currentUser) {
        setPadding(false);
        setSpacing(false);
        setWidthFull();
        setHeightFull();
        addClassName("fade-in");
        getStyle().set("overflow-y", "auto").set("align-items", "stretch");

        long items = count(jdbc, "SELECT count(*) FROM items WHERE user_id = ?", currentUser.id());
        long chunks = count(jdbc, """
                SELECT count(*) FROM item_embeddings e JOIN items i ON i.id = e.item_id
                WHERE i.user_id = ?""", currentUser.id());
        long entities = count(jdbc, "SELECT count(*) FROM entities WHERE user_id = ?", currentUser.id());
        long categories = count(jdbc, "SELECT count(*) FROM categories WHERE user_id = ?", currentUser.id());

        add(new Html(hero(items, chunks, entities, categories)));
        add(new Html(features()));
        add(new Html(pipeline()));
        add(new Html(footer()));
    }

    private long count(JdbcTemplate jdbc, String sql, Object... args) {
        try {
            Long value = jdbc.queryForObject(sql, Long.class, args);
            return value == null ? 0 : value;
        } catch (Exception e) {
            return 0;
        }
    }

    // ---------- Abschnitte ----------

    private String hero(long items, long chunks, long entities, long categories) {
        return """
                <section class="w-hero">
                  <div class="w-hero-inner">
                    <div class="w-badge">%s</div>
                    <h1>%s</h1>
                    <p class="w-lead">
                      %s
                    </p>
                    <div class="w-stats">
                      <div class="w-stat"><b>%d</b><span>%s</span></div>
                      <div class="w-stat"><b>%d</b><span>%s</span></div>
                      <div class="w-stat"><b>%d</b><span>%s</span></div>
                      <div class="w-stat"><b>%d</b><span>%s</span></div>
                    </div>
                    <div class="w-cta">
                      <a class="w-btn w-btn-primary" href="/" router-link>%s</a>
                      <a class="w-btn" href="/chat" router-link>%s</a>
                      <a class="w-btn" href="/graph" router-link>%s</a>
                    </div>
                  </div>
                </section>
                """.formatted(
                getTranslation("welcome.hero.badge"),
                getTranslation("welcome.hero.title"),
                getTranslation("welcome.hero.lead"),
                items, getTranslation("welcome.hero.stat.items"),
                chunks, getTranslation("welcome.hero.stat.chunks"),
                entities, getTranslation("welcome.hero.stat.entities"),
                categories, getTranslation("welcome.hero.stat.categories"),
                getTranslation("welcome.hero.cta.dashboard"),
                getTranslation("welcome.hero.cta.chat"),
                getTranslation("welcome.hero.cta.graph"));
    }

    private String features() {
        Feature[] features = {
            new Feature("🔎", getTranslation("welcome.feature.search.title"),
                getTranslation("welcome.feature.search.text"), "",
                getTranslation("welcome.feature.search.link")),
            new Feature("💬", getTranslation("welcome.feature.chat.title"),
                getTranslation("welcome.feature.chat.text"), "chat",
                getTranslation("welcome.feature.chat.link")),
            new Feature("🕸", getTranslation("welcome.feature.graph.title"),
                getTranslation("welcome.feature.graph.text"), "graph",
                getTranslation("welcome.feature.graph.link")),
            new Feature("🗂", getTranslation("welcome.feature.categories.title"),
                getTranslation("welcome.feature.categories.text"), "categories",
                getTranslation("welcome.feature.categories.link")),
            new Feature("📄", getTranslation("welcome.feature.formats.title"),
                getTranslation("welcome.feature.formats.text"), "",
                getTranslation("welcome.feature.formats.link")),
            new Feature("📱", getTranslation("welcome.feature.capture.title"),
                getTranslation("welcome.feature.capture.text"), "system",
                getTranslation("welcome.feature.capture.link")),
            new Feature("🔒", getTranslation("welcome.feature.privacy.title"),
                getTranslation("welcome.feature.privacy.text"), "system",
                getTranslation("welcome.feature.privacy.link")),
            new Feature("⭐", getTranslation("welcome.feature.favorites.title"),
                getTranslation("welcome.feature.favorites.text"), "",
                getTranslation("welcome.feature.favorites.link"))
        };

        StringBuilder cards = new StringBuilder();
        for (Feature feature : features) {
            cards.append("""
                    <article class="w-card">
                      <div class="w-icon">%s</div>
                      <h3>%s</h3>
                      <p>%s</p>
                      <a href="/%s" router-link>%s →</a>
                    </article>
                    """.formatted(feature.icon(), feature.title(), feature.text(),
                    feature.link(), feature.linkText()));
        }
        return """
                <section class="w-section">
                  <h2>%s</h2>
                  <div class="w-grid">%s</div>
                </section>
                """.formatted(getTranslation("welcome.features.heading"), cards);
    }

    private String pipeline() {
        return """
                <section class="w-section w-section-alt">
                  <h2>%s</h2>
                  <ol class="w-steps">
                    <li><span>1</span><b>%s</b>%s</li>
                    <li><span>2</span><b>%s</b>%s</li>
                    <li><span>3</span><b>%s</b>%s</li>
                    <li><span>4</span><b>%s</b>%s</li>
                    <li><span>5</span><b>%s</b>%s</li>
                  </ol>
                </section>
                """.formatted(
                getTranslation("welcome.pipeline.heading"),
                getTranslation("welcome.step1.title"), getTranslation("welcome.step1.text"),
                getTranslation("welcome.step2.title"), getTranslation("welcome.step2.text"),
                getTranslation("welcome.step3.title"), getTranslation("welcome.step3.text"),
                getTranslation("welcome.step4.title"), getTranslation("welcome.step4.text"),
                getTranslation("welcome.step5.title"), getTranslation("welcome.step5.text"));
    }

    private String footer() {
        return """
                <footer class="w-footer">
                  <span>Summarizer Studio</span> · %s ·
                  <a href="/system" router-link>%s</a>
                </footer>
                """.formatted(getTranslation("welcome.footer.tagline"),
                getTranslation("welcome.footer.settings"));
    }
}
