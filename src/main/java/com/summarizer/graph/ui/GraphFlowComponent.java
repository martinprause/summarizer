package com.summarizer.graph.ui;

import com.vaadin.flow.component.HasSize;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.component.dependency.JsModule;
import com.vaadin.flow.component.dependency.NpmPackage;
import com.vaadin.flow.component.react.ReactAdapterComponent;

import java.io.Serializable;
import java.util.List;

/**
 * React-Flow-Visualisierung des Wissensgraphen
 * (ReactAdapterComponent-Muster wie im dynamics-Projekt).
 */
@NpmPackage(value = "@xyflow/react", version = "12.6.0")
@NpmPackage(value = "@dagrejs/dagre", version = "1.1.4")
@JsModule("./components/knowledge-graph-adapter.tsx")
@Tag("knowledge-graph-adapter")
public class GraphFlowComponent extends ReactAdapterComponent implements HasSize {

    public record GraphNode(String id, String label, String type, long degree,
                            String color) implements Serializable {
    }

    public record GraphEdge(String id, String source, String target, String label,
                            int weight) implements Serializable {
    }

    @FunctionalInterface
    public interface NodeClickListener extends Serializable {
        void onNodeClick(long entityId);
    }

    public GraphFlowComponent() {
        setState("graphNodes", List.of());
        setState("graphEdges", List.of());
        getElement().getStyle().set("display", "block");
    }

    public void setGraph(List<GraphNode> nodes, List<GraphEdge> edges) {
        setState("graphNodes", nodes);
        setState("graphEdges", edges);
    }

    public void addNodeClickListener(NodeClickListener listener) {
        getElement().addEventListener("node-click", event -> {
            var detail = event.getEventData().get("event.detail");
            listener.onNodeClick(Long.parseLong(detail.get("nodeId").asText()));
        }).addEventData("event.detail");
    }
}
