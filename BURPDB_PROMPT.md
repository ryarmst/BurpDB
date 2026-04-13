Build a Burp Suite Montoya extension called `BurpDB` that gives Bambda scripts access to a shared SQLite database without any manual JAR or classpath setup.

## Runtime and packaging requirements

- Target Java 17+.
- Use Gradle.
- Depend on Burp Montoya API as `compileOnly`.
- Bundle `org.xerial:sqlite-jdbc` into the shaded runtime JAR. Do not mark it `compileOnly`.
- Do not shade Montoya classes.
- Use `duplicatesStrategy(DuplicatesStrategy.EXCLUDE)` for the shaded artifact.
- Produce a single shaded JAR as the deliverable Burp should load.
- Include `src/main/resources/META-INF/services/burp.api.montoya.BurpExtension` containing the fully qualified extension entrypoint class name.

## Core mechanic

- In `initialize(MontoyaApi api)`, call `api.extension().setName("BurpDB")`.
- Call `Class.forName("org.sqlite.JDBC")` during initialization so the SQLite JDBC driver is registered JVM-wide.
- Resolve the database path immediately after reading preferences, then set the system property `burp.db.url` to `jdbc:sqlite:<resolved-path>`.
- Bambdas anywhere in the Burp session must be able to connect with:

```java
DriverManager.getConnection(System.getProperty("burp.db.url"))
```

- There must be no manual driver JAR placement, custom classpath setup, or hard-coded absolute path in Bambdas.

## Database path and ownership

- Read the DB path from `api.persistence().preferences()` key `burpdb.path`.
- If the preference is unset or blank, default to `<user.home>/.burp/burpdb.db`.
- Normalize and resolve the path to an absolute path before publishing it.
- Create parent directories if they do not already exist.
- Store the exact JDBC URL published by this extension instance so unload cleanup can verify ownership.
- If `burp.db.url` is already set to a different value when this extension initializes, log that it is being replaced.
- On unload, clear `burp.db.url` only if the current property value still matches the value set by this extension instance.

## Connection lifecycle and SQLite behavior

- Do not keep a shared long-lived `Connection` field.
- Use connection-per-call everywhere:
  - schema initialization opens a connection, runs setup, and closes it
  - each UI query opens its own connection, executes, and closes it
  - Bambdas are expected to do the same
- Use `try-with-resources` for every `Connection`, `Statement`, and `ResultSet`.
- Immediately after opening a connection, configure SQLite for safer concurrent use:
  - set `PRAGMA busy_timeout = 5000`
  - enable `PRAGMA journal_mode = WAL` if supported

## Schema initialization

- On every startup, open a fresh connection and run these statements:

```sql
CREATE TABLE IF NOT EXISTS kv (
  key TEXT PRIMARY KEY,
  value TEXT,
  updated_at INTEGER
);

CREATE TABLE IF NOT EXISTS findings (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  host TEXT,
  issue TEXT,
  detail TEXT,
  severity TEXT,
  created_at INTEGER
);
```

- Also create useful starter indexes:

```sql
CREATE INDEX IF NOT EXISTS idx_findings_host ON findings(host);
CREATE INDEX IF NOT EXISTS idx_findings_created_at ON findings(created_at);
```

- Reserve room for future migrations by setting and checking `PRAGMA user_version`, even if the initial version is just `1`.

## Unload handler

- Register an unloading handler with `api.extension().registerUnloadingHandler(...)`.
- Since there is no shared connection, unload should not try to close a global `Connection`.
- Unload must clean up only JVM-wide side effects created by this extension:
  - remove `burp.db.url` only if this instance still owns it
  - log that cleanup occurred

## Burp tab

Register a single Swing `JPanel` suite tab via `api.userInterface().registerSuiteTab("BurpDB", panel)` and apply Burp theme helpers to the panel/components.

The tab must include:

- A read-only text field showing the current DB path.
- A `Change...` button that opens a `JFileChooser` parented to Burp's suite frame, lets the user pick a new DB path, saves it to preferences, resolves it, creates parent directories if needed, updates `burp.db.url`, and refreshes the displayed path.
- A text area for SQL input.
- `Run` and `Clear` buttons.
- A `JTable` backed by `AbstractTableModel`.
- A status label below the table.

## Query execution semantics

- Never run SQL on the Swing EDT.
- Execute schema initialization and UI queries on a background thread, then marshal UI updates back to the EDT.
- Each click of `Run` must execute exactly one SQL statement. Do not support multi-statement batches in the SQL text area.
- If the statement returns a `ResultSet`, derive table columns dynamically from `ResultSetMetaData` and render rows dynamically so arbitrary queries work.
- If the statement does not return a `ResultSet`, show the affected row count in the status label.
- On success, the status label should show either row count or affected row count.
- On failure, show the exception message in the status label and log the full error via `api.logging()`.
- To avoid freezing Burp on huge results, cap rendered output to a reasonable maximum such as 10,000 rows and report truncation in the status label.

## Logging

Use `api.logging()` to log:

- extension startup
- resolved database path
- system property replacement warnings
- schema initialization success/failure
- DB path changes from the UI
- query execution failures
- unload cleanup

Do not log SQL query results or other potentially sensitive database contents.

## Optional helper UX

- Add a small read-only helper area or panel containing example Bambda snippets for:
  - connecting
  - `SELECT`
  - `INSERT`
  - `UPSERT`
- Optionally add a context-menu action later that inserts selected request/response metadata into the database, but keep the initial implementation focused on the shared DB, schema, and SQL tab.

## Acceptance criteria

- Burp can load the extension by selecting only the shaded output JAR.
- No external SQLite JDBC JAR is required anywhere on disk beyond what is bundled in the extension.
- On startup, the extension resolves the DB path, creates parent directories if needed, publishes `burp.db.url`, and initializes the schema automatically.
- A Bambda in the same Burp session can successfully run `DriverManager.getConnection(System.getProperty("burp.db.url"))`.
- Changing the DB path in the Burp tab updates preferences, updates `burp.db.url`, and affects subsequent UI and Bambda connections.
- The SQL tab can execute both `SELECT` and non-`SELECT` statements safely.
- Large result sets do not freeze the Burp UI.
- Unloading the extension removes only the JVM-wide state owned by this extension instance.
