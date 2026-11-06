CREATE TABLE user_appearance (
    user_id TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    scheme TEXT NOT NULL CHECK (scheme IN ('light','dark')),
    palette TEXT NOT NULL CHECK (palette IN ('teal','blue','violet','rose','orange','cyan','indigo','grape','lime','yellow'))
);
