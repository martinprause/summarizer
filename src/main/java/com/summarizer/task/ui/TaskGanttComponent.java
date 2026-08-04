package com.summarizer.task.ui;

import com.vaadin.flow.component.HasSize;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.component.dependency.JsModule;
import com.vaadin.flow.component.dependency.NpmPackage;
import com.vaadin.flow.component.react.ReactAdapterComponent;

import java.io.Serializable;
import java.util.List;

/**
 * Gantt-Diagramm auf Basis von frappe-gantt (gleiches Adapter-Muster
 * wie der Wissensgraph). Balken ziehen ändert Start/Fälligkeit.
 */
@NpmPackage(value = "frappe-gantt", version = "1.0.3")
@JsModule("./components/task-gantt-adapter.tsx")
@Tag("task-gantt-adapter")
public class TaskGanttComponent extends ReactAdapterComponent implements HasSize {

    public record GanttTask(String id, String name, String start, String end,
                            int progress, String color) implements Serializable {
    }

    @FunctionalInterface
    public interface TaskClickListener extends Serializable {
        void onClick(long taskId);
    }

    @FunctionalInterface
    public interface TaskMoveListener extends Serializable {
        void onMove(long taskId, String start, String end);
    }

    @FunctionalInterface
    public interface TaskProgressListener extends Serializable {
        void onProgress(long taskId, int progress);
    }

    public TaskGanttComponent() {
        setState("ganttTasks", List.of());
        setState("viewMode", "Week");
        getElement().getStyle().set("display", "block");
    }

    public void setTasks(List<GanttTask> tasks) {
        setState("ganttTasks", tasks);
    }

    /** "Day", "Week" oder "Month". */
    public void setViewMode(String mode) {
        setState("viewMode", mode);
    }

    public void addTaskClickListener(TaskClickListener listener) {
        getElement().addEventListener("task-click", event -> {
            var detail = event.getEventData().get("event.detail");
            listener.onClick(Long.parseLong(detail.get("taskId").asText()));
        }).addEventData("event.detail");
    }

    public void addTaskMoveListener(TaskMoveListener listener) {
        getElement().addEventListener("task-move", event -> {
            var detail = event.getEventData().get("event.detail");
            listener.onMove(Long.parseLong(detail.get("taskId").asText()),
                    detail.get("start").asText(), detail.get("end").asText());
        }).addEventData("event.detail");
    }

    public void addTaskProgressListener(TaskProgressListener listener) {
        getElement().addEventListener("task-progress", event -> {
            var detail = event.getEventData().get("event.detail");
            listener.onProgress(Long.parseLong(detail.get("taskId").asText()),
                    detail.get("progress").asInt());
        }).addEventData("event.detail");
    }
}
