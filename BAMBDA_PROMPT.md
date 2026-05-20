# BurpDB — Bambda integration

BurpDB is a Burp Suite extension that provisions a shared SQLite database for cross-invocation persistence in Bambdas (scan checks, custom columns, filters, Repeater actions). It shades `sqlite-jdbc` into its JAR, creates the schema on load, and exposes connection details via JVM system properties. No external JDBC JAR or classpath setup is required.

## How it works

On load, BurpDB:

1. Loads the shaded SQLite driver in the extension classloader.
2. Publishes the live `java.sql.Driver` object in `System.getProperties()` under `burp.db.driver.instance`.
3. Sets `burp.db.url` to `jdbc:sqlite:<path>` (default file: `~/.burp/burpdb.db`, changeable in the BurpDB suite tab).
4. Creates `kv`, `findings`, and `logs` if missing (WAL mode, 5s busy timeout).

Bambdas run in Burp's classloader, not the extension's. `DriverManager.getConnection()` fails from Bambdas because Java 9+ caller-sensitivity checks reject drivers registered by sibling classloaders. Bambdas must call `driver.connect()` on the instance published in `System.getProperties()` — a `Hashtable<Object,Object>` singleton visible to every classloader.

Do not call `Class.forName("org.sqlite.JDBC")` or add `sqlite-jdbc` to Burp's library JAR folder. The driver class is not visible outside the extension JAR.

## System properties

| Property | Type | Meaning |
|---|---|---|
| `burp.db.driver.instance` | `java.sql.Driver` | Live driver object — **use this to connect** |
| `burp.db.url` | `String` | JDBC URL, e.g. `jdbc:sqlite:/home/user/.burp/burpdb.db` |
| `burp.db.driver` | `String` | Driver class name (`org.sqlite.JDBC`) — presence confirms extension loaded |

All three are JVM-global. If BurpDB is unloaded, they are cleared.

## Connecting from a Bambda

```java
var driver = (java.sql.Driver) System.getProperties().get("burp.db.driver.instance");
if (driver == null) return;  // extension not loaded

var dbUrl = System.getProperty("burp.db.url");
if (dbUrl == null || dbUrl.isBlank()) return;

try (var conn = driver.connect(dbUrl, new java.util.Properties())) {
    // standard JDBC from here
}
```

Always use `try-with-resources` for `Connection`, `Statement`, `PreparedStatement`, and `ResultSet`.

Health check (custom column):

```java
var driver = (java.sql.Driver) System.getProperties().get("burp.db.driver.instance");
if (driver == null) return "no driver";
try (var conn = driver.connect(System.getProperty("burp.db.url"), new java.util.Properties());
     var stmt = conn.createStatement();
     var rs = stmt.executeQuery("SELECT 1")) {
    return rs.next() ? "ok" : "no rows";
} catch (Exception e) {
    return e.getMessage();
}
```

## Schema

Pre-provisioned tables — do not `CREATE TABLE`:

- `kv(key TEXT PRIMARY KEY, value TEXT, updated_at INTEGER)` — general key-value state; namespace keys (`my-check.seen-host:example.com`)
- `findings(id INTEGER PRIMARY KEY AUTOINCREMENT, host TEXT, issue TEXT, detail TEXT, severity TEXT, created_at INTEGER)` — structured finding records
- `logs(created_at INTEGER, reporter TEXT, details TEXT)` — shared troubleshooting log

```java
// kv upsert
try (var conn = driver.connect(dbUrl, new java.util.Properties());
     var ps = conn.prepareStatement(
         "INSERT INTO kv(key, value, updated_at) VALUES(?, ?, strftime('%s','now')) " +
         "ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at")) {
    ps.setString(1, "my-bambda.counter");
    ps.setString(2, "42");
    ps.executeUpdate();
}

// log an unexpected condition
try (var conn = driver.connect(dbUrl, new java.util.Properties());
     var ps = conn.prepareStatement(
         "INSERT INTO logs(created_at, reporter, details) VALUES(strftime('%s','now'), ?, ?)")) {
    ps.setString(1, "my-bambda");
    ps.setString(2, "driver instance missing; skipping check");
    ps.executeUpdate();
}
```

Prefer `PreparedStatement` for writes. Keep `logs.details` short; no secrets.

## Concurrency

Burp runs scan checks in parallel. SQLite serializes writes — use `INSERT ... ON CONFLICT DO NOTHING/DO UPDATE` instead of check-then-insert. Do not hold write transactions open across slow I/O (HTTP probes). For multi-statement atomicity: `conn.setAutoCommit(false)` + `conn.commit()`.

## Rules

- Connect via `burp.db.driver.instance` + `burp.db.url`; never `DriverManager.getConnection()` from a Bambda.
- Never hard-code DB paths.
- Namespace `kv` keys by Bambda/tool name.
- Log unexpected failures to `logs` with a static `reporter` string.
- Cache reads in script-local variables within a single invocation; avoid a DB round-trip on every cheap early-return path.
