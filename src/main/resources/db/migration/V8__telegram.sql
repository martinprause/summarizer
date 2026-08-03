-- Telegram-Bot: Verknuepfung Telegram-Chat <-> Summarizer-User
ALTER TABLE users ADD COLUMN telegram_chat_id BIGINT UNIQUE;
