# BurpDB

`BurpDB` is a Burp Suite Montoya extension that exposes a shared SQLite database to Bambdas without manual JDBC JAR or classpath setup.

## Purpose and architecture

- The extension shades `sqlite-jdbc` into the final JAR, registers `org.sqlite.JDBC` on load, and publishes `burp.db.url` as a JVM-wide system property.
- Bambdas and the UI both use connection-per-call against that same SQLite file.
- The extension creates a `BurpDB` suite tab for SQL execution, result viewing, DB path changes, and table listing.

## Limitations and issues

- `burp.db.url` is JVM-global, so multiple extension instances can overwrite each other.
- SQLite still has write-lock constraints under heavy concurrent use, even with `busy_timeout` and WAL.
- The SQL UI only runs one statement at a time.
- Result rendering is capped to avoid freezing Burp.

## Bambda integration

Use the shared JDBC URL directly:

```java
try (var conn = java.sql.DriverManager.getConnection(System.getProperty("burp.db.url"));
     var stmt = conn.createStatement();
     var rs = stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")) {
    while (rs.next()) {
        System.out.println(rs.getString(1));
    }
}
```

For troubleshooting, Bambdas should write simple events into `logs` with:

- `created_at`: Unix timestamp in seconds
- `reporter`: the Bambda or tool name, for example `ProxyErrorBambda` or `RepeaterAudit`
- `details`: a short human-readable description of the problem

Example:

```java
try (var conn = java.sql.DriverManager.getConnection(System.getProperty("burp.db.url"));
     var ps = conn.prepareStatement(
         "INSERT INTO logs(created_at, reporter, details) VALUES (strftime('%s','now'), ?, ?)")) {
    ps.setString(1, "ProxyErrorBambda");
    ps.setString(2, "Observed upstream timeout while replaying request.");
    ps.executeUpdate();
}
```

Keep `details` concise and avoid storing secrets or full raw payloads unless you explicitly want them in the shared database.

## Default tables

- `kv(key TEXT PRIMARY KEY, value TEXT, updated_at INTEGER)`
- `findings(id INTEGER PRIMARY KEY AUTOINCREMENT, host TEXT, issue TEXT, detail TEXT, severity TEXT, created_at INTEGER)`
- `logs(created_at INTEGER, reporter TEXT, details TEXT)`
