package com.summarizer.base.ui;

import com.summarizer.base.JobProgressService;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Composite;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.progressbar.ProgressBar;

/**
 * Fortschrittsbalken für einen Hintergrund-Job (JobProgressService-Key).
 * Pollt alle 2 s, solange die View offen ist; zeigt danach das Ergebnis.
 */
public class JobProgressBar extends Composite<Div> {

    private final JobProgressService jobs;
    private final String key;
    private final ProgressBar bar = new ProgressBar();
    private final Span label = new Span();

    public JobProgressBar(JobProgressService jobs, String key) {
        this.jobs = jobs;
        this.key = key;
        bar.setWidth("320px");
        label.getStyle().set("font-size", "0.85em")
                .set("color", "var(--vaadin-text-color-secondary, #666)");
        getContent().add(label, bar);
        getContent().getStyle().set("display", "flex").set("align-items", "center")
                .set("gap", "0.8em").set("min-height", "28px");
        refresh();
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        UI ui = attachEvent.getUI();
        refresh();
        // Aktualisierung per @Push aus einem Hintergrund-Thread — kein UI-Poll,
        // der sonst die Navigation blockiert.
        Thread.ofVirtual().start(() -> {
            try {
                while (true) {
                    Thread.sleep(2000);
                    ui.access(this::refresh);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                // UI detached
            }
        });
    }


    private void refresh() {
        jobs.get(key).ifPresentOrElse(progress -> {
            if (progress.running()) {
                getContent().setVisible(true);
                bar.setVisible(true);
                bar.setValue(progress.fraction());
                label.setText(getTranslation("job.progress", progress.label(),
                        String.valueOf(progress.done()), String.valueOf(progress.total())));
            } else if (progress.result() != null) {
                getContent().setVisible(true);
                bar.setVisible(false);
                label.setText(getTranslation("job.done", progress.result()));
            } else {
                getContent().setVisible(false);
            }
        }, () -> getContent().setVisible(false));
    }
}
