package com.summarizer.token.ui;

import com.summarizer.token.ApiToken;
import com.summarizer.token.ApiTokenRepository;
import com.summarizer.token.QrCodeService;
import com.summarizer.token.TokenService;
import com.vaadin.flow.component.Html;
import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.server.VaadinServletRequest;
import com.summarizer.user.User;
import com.summarizer.user.UserRepository;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * API-Token-Verwaltung des eingeloggten Users.
 */
@Route("tokens")
@PageTitle("API-Tokens — Summarizer Studio")
@PermitAll
public class TokensView extends VerticalLayout {

    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());

    private final TokenService tokenService;
    private final ApiTokenRepository tokenRepository;
    private final QrCodeService qrCodes;
    private final User user;
    private final Grid<ApiToken> grid = new Grid<>();

    public TokensView(TokenService tokenService, ApiTokenRepository tokenRepository,
                      QrCodeService qrCodes,
                      com.summarizer.base.CurrentUser currentUser) {
        this.tokenService = tokenService;
        this.tokenRepository = tokenRepository;
        this.qrCodes = qrCodes;
        this.user = currentUser.get();
        setPadding(true);
        addClassName("fade-in");

        add(new H2(getTranslation("tokens.title")));
        add(new Paragraph(getTranslation("tokens.intro")));

        Button create = new Button(getTranslation("tokens.create"), e -> openCreateDialog());
        create.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        add(create);

        grid.addColumn(ApiToken::getName).setHeader(getTranslation("tokens.column.name")).setAutoWidth(true);
        grid.addColumn(t -> FORMAT.format(t.getCreatedAt()))
                .setHeader(getTranslation("tokens.column.created")).setAutoWidth(true);
        grid.addColumn(t -> t.getLastUsedAt() == null ? "—" : FORMAT.format(t.getLastUsedAt()))
                .setHeader(getTranslation("tokens.column.lastUsed")).setAutoWidth(true);
        grid.addComponentColumn(token -> {
            if (token.isRevoked()) {
                return new Span(getTranslation("tokens.revokedState"));
            }
            Button revoke = new Button(getTranslation("tokens.revoke"), e -> {
                tokenService.revoke(token.getId());
                refresh();
                Notification.show(getTranslation("tokens.revokedNotification"));
            });
            revoke.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_SMALL);
            return revoke;
        }).setHeader(getTranslation("tokens.column.status"));
        grid.setWidthFull();
        add(grid);
        refresh();
    }

    private void openCreateDialog() {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle(getTranslation("tokens.dialog.create"));
        TextField name = new TextField(getTranslation("tokens.field.name"));
        name.setWidthFull();
        Button create = new Button(getTranslation("tokens.generate"), e -> {
            if (name.getValue().isBlank()) {
                name.setInvalid(true);
                return;
            }
            TokenService.CreatedToken created = tokenService.createToken(user.getId(), name.getValue().trim());
            dialog.close();
            showPlaintextDialog(created.plaintext());
            refresh();
        });
        create.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        dialog.add(new VerticalLayout(name, create));
        dialog.open();
    }

    private void showPlaintextDialog(String plaintext) {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle(getTranslation("tokens.dialog.created"));
        TextArea tokenField = new TextArea();
        tokenField.setValue(plaintext);
        tokenField.setReadOnly(true);
        tokenField.setWidth("420px");

        // QR-Payload für App-Pairing: {"url":"…","token":"…"}
        String payload = "{\"url\":\"" + serverBaseUrl() + "\",\"token\":\"" + plaintext + "\"}";
        Html qr = new Html(qrCodes.toSvg(payload, 220));

        dialog.add(new VerticalLayout(
                new Paragraph(getTranslation("tokens.createdHint")),
                tokenField,
                qr,
                new Button(getTranslation("tokens.close"), e -> dialog.close())));
        dialog.open();
    }

    private String serverBaseUrl() {
        VaadinServletRequest request = (VaadinServletRequest) VaadinRequest.getCurrent();
        String scheme = request.getHeader("X-Forwarded-Proto");
        if (scheme == null) {
            scheme = request.getScheme();
        }
        String host = request.getHeader("X-Forwarded-Host");
        if (host == null) {
            host = request.getHeader("Host");
        }
        return scheme + "://" + host;
    }

    private void refresh() {
        grid.setItems(tokenRepository.findByUserIdOrderByCreatedAtDesc(user.getId()));
    }
}
