package com.summarizer.ai.ui;

import com.summarizer.ai.ChatHistoryService;
import com.summarizer.ai.EmbeddingService;
import com.summarizer.ai.LlmRouter;
import com.summarizer.ai.RagService;
import com.summarizer.base.CurrentUser;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * RAG-Chat mit Token-Streaming (@Push), Markdown-Rendering und
 * persistentem Verlauf. Quellen als Links auf die Item-Detailansicht.
 */
@Route("chat")
@PageTitle("Archiv-Chat — Summarizer Studio")
@PermitAll
public class ChatView extends VerticalLayout {

    private final RagService rag;
    private final LlmRouter llm;
    private final ChatHistoryService history;
    private final CurrentUser currentUser;
    private final com.summarizer.item.ItemRepository itemRepository;
    private final Long userId;

    private final Div messages = new Div();
    private final TextField input = new TextField();
    private final Button send = new Button();
    private final com.vaadin.flow.component.textfield.IntegerField topK =
            new com.vaadin.flow.component.textfield.IntegerField();

    private final Parser markdownParser = Parser.builder().build();
    private final HtmlRenderer markdownRenderer = HtmlRenderer.builder().escapeHtml(true).build();

    public ChatView(RagService rag, LlmRouter llm, ChatHistoryService history, CurrentUser currentUser,
                    com.summarizer.item.ItemRepository itemRepository) {
        this.rag = rag;
        this.llm = llm;
        this.history = history;
        this.currentUser = currentUser;
        this.itemRepository = itemRepository;
        this.userId = currentUser.id();
        setPadding(true);
        setSizeFull();
        addClassName("fade-in");

        topK.setLabel(getTranslation("chat.hits"));
        topK.setValue(5);
        topK.setMin(1);
        topK.setMax(20);
        topK.setStepButtonsVisible(true);
        topK.setWidth("110px");
        topK.setTooltipText(getTranslation("chat.hitsTooltip"));

        Button clear = new Button(getTranslation("chat.clearHistory"), e -> {
            history.clear(userId);
            messages.removeAll();
        });
        clear.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);

        HorizontalLayout header = new HorizontalLayout(new H2(getTranslation("chat.heading")), topK, clear);
        header.setAlignItems(Alignment.END);
        header.setWidthFull();
        add(header);
        add(new Paragraph(getTranslation("chat.intro")));

        messages.getStyle().set("overflow-y", "auto").set("width", "100%")
                .set("display", "flex").set("flex-direction", "column").set("gap", "0.8em");
        add(messages);
        setFlexGrow(1, messages);

        input.setPlaceholder(getTranslation("chat.placeholder"));
        input.setWidthFull();
        send.setText(getTranslation("chat.ask"));
        send.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        send.addClickShortcut(com.vaadin.flow.component.Key.ENTER);
        send.addClickListener(e -> ask());

        HorizontalLayout inputRow = new HorizontalLayout(input, send);
        inputRow.setWidthFull();
        inputRow.setFlexGrow(1, input);
        add(inputRow);

