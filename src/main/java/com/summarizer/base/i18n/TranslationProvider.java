package com.summarizer.base.i18n;

import com.vaadin.flow.i18n.I18NProvider;
import org.springframework.stereotype.Component;

import java.text.MessageFormat;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * Übersetzungen für die gesamte Oberfläche. Bundles liegen unter
 * src/main/resources/i18n/translations_de.properties bzw. _en.properties.
 * Unbekannte Schlüssel werden sichtbar als "!key!" ausgegeben statt zu crashen.
 */
@Component
public class TranslationProvider implements I18NProvider {

    public static final Locale GERMAN = Locale.GERMAN;
    public static final Locale ENGLISH = Locale.ENGLISH;

    public static final String LANGUAGE_KEY = "ui.language";

    @Override
    public List<Locale> getProvidedLocales() {
        return List.of(GERMAN, ENGLISH);
    }

    @Override
    public String getTranslation(String key, Locale locale, Object... params) {
        Locale effective = locale != null && "en".equals(locale.getLanguage()) ? ENGLISH : GERMAN;
        try {
            ResourceBundle bundle = ResourceBundle.getBundle("i18n.translations", effective);
            String value = bundle.getString(key);
            return params.length > 0 ? new MessageFormat(value, effective).format(params) : value;
        } catch (MissingResourceException e) {
            return "!" + key + "!";
        }
    }

    /** Locale aus dem gespeicherten Sprach-Setting ("de"/"en"). */
    public static Locale localeFor(String languageSetting) {
        return "en".equals(languageSetting) ? ENGLISH : GERMAN;
    }
}
