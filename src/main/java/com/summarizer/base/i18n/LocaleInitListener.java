package com.summarizer.base.i18n;

import com.summarizer.settings.AppSettingsService;
import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.VaadinServiceInitListener;
import org.springframework.stereotype.Component;

/**
 * Setzt beim Start jeder Browser-Sitzung die Sprache aus dem System-Setting.
 */
@Component
public class LocaleInitListener implements VaadinServiceInitListener {

    private final AppSettingsService settings;

    public LocaleInitListener(AppSettingsService settings) {
        this.settings = settings;
    }

    @Override
    public void serviceInit(ServiceInitEvent event) {
        event.getSource().addSessionInitListener(sessionInit ->
                sessionInit.getSession().setLocale(TranslationProvider.localeFor(
                        settings.get(TranslationProvider.LANGUAGE_KEY, "de"))));
    }
}
