import { ReactAdapterElement, type RenderHooks } from 'Frontend/generated/flow/ReactAdapter';
import {
    ReactFlow,
    Controls,
    Background,
    MiniMap,
    MarkerType,
    Position,
    applyNodeChanges,
    type Node,
    type Edge,
    type OnNodesChange,
} from '@xyflow/react';
// @ts-ignore — CSS-Import ohne Typdeklaration
import '@xyflow/react/dist/style.css';
import dagre from '@dagrejs/dagre';
import React, { type ReactElement, useCallback, useEffect, useState } from 'react';

type GNode = { id: string; label: string; type: string; degree: number; color: string; kind: string };

const ITEM_ICONS: Record<string, string> = {
    TEXT: '📝', WEBPAGE: '🌐', BOOKMARK: '🔖', FILE: '📄', AUDIO: '🎙', IMAGE: '🖼',
};
type GEdge = { id: string; source: string; target: string; label: string; weight: number };

const FALLBACK_COLOR = '#546e7a';

function nodeWidth(label: string): number {
    return Math.min(260, Math.max(90, label.length * 8 + 50));
}

/** Hierarchisches Layout mit dagre (links → rechts). */
function layoutPositions(nodes: GNode[], edges: GEdge[]): Map<string, { x: number; y: number }> {
    const g = new dagre.graphlib.Graph().setDefaultEdgeLabel(() => ({}));
    g.setGraph({ rankdir: 'LR', nodesep: 30, ranksep: 130 });
    for (const n of nodes) {
        g.setNode(n.id, { width: nodeWidth(n.label), height: 38 });
    }
    for (const e of edges) {
        if (g.hasNode(e.source) && g.hasNode(e.target)) {
            g.setEdge(e.source, e.target);
        }
    }
    dagre.layout(g);
    const positions = new Map<string, { x: number; y: number }>();
    for (const n of nodes) {
        const dn = g.node(n.id);
        if (dn) {
            positions.set(n.id, { x: dn.x - nodeWidth(n.label) / 2, y: dn.y - 19 });
        }
    }
    return positions;
}

class KnowledgeGraphElement extends ReactAdapterElement {
    protected override render(hooks: RenderHooks): ReactElement {
        const [gNodes] = hooks.useState<GNode[]>('graphNodes');
        const [gEdges] = hooks.useState<GEdge[]>('graphEdges');

        // Lokaler State + onNodesChange: React Flow meldet Knoten-Messungen als
        // NodeChanges zurück — ohne applyNodeChanges bleiben "measured"-Daten leer.
        const [nodes, setNodes] = useState<Node[]>([]);
        const [edges, setEdges] = useState<Edge[]>([]);
        const onNodesChange: OnNodesChange = useCallback(
            (changes) => setNodes((current) => applyNodeChanges(changes, current)),
            [],
        );

        useEffect(() => {
            const positions = layoutPositions(gNodes ?? [], gEdges ?? []);
            setNodes((gNodes ?? []).map((n) => {
                const isItem = n.kind === 'item';
                const label = isItem
                    ? `${ITEM_ICONS[n.type] ?? '📄'} ${n.label}`
                    : `${n.label}${n.degree > 0 ? ` (${n.degree})` : ''}`;
                const w = nodeWidth(label);
                const h = 38;
                return {
                    id: n.id,
                    position: positions.get(n.id) ?? { x: 0, y: 0 },
                    data: { label },
                    // SSR-Pfad: explizite Maße + Handle-Koordinaten, weil die DOM-Messung
                    // in der Web-Component-Einbettung nicht feuert — ohne sie keine Kanten.
                    width: w,
                    height: h,
                    handles: [
                        { type: 'source' as const, position: Position.Right, x: w, y: h / 2, width: 6, height: 6 },
                        { type: 'target' as const, position: Position.Left, x: 0, y: h / 2, width: 6, height: 6 },
                    ],
                    // Inhalte = helle Rechtecke, Begriffe = farbige Pillen
                    style: isItem ? {
                        background: 'white',
                        color: '#333',
                        border: '1px solid ' + (n.color || '#bbb'),
                        borderRadius: 6,
                        padding: '5px 10px',
                        fontSize: 11,
                    } : {
                        background: n.color || FALLBACK_COLOR,
                        color: 'white',
                        border: 'none',
                        borderRadius: 18,
                        padding: '6px 12px',
                        fontSize: 12 + Math.min(6, n.degree),
                    },
                };
            }));
            setEdges((gEdges ?? []).map((e) => ({
                id: e.id,
                source: e.source,
                target: e.target,
                label: e.label || undefined,
                labelStyle: { fontSize: 10 },
                markerEnd: { type: MarkerType.ArrowClosed, width: 14, height: 14 },
                // Kantendicke = Kookkurrenz: je mehr gemeinsame Inhalte, desto dicker
                style: {
                    stroke: (e.weight ?? 1) >= 3 ? '#546e7a' : '#90a4ae',
                    strokeWidth: Math.min(1 + (e.weight ?? 1) * 0.8, 5),
                },
            })));
        }, [gNodes, gEdges]);

        return (
            <div style={{ width: '100%', height: '100%' }}>
                <ReactFlow
                    nodes={nodes}
                    edges={edges}
                    onNodesChange={onNodesChange}
                    onNodeClick={(_, node) =>
                        this.dispatchEvent(new CustomEvent('node-click', { detail: { nodeId: node.id } }))
                    }
                    fitView
                    nodesConnectable={false}
                    deleteKeyCode={null}
                >
                    <Background />
                    <Controls showInteractive={false} />
                    <MiniMap pannable zoomable />
                </ReactFlow>
            </div>
        );
    }
}

customElements.define('knowledge-graph-adapter', KnowledgeGraphElement);
