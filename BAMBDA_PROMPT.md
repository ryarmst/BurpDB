
Rules:
- Open the database with `DriverManager.getConnection(System.getProperty("burp.db.url"))`.
- Use `try-with-resources` for every `Connection`, `Statement`, `PreparedStatement`, and `ResultSet`.
- Do not hard-code DB paths or require any external JDBC JAR.
- Assume these tables exist: `kv(key, value, updated_at)`, `findings(id, host, issue, detail, severity, created_at)`, and `logs(created_at, reporter, details)`.
- For troubleshooting, insert into `logs` with:
  - `created_at = strftime('%s','now')`
  - `reporter =` the Bambda/tool name
  - `details =` a short human-readable message
- Prefer `PreparedStatement` for inserts/updates.
- Keep logged details concise and avoid secrets unless explicitly requested.
