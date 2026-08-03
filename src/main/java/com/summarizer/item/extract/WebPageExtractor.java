package com.summarizer.item.extract;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

/**
 * Lädt eine Webseite und extrahiert Titel, Haupttext (Readability light),
 * Vorschaubild (og:image) und das Roh-HTML für die Offline-Kopie.
 */
@Component
public class WebPageExtractor {

    private static final int MAX_TEXT_CHARS = 100_000;
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Summarizer/0.1";

    public Extracted extract(String url) throws Exception {
        Document doc = Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .timeout(15_000)
                .followRedirects(true)
                .get();

        String html = doc.outerHtml();
        String thumbnail = firstAttr(doc,
                "meta[property=og:image]", "meta[name=twitter:image]");

        doc.select("script, style, nav, header, footer, aside, form, noscript").remove();
        Element main = firstNonEmpty(doc, "article", "main", "[role=main]", "#content", ".content");
        String text = (main != null ? main : doc.body()).text();
        if (text.length() > MAX_TEXT_CHARS) {
            text = text.substring(0, MAX_TEXT_CHARS);
        }
        return new Extracted(doc.title(), text, thumbnail, html);
    }

    private String firstAttr(Document doc, String... selectors) {
        for (String selector : selectors) {
            Element el = doc.selectFirst(selector);
            if (el != null && !el.attr("content").isBlank()) {
                return el.attr("content");
            }
        }
        return null;
    }

    private Element firstNonEmpty(Document doc, String... selectors) {
        for (String selector : selectors) {
            Element el = doc.selectFirst(selector);
            if (el != null && el.text().length() > 200) {
                return el;
            }
        }
        return null;
    }

    public record Extracted(String title, String text, String thumbnailUrl, String html) {
    }
}
