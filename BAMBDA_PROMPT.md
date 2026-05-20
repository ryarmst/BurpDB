
Rules:
- Open the database via the `Driver` instance BurpDB publishes in `System.getProperties()`. Do **not** use `DriverManager.getConnection()` from a Bambda — Java 9+ caller-sensitivity checks block drivers registered by the extension classloader.
- Use `try-with-resources` for every `Connection`, `Statement`, `PreparedStatement`, and `ResultSet`.
- Do not hard-code DB paths or require any external JDBC JAR.
- Assume these tables exist: `kv(key, value, updated_at)`, `findings(id, host, issue, detail, severity, created_at)`, and `logs(created_at, reporter, details)`.
- For troubleshooting, insert into `logs` with:
  - `created_at = strftime('%s','now')`
  - `reporter =` the Bambda/tool name
  - `details =` a short human-readable message
- Prefer `PreparedStatement` for inserts/updates.
- Keep logged details concise and avoid secrets unless explicitly requested.

Connection pattern:

```java
var driver = (java.sql.Driver) System.getProperties().get("burp.db.driver.instance");
if (driver == null) {
    // BurpDB extension is not loaded or hasn't finished initializing.
    return;
}
String dbUrl = System.getProperty("burp.db.url");
if (dbUrl == null || dbUrl.isBlank()) {
    return;
}
try (var conn = driver.connect(dbUrl, new java.util.Properties())) {
    // use conn — standard JDBC from here
}
```
