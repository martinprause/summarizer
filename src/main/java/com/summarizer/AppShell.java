package com.summarizer;

import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.component.page.Push;
import com.vaadin.flow.theme.aura.Aura;

/**
 * App-Shell: Aura-Theme explizit + Server-Push (für Streaming-Chat und
 * Hintergrund-Updates in die UI).
 */
@Push
@StyleSheet(Aura.STYLESHEET)
public class AppShell implements AppShellConfigurator {
}
