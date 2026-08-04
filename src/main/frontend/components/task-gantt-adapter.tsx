import { ReactAdapterElement, type RenderHooks } from 'Frontend/generated/flow/ReactAdapter';
// @ts-ignore — kein Typpaket vorhanden
import Gantt from 'frappe-gantt';
// Exports-Feld des Pakets erlaubt keinen CSS-Subpath-Import — direkt aus node_modules laden
// @ts-ignore — CSS-Import ohne Typdeklaration
import '../../../../node_modules/frappe-gantt/dist/frappe-gantt.css';
import React, { type ReactElement, useEffect, useRef } from 'react';

type GTask = {
    id: string;
    name: string;
    start: string;      // YYYY-MM-DD
    end: string;        // YYYY-MM-DD
    progress: number;   // 0-100
    color: string;      // Balkenfarbe
};

class TaskGanttElement extends ReactAdapterElement {
    protected override render(hooks: RenderHooks): ReactElement {
        const [tasks] = hooks.useState<GTask[]>('ganttTasks');
        const [viewMode] = hooks.useState<string>('viewMode');
        const containerRef = useRef<HTMLDivElement>(null);
        const ganttRef = useRef<any>(null);

        useEffect(() => {
            const el = containerRef.current;
            if (!el) {
                return;
            }
            el.innerHTML = '';
            ganttRef.current = null;
            if (!tasks || tasks.length === 0) {
                return;
            }
            const dispatch = (name: string, detail: object) =>
                this.dispatchEvent(new CustomEvent(name, { detail }));
            const fmt = (d: Date) =>
                `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
            try {
                ganttRef.current = new Gantt(el, tasks.map((t) => ({ ...t })), {
                    view_mode: viewMode || 'Week',
                    language: document.documentElement.lang === 'en' ? 'en' : 'de',
                    // Drag/Resize aktiv; unendliches Seiten-Padding stoert die Maus-Gesten
                    readonly: false,
                    infinite_padding: false,
                    container_height: 420,
                    snap_at: '1d',
                    on_click: (task: any) => dispatch('task-click', { taskId: task.id }),
                    on_date_change: (task: any, start: Date, end: Date) =>
                        dispatch('task-move', { taskId: task.id, start: fmt(start), end: fmt(end) }),
                    on_progress_change: (task: any, progress: number) =>
                        dispatch('task-progress', { taskId: task.id, progress }),
                });
                // Balkenfarben pro Aufgabe setzen
                for (const t of tasks) {
                    if (!t.color) continue;
                    el.querySelectorAll(`.bar-wrapper[data-id="${t.id}"] .bar`).forEach(
                        (bar) => bar.setAttribute('style', `fill: ${t.color}`));
                }
            } catch (e) {
                // Render-Fehler nicht die ganze View reissen lassen
                // eslint-disable-next-line no-console
                console.error('Gantt render failed', e);
            }
        }, [tasks, viewMode]);

        return <div ref={containerRef}
                    style={{ width: '100%', minHeight: '440px', overflowX: 'auto', overflowY: 'visible' }} />;
    }
}

customElements.define('task-gantt-adapter', TaskGanttElement);
