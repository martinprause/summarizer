-- Aufgaben mit Faelligkeit + Verknuepfung zu Inhalten (Gantt-Ansicht)

CREATE TABLE tasks (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    title      VARCHAR(300) NOT NULL,
    notes      TEXT,
    status     VARCHAR(10)  NOT NULL DEFAULT 'TODO',   -- TODO | DOING | DONE
    start_date DATE,
    due_date   DATE,
    progress   INT          NOT NULL DEFAULT 0,        -- 0-100
    color      VARCHAR(20),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_tasks_user_due ON tasks (user_id, due_date);

CREATE TABLE item_tasks (
    item_id BIGINT NOT NULL REFERENCES items (id) ON DELETE CASCADE,
    task_id BIGINT NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    PRIMARY KEY (item_id, task_id)
);

CREATE INDEX idx_item_tasks_task ON item_tasks (task_id);
