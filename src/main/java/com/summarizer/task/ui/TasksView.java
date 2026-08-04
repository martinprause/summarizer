package com.summarizer.task.ui;

import com.summarizer.base.CurrentUser;
import com.summarizer.task.Task;
import com.summarizer.task.TaskRepository;
import com.summarizer.task.TaskService;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.Tabs;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Aufgaben: Liste + Gantt-Diagramm (frappe-gantt), Fälligkeiten per Drag
 * verschiebbar, Inhalte lassen sich Aufgaben zuordnen (Item-Detail).
 */
@Route("tasks")
@PageTitle("Aufgaben — Summarizer Studio")
@PermitAll
public class TasksView extends VerticalLayout {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final TaskRepository repository;
    private final TaskService service;
    private final CurrentUser currentUser;

    private final Grid<Task> grid = new Grid<>();
    private final TaskGanttComponent gantt = new TaskGanttComponent();
    private final Checkbox hideDone = new Checkbox();
    private final Select<String> viewMode = new Select<>();
    private final DatePicker filterFrom = new DatePicker();
    private final DatePicker filterTo = new DatePicker();
    private final Span ganttEmpty = new Span();
    private Map<Long, Long> itemCounts = Map.of();

    public TasksView(TaskRepository repository, TaskService service, CurrentUser currentUser) {
        this.repository = repository;
        this.service = service;
        this.currentUser = currentUser;
        setPadding(true);
        setSizeFull();
        addClassName("fade-in");

        add(new H2(getTranslation("tasks.title")));

        Button create = new Button(getTranslation("tasks.new"), e -> openDialog(null));
        create.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        hideDone.setLabel(getTranslation("tasks.hideDone"));
        hideDone.setValue(true);
        hideDone.addValueChangeListener(e -> refresh());

        viewMode.setItems("Day", "Week", "Month");
        viewMode.setValue("Week");
        viewMode.setItemLabelGenerator(mode -> switch (mode) {
            case "Day" -> getTranslation("tasks.view.day");
            case "Month" -> getTranslation("tasks.view.month");
            default -> getTranslation("tasks.view.week");
        });
        viewMode.setWidth("140px");
        viewMode.addValueChangeListener(e -> gantt.setViewMode(e.getValue()));

        // Datums-Eingrenzung: nur Aufgaben im gewählten Zeitraum (Gantt + Liste)
        filterFrom.setPlaceholder(getTranslation("tasks.filter.from"));
        filterFrom.setClearButtonVisible(true);
        filterFrom.setWidth("150px");
        filterFrom.addValueChangeListener(e -> refresh());
        filterTo.setPlaceholder(getTranslation("tasks.filter.to"));
        filterTo.setClearButtonVisible(true);
        filterTo.setWidth("150px");
        filterTo.addValueChangeListener(e -> refresh());

        HorizontalLayout toolbar = new HorizontalLayout(create, hideDone, viewMode,
                filterFrom, filterTo);
        toolbar.setAlignItems(Alignment.CENTER);
        toolbar.getStyle().set("flex-wrap", "wrap").set("gap", "1em");
        add(toolbar);

        // Gantt oben, Liste darunter — umschaltbar per Tabs
        Tab ganttTab = new Tab(getTranslation("tasks.tab.gantt"));
        Tab listTab = new Tab(getTranslation("tasks.tab.list"));
        Tabs tabs = new Tabs(ganttTab, listTab);
        add(tabs);

        gantt.setWidthFull();
        // Feste Mindesthoehe — als Flex-Kind kollabiert die SVG-Flaeche sonst
        gantt.setMinHeight("460px");
        ganttEmpty.setText(getTranslation("tasks.noneGantt"));
        ganttEmpty.getStyle().set("color", "var(--lumo-secondary-text-color)");
        ganttEmpty.setVisible(false);
        buildGrid();
        grid.setVisible(false);
        add(gantt, ganttEmpty, grid);
        expand(grid);

        tabs.addSelectedChangeListener(e -> {
            boolean showGantt = e.getSelectedTab() == ganttTab;
            gantt.setVisible(showGantt);
            ganttEmpty.setVisible(showGantt && ganttEmpty.isVisible());
            grid.setVisible(!showGantt);
        });

        gantt.addTaskClickListener(taskId ->
                repository.findByIdAndUserId(taskId, currentUser.id()).ifPresent(this::openDialogFor));
        gantt.addTaskMoveListener((taskId, start, end) ->
                repository.findByIdAndUserId(taskId, currentUser.id()).ifPresent(task -> {
                    task.setStartDate(LocalDate.parse(start));
                    task.setDueDate(LocalDate.parse(end));
                    repository.save(task);
                    Notification.show(getTranslation("tasks.moved"));
                    refresh();
                }));
        gantt.addTaskProgressListener((taskId, progress) ->
                repository.findByIdAndUserId(taskId, currentUser.id()).ifPresent(task -> {
                    task.setProgress(progress);
                    if (progress >= 100) {
                        task.setStatus(Task.Status.DONE);
                    }
                    repository.save(task);
                    refresh();
                }));

        refresh();
    }