        // Verlauf laden
        for (ChatHistoryService.Message message : history.lastMessages(userId, 50)) {
            if ("USER".equals(message.role())) {
                messages.add(bubble(getTranslation("chat.you", message.text()), true));
            } else {
                messages.add(markdownBubble(message.text()));
            }
        }
    }

    private void ask() {
        String question = input.getValue().strip();
        if (question.isEmpty()) {
            return;
        }
        input.clear();
        send.setEnabled(false);
        history.save(userId, "USER", question);
        messages.add(bubble(getTranslation("chat.you", question), true));

        Div streaming = bubble("…", false);
        messages.add(streaming);

        UI ui = UI.getCurrent();
        int hitCount = topK.getValue() == null ? 5 : topK.getValue();
        // Übersetzungen im UI-Thread auflösen (im Hintergrund-Thread kein VaadinService)
        String noResultsText = getTranslation("chat.noResults");
        String llmUnavailableText = getTranslation("chat.llmUnavailable");
        String untitledText = getTranslation("chat.untitled");
        String relevancePattern = getTranslation("chat.relevance");
        // Retrieval + Streaming im Hintergrund-Thread, Tokens per @Push in die UI
        Thread.ofVirtual().start(() -> {
            try {
                RagService.Prompt prompt = rag.buildPrompt(userId, question, hitCount);
                if (prompt.text() == null) {
                    ui.access(() -> {
                        streaming.setText(noResultsText);
                        send.setEnabled(true);
                    });
                    return;
                }
                StringBuilder buffer = new StringBuilder();
                String full = llm.generateStreaming(prompt.text(), token -> {
                    buffer.append(token);
                    ui.access(() -> streaming.setText(buffer.toString()));
                });
                String answer = full == null || full.isBlank()
                        ? llmUnavailableText
                        : full.strip();
                history.save(userId, "ASSISTANT", answer);
                Div tiles = resultTiles(prompt.sources(), untitledText, relevancePattern);
                ui.access(() -> {
                    messages.remove(streaming);
                    messages.add(markdownBubble(answer));
                    messages.add(tiles);
                    send.setEnabled(true);
                    messages.getElement().executeJs("this.scrollTop = this.scrollHeight");
                });
            } catch (Exception ex) {
                ui.access(() -> {
                    streaming.setText(getTranslation("chat.error", ex.getMessage()));
                    send.setEnabled(true);
                });
            }
        });
    }

    private Div bubble(String text, boolean own) {
        Div div = new Div();
        div.setText(text);
        styleBubble(div, own);
        return div;
    }

    /** Antwort als gerendertes Markdown (escaped — kein rohes HTML aus dem LLM). */
    private Div markdownBubble(String markdown) {
        Div bubble = new Div();
        styleBubble(bubble, false);
        // innerHTML in eigenem Kind-Div, damit server-seitig angehängte
        // Elemente (Quellen-Links) nicht überschrieben werden
        Div content = new Div();
        content.getElement().setProperty("innerHTML",
                markdownRenderer.render(markdownParser.parse(markdown)));
        bubble.add(content);
        return bubble;
    }

    private void styleBubble(Div div, boolean own) {
        div.addClassName(own ? "s-bubble-user" : "s-bubble-answer");
        // Marker fuer DOM-Abfragen (Tests) beibehalten
        div.getStyle().set("border-radius", own ? "16px 16px 4px 16px" : "16px 16px 16px 4px");
    }

    /**
     * Treffer als kleine Kacheln: Vorschaubild, Titel, Zusammenfassung, Relevanz.
     * Reihenfolge = Relevanz (flex-wrap: links→rechts, oben→unten).
     */
    private Div resultTiles(List<EmbeddingService.SearchHit> sources,
                            String untitledText, String relevancePattern) {
        Div block = new Div();
        if (sources.isEmpty()) {
            return block;
        }
        block.getStyle().set("display", "flex").set("flex-wrap", "wrap")
                .set("gap", "0.6em").set("max-width", "900px")
                .set("align-self", "flex-start");

        Set<Long> seen = new LinkedHashSet<>();
        java.util.Map<Long, com.summarizer.item.Item> byId = new java.util.HashMap<>();
        itemRepository.findAllById(sources.stream().map(EmbeddingService.SearchHit::itemId).toList())
                .forEach(item -> byId.put(item.getId(), item));

        for (EmbeddingService.SearchHit hit : sources) {
            if (!seen.add(hit.itemId())) {
                continue;
            }
            com.summarizer.item.Item item = byId.get(hit.itemId());
            block.add(tile(hit, item, untitledText, relevancePattern));
        }
        return block;
    }

    private Div tile(EmbeddingService.SearchHit hit, com.summarizer.item.Item item,
                     String untitledText, String relevancePattern) {
        Div tile = new Div();
        tile.getStyle()
                .set("width", "170px")
                .set("border", "1px solid var(--vaadin-border-color, #ddd)")
                .set("border-radius", "10px")
                .set("overflow", "hidden")
                .set("cursor", "pointer")
                .set("background", "var(--vaadin-base-color, white)")
                .set("display", "flex").set("flex-direction", "column");
        tile.addClickListener(e ->
                UI.getCurrent().navigate(com.summarizer.item.ui.ItemDetailView.class, hit.itemId()));

        // Vorschau: og:image, bei Bildern das Bild selbst, sonst Typ-Icon-Fläche
        String thumbnail = item != null && item.getThumbnailUrl() != null && !item.getThumbnailUrl().isBlank()
                ? item.getThumbnailUrl()
                : ("IMAGE".equals(hit.type()) ? "files/" + hit.itemId() : null);
        if (thumbnail != null && !thumbnail.isBlank()) {
            com.vaadin.flow.component.html.Image img =
                    new com.vaadin.flow.component.html.Image(thumbnail, "");
            img.getStyle().set("width", "100%").set("height", "80px").set("object-fit", "cover");
            img.getElement().setAttribute("loading", "lazy");
            img.getElement().executeJs(
                    "this.addEventListener('error', () => this.style.display='none')");
            tile.add(img);
        } else {
            Div placeholder = new Div();
            placeholder.setText(switch (hit.type()) {
                case "WEBPAGE" -> "🌐";
                case "BOOKMARK" -> "🔖";
                case "IMAGE" -> "🖼";
                case "FILE" -> "📄";
                case "AUDIO" -> "🎙";
                default -> "📝";
            });
            placeholder.getStyle().set("height", "56px").set("display", "flex")
                    .set("align-items", "center").set("justify-content", "center")
                    .set("font-size", "1.6em")
                    .set("background", "var(--vaadin-contrast-5pct, #f0f0f0)");
            tile.add(placeholder);
        }

        Div body = new Div();
        body.getStyle().set("padding", "0.5em 0.7em").set("display", "flex")
                .set("flex-direction", "column").set("gap", "0.25em");

        Span title = new Span(hit.title() == null || hit.title().isBlank()
                ? untitledText : hit.title());
        title.getStyle().set("font-weight", "600").set("font-size", "0.78em")
                .set("line-height", "1.25")
                .set("overflow", "hidden").set("display", "-webkit-box")
                .set("-webkit-line-clamp", "2").set("-webkit-box-orient", "vertical");
        body.add(title);

        // Zusammenfassung unter der Kachel-Vorschau
        String summary = item != null && item.getSummary() != null && !item.getSummary().isBlank()
                ? item.getSummary()
                : hit.chunkText();
        if (summary != null && !summary.isBlank()) {
            Span text = new Span(summary);
            text.getStyle().set("font-size", "0.72em")
                    .set("color", "var(--vaadin-text-color-secondary, #666)")
                    .set("line-height", "1.3")
                    .set("overflow", "hidden").set("display", "-webkit-box")
                    .set("-webkit-line-clamp", "3").set("-webkit-box-orient", "vertical");
            body.add(text);
        }

        Span relevance = new Span(java.text.MessageFormat.format(
                relevancePattern, "%.2f".formatted(1 - hit.distance())));
        relevance.getStyle().set("font-size", "0.68em")
                .set("color", "var(--vaadin-primary-color, #1a73e8)");
        body.add(relevance);

        tile.add(body);
        return tile;
    }
}
