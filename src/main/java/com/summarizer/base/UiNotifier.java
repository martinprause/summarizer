package com.summarizer.base;

import com.vaadin.flow.shared.Registration;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Server-seitige Push-Hinweise an alle offenen Browser-Sitzungen.
 * Views registrieren sich beim Attach und zeigen Nachrichten per ui.access
 * an — kein Polling, Auslieferung ausschließlich über @Push.
 */
public final class UiNotifier {

    private static final Set<Consumer<String>> LISTENERS = ConcurrentHashMap.newKeySet();

    private UiNotifier() {
    }

    public static Registration register(Consumer<String> listener) {
        LISTENERS.add(listener);
        return () -> LISTENERS.remove(listener);
    }

    public static void broadcast(String message) {
        for (Consumer<String> listener : LISTENERS) {
            try {
                listener.accept(message);
            } catch (Exception ignored) {
                // Sitzung bereits geschlossen — Listener räumt sich beim Detach ab
            }
        }
    }
}