    private void openDialogFor(Task task) {
        getUI().ifPresent(ui -> ui.access(() -> openDialog(task)));
    }

    // ---------- Liste ----------

    private void buildGrid() {
        grid.addComponentColumn(task -> {
            Span badge = new Span(statusLabel(task.getStatus()));
            badge.getStyle().set("background", statusColor(task))
                    .set("color", "white").set("border-radius", "999px")
                    .set("padding", "0.15em 0.8em").set("font-size", "0.78em")
                    .set("font-weight", "600").set("cursor", "pointer");
            badge.getElement().setProperty("title", getTranslation("tasks.statusToggle"));
            badge.getElement().addEventListener("click", e -> {
                task.setStatus(switch (task.getStatus()) {
                    case TODO -> Task.Status.DOING;
                    case DOING -> Task.Status.DONE;
                    case DONE -> Task.Status.TODO;
                });
                if (task.getStatus() == Task.Status.DONE) {
                    task.setProgress(100);
                }
                repository.save(task);
                refresh();
            });
            return badge;
        }).setHeader(getTranslation("tasks.column.status")).setWidth("130px").setFlexGrow(0);

        grid.addColumn(Task::getTitle).setHeader(getTranslation("tasks.column.title")).setFlexGrow(1);
        grid.addColumn(t -> t.getStartDate() == null ? "" : DATE.format(t.getStartDate()))
                .setHeader(getTranslation("tasks.column.start")).setAutoWidth(true).setFlexGrow(0);
        grid.addComponentColumn(task -> {
            if (task.getDueDate() == null) {
                return new Span("");
            }
            Span due = new Span(DATE.format(task.getDueDate()));
            if (task.getStatus() != Task.Status.DONE && task.getDueDate().isBefore(LocalDate.now())) {
                due.setText(due.getText() + " · " + getTranslation("tasks.overdue"));
                due.getStyle().set("color", "#c62828").set("font-weight", "700");
            }
            return due;
        }).setHeader(getTranslation("tasks.column.due")).setAutoWidth(true).setFlexGrow(0);
        grid.addColumn(t -> t.getProgress() + " %")
                .setHeader(getTranslation("tasks.column.progress")).setAutoWidth(true).setFlexGrow(0);
        grid.addColumn(t -> itemCounts.getOrDefault(t.getId(), 0L))
                .setHeader(getTranslation("tasks.column.items")).setAutoWidth(true).setFlexGrow(0);
        grid.addComponentColumn(task -> {
            Button edit = new Button(getTranslation("tasks.edit"), e -> openDialog(task));
            edit.addThemeVariants(ButtonVariant.LUMO_SMALL);
            Button delete = new Button(getTranslation("tasks.delete"), e -> confirmDelete(task));
            delete.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ERROR);
            return new HorizontalLayout(edit, delete);
        }).setHeader("").setAutoWidth(true).setFlexGrow(0);
        grid.setWidthFull();
    }

    private void confirmDelete(Task task) {
        ConfirmDialog dialog = new ConfirmDialog();
        dialog.setHeader(getTranslation("tasks.delete.header"));
        dialog.setText(getTranslation("tasks.delete.text", task.getTitle()));
        dialog.setCancelable(true);
        dialog.setCancelText(getTranslation("tasks.cancel"));
        dialog.setConfirmText(getTranslation("tasks.delete"));
        dialog.setConfirmButtonTheme("error primary");
        dialog.addConfirmListener(e -> {
            repository.delete(task);
            Notification.show(getTranslation("tasks.deleted"));
            refresh();
        });
        dialog.open();
    }

    // ---------- Anlegen / Bearbeiten ----------

    private void openDialog(Task existing) {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle(existing == null
                ? getTranslation("tasks.dialog.new") : getTranslation("tasks.dialog.edit"));
        dialog.setWidth("520px");

        TextField title = new TextField(getTranslation("tasks.field.title"));
        title.setWidthFull();
        TextArea notes = new TextArea(getTranslation("tasks.field.notes"));
        notes.setWidthFull();
        notes.setHeight("90px");
        DatePicker start = new DatePicker(getTranslation("tasks.field.start"));
        DatePicker due = new DatePicker(getTranslation("tasks.field.due"));
        IntegerField progress = new IntegerField(getTranslation("tasks.field.progress"));
        progress.setMin(0);
        progress.setMax(100);
        progress.setStepButtonsVisible(true);
        progress.setStep(10);
        progress.setWidth("140px");
        Select<Task.Status> status = new Select<>();
        status.setLabel(getTranslation("tasks.field.status"));
        status.setItems(Task.Status.values());
        status.setItemLabelGenerator(this::statusLabel);

        // Farbwahl: nativer Farbdialog des Browsers + "automatisch nach Status"
        Span colorLabel = new Span(getTranslation("tasks.field.color"));
        colorLabel.getStyle().set("font-size", "var(--lumo-font-size-s)")
                .set("color", "var(--lumo-secondary-text-color)");
        com.vaadin.flow.component.html.Input colorInput =
                new com.vaadin.flow.component.html.Input();
        colorInput.setType("color");
        colorInput.getStyle().set("width", "46px").set("height", "34px")
                .set("padding", "0").set("border", "none")
                .set("background", "none").set("cursor", "pointer");
        Checkbox autoColor = new Checkbox(getTranslation("tasks.field.colorAuto"));
        autoColor.addValueChangeListener(e ->
                colorInput.setEnabled(!Boolean.TRUE.equals(e.getValue())));

        if (existing == null) {
            start.setValue(LocalDate.now());
            due.setValue(LocalDate.now().plusDays(7));
            progress.setValue(0);
            status.setValue(Task.Status.TODO);
            autoColor.setValue(true);
            colorInput.setEnabled(false);
            colorInput.setValue("#3a4ad8");
        } else {
            title.setValue(existing.getTitle());
            notes.setValue(existing.getNotes() == null ? "" : existing.getNotes());
            start.setValue(existing.getStartDate());
            due.setValue(existing.getDueDate());
            progress.setValue(existing.getProgress());
            status.setValue(existing.getStatus());
            boolean hasColor = existing.getColor() != null && !existing.getColor().isBlank();
            autoColor.setValue(!hasColor);
            colorInput.setEnabled(hasColor);
            colorInput.setValue(hasColor ? existing.getColor() : "#3a4ad8");
        }

        Button save = new Button(getTranslation("tasks.save"), e -> {
            if (title.getValue().isBlank()) {
                title.setInvalid(true);
                title.setErrorMessage(getTranslation("tasks.titleRequired"));
                return;
            }
            Task task = existing == null ? new Task(currentUser.id(), title.getValue().strip()) : existing;
            task.setTitle(title.getValue().strip());
            task.setNotes(notes.getValue().isBlank() ? null : notes.getValue());
            task.setStartDate(start.getValue());
            task.setDueDate(due.getValue());
            task.setProgress(progress.getValue() == null ? 0 : progress.getValue());
            task.setStatus(status.getValue());
            task.setColor(Boolean.TRUE.equals(autoColor.getValue()) ? null : colorInput.getValue());
            repository.save(task);
            dialog.close();
            Notification.show(getTranslation("tasks.saved"));
            refresh();
        });
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        HorizontalLayout dates = new HorizontalLayout(start, due);
        HorizontalLayout colorRow = new HorizontalLayout(colorLabel, colorInput, autoColor);
        colorRow.setAlignItems(Alignment.CENTER);
        HorizontalLayout meta = new HorizontalLayout(status, progress, colorRow);
        meta.setAlignItems(Alignment.END);
        VerticalLayout form = new VerticalLayout(title, notes, dates, meta);
        form.setPadding(false);

        // Verknüpfte Inhalte (nur beim Bearbeiten)
        if (existing != null) {
            form.add(linkedItemsSection(existing));
        }

        dialog.add(form);
        dialog.getFooter().add(save);
        dialog.open();
    }

    private VerticalLayout linkedItemsSection(Task task) {
        VerticalLayout section = new VerticalLayout();
        section.setPadding(false);
        section.setSpacing(false);
        Span heading = new Span(getTranslation("tasks.linkedItems"));
        heading.getStyle().set("font-weight", "700").set("margin-top", "0.5em");
        section.add(heading);
        List<Map<String, Object>> items = service.itemsForTask(task.getId());
        if (items.isEmpty()) {
            Span none = new Span(getTranslation("tasks.noLinkedItems"));
            none.getStyle().set("color", "var(--lumo-secondary-text-color)")
                    .set("font-size", "0.9em");
            section.add(none);
            return section;
        }
        for (Map<String, Object> item : items) {
            Long itemId = ((Number) item.get("id")).longValue();
            com.vaadin.flow.component.html.Anchor link = new com.vaadin.flow.component.html.Anchor(
                    "items/" + itemId, String.valueOf(item.get("title")));
            Button remove = new Button(getTranslation("tasks.unlink"), e -> {
                service.unlinkItem(itemId, task.getId());
                e.getSource().getParent().ifPresent(p -> p.setVisible(false));
            });
            remove.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
            HorizontalLayout row = new HorizontalLayout(link, remove);
            row.setAlignItems(Alignment.CENTER);
            section.add(row);
        }
        return section;
    }

    // ---------- Daten ----------

    private void refresh() {
        List<Task> all = hideDone.getValue()
                ? repository.findByUserIdAndStatusNotOrderByDueDateAscIdAsc(currentUser.id(), Task.Status.DONE)
                : repository.findByUserIdOrderByDueDateAscIdAsc(currentUser.id());
        // Datums-Eingrenzung: Aufgabe bleibt, wenn ihr Zeitraum den Filter überlappt
        LocalDate from = filterFrom.getValue();
        LocalDate to = filterTo.getValue();
        if (from != null || to != null) {
            all = all.stream().filter(task -> {
                LocalDate taskStart = task.getStartDate() != null ? task.getStartDate() : task.getDueDate();
                LocalDate taskEnd = task.getDueDate() != null ? task.getDueDate() : task.getStartDate();
                if (taskStart == null) {
                    return false;
                }
                return (to == null || !taskStart.isAfter(to))
                        && (from == null || !taskEnd.isBefore(from));
            }).toList();
        }
        itemCounts = service.itemCounts(currentUser.id());
        grid.setItems(all);

        List<TaskGanttComponent.GanttTask> ganttTasks = new ArrayList<>();
        for (Task task : all) {
            LocalDate startDate = task.getStartDate() != null ? task.getStartDate() : task.getDueDate();
            LocalDate endDate = task.getDueDate() != null ? task.getDueDate() : task.getStartDate();
            if (startDate == null || endDate == null) {
                continue;   // ohne Datum nicht im Gantt darstellbar (nur in der Liste)
            }
            ganttTasks.add(new TaskGanttComponent.GanttTask(String.valueOf(task.getId()),
                    task.getTitle(), startDate.toString(), endDate.toString(),
                    task.getProgress(), statusColor(task)));
        }
        gantt.setTasks(ganttTasks);
        boolean empty = ganttTasks.isEmpty();
        ganttEmpty.setVisible(empty && gantt.isVisible());
    }

    private String statusLabel(Task.Status status) {
        return getTranslation("tasks.status." + status.name());
    }

    private String statusColor(Task task) {
        if (task.getColor() != null && !task.getColor().isBlank()) {
            return task.getColor();
        }
        return switch (task.getStatus()) {
            case TODO -> "#3a4ad8";
            case DOING -> "#f9a825";
            case DONE -> "#2e7d32";
        };
    }
}
