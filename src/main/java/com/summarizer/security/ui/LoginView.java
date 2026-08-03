package com.summarizer.security.ui;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.login.LoginForm;
import com.vaadin.flow.component.login.LoginI18n;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;

/**
 * Login: Benutzername/E-Mail + Passwort in einer zentrierten Karte auf
 * Verlaufs-Hintergrund. "Mit Google anmelden" erscheint nur, wenn
 * OAuth-Credentials konfiguriert sind.
 */
@Route(value = "login", autoLayout = false)
@PageTitle("Login — Summarizer Studio")
@AnonymousAllowed
@com.vaadin.flow.component.dependency.CssImport("./styles.css")
public class LoginView extends Div implements BeforeEnterObserver {

    private final LoginForm login = new LoginForm();

    public LoginView() {
        setSizeFull();
        getStyle()
                .set("display", "flex")
                .set("align-items", "center")
                .set("justify-content", "center")
                .set("background", "linear-gradient(135deg, #1a73e8 0%, #7c4dff 60%, #b388ff 100%)");

        Div card = new Div();
        card.getStyle()
                .set("background", "var(--vaadin-base-color, white)")
                .set("border-radius", "16px")
                .set("box-shadow", "0 18px 60px rgba(0,0,0,0.35)")
                .set("padding", "2.2em 2.4em 2em")
                .set("width", "380px")
                .set("max-width", "92vw")
                .set("display", "flex")
                .set("flex-direction", "column")
                .set("align-items", "stretch");

        Div logo = new Div();
        logo.setText("S");
        logo.getStyle()
                .set("width", "52px").set("height", "52px")
                .set("border-radius", "14px")
                .set("background", "linear-gradient(135deg, #1a73e8, #7c4dff)")
                .set("color", "white")
                .set("font-size", "1.6em").set("font-weight", "800")
                .set("display", "flex").set("align-items", "center").set("justify-content", "center")
                .set("margin", "0 auto 0.6em");
        card.add(logo);

        H1 title = new H1("Summarizer Studio");
        title.getStyle().set("font-size", "1.35em").set("margin", "0 auto 0.15em")
                .set("text-align", "center");
        card.add(title);

        Span tagline = new Span(getTranslation("login.tagline"));
        tagline.getStyle().set("display", "block").set("text-align", "center")
                .set("font-size", "0.85em")
                .set("color", "var(--vaadin-text-color-secondary, #666)")
                .set("margin-bottom", "0.8em");
        card.add(tagline);

        LoginI18n i18n = LoginI18n.createDefault();
        LoginI18n.Form form = i18n.getForm();
        form.setTitle("");
        form.setUsername(getTranslation("login.username"));
        form.setPassword(getTranslation("login.password"));
        form.setSubmit(getTranslation("login.submit"));
        LoginI18n.ErrorMessage error = i18n.getErrorMessage();
        error.setTitle(getTranslation("login.error.title"));
        error.setMessage(getTranslation("login.error.message"));
        i18n.setErrorMessage(error);
        login.setI18n(i18n);
        login.setForgotPasswordButtonVisible(false);
        login.setAction("login");
        login.getElement().getStyle().set("width", "100%");
        card.add(login);

        add(card);
    }



    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        if (event.getLocation().getQueryParameters().getParameters().containsKey("error")) {
            login.setError(true);
        }
    }
}
