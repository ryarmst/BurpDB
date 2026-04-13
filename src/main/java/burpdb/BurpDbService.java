package burpdb;

import burp.api.montoya.logging.Logging;
import burp.api.montoya.persistence.Preferences;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

final class BurpDbService
{
    static final String DB_URL_PROPERTY = "burp.db.url";
    static final String DB_PATH_PREFERENCE = "burpdb.path";
    static final int MAX_RENDERED_ROWS = 10_000;
    private static final int TARGET_USER_VERSION = 1;

    private static final List<String> SCHEMA_STATEMENTS = List.of(
            """
            CREATE TABLE IF NOT EXISTS kv (
              key TEXT PRIMARY KEY,
              value TEXT,
              updated_at INTEGER
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS findings (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              host TEXT,
              issue TEXT,
              detail TEXT,
              severity TEXT,
              created_at INTEGER
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS logs (
              created_at INTEGER,
              reporter TEXT,
              details TEXT
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_findings_host ON findings(host)",
            "CREATE INDEX IF NOT EXISTS idx_findings_created_at ON findings(created_at)",
            "CREATE INDEX IF NOT EXISTS idx_logs_created_at ON logs(created_at)",
            "CREATE INDEX IF NOT EXISTS idx_logs_reporter ON logs(reporter)"
    );

    private final Preferences preferences;
    private final Logging logging;
    private final ExecutorService executorService;
    private final AtomicReference<Path> currentDatabasePath;
    private final AtomicReference<String> currentJdbcUrl;
    private final AtomicReference<String> ownedJdbcUrl;

    BurpDbService(Preferences preferences, Logging logging)
    {
        this.preferences = preferences;
        this.logging = logging;
        this.executorService = Executors.newSingleThreadExecutor(new BurpDbThreadFactory());
        this.currentDatabasePath = new AtomicReference<>();
        this.currentJdbcUrl = new AtomicReference<>();
        this.ownedJdbcUrl = new AtomicReference<>();
    }

    DatabaseLocation initializePath() throws IOException
    {
        String configuredPath = preferences.getString(DB_PATH_PREFERENCE);
        Path targetPath = configuredPath == null || configuredPath.isBlank()
                ? defaultDatabasePath()
                : expandPath(configuredPath);

        return publishDatabasePath(targetPath, false);
    }

    DatabaseLocation currentLocation()
    {
        return new DatabaseLocation(currentDatabasePath.get(), currentJdbcUrl.get());
    }

    void initializeSchemaAsync(Runnable onSuccess, Consumer<Throwable> onFailure)
    {
        executorService.submit(() -> {
            try
            {
                initializeSchema();
                if (onSuccess != null)
                {
                    onSuccess.run();
                }
            }
            catch (Throwable throwable)
            {
                if (onFailure != null)
                {
                    onFailure.accept(throwable);
                }
            }
        });
    }

    void changeDatabasePathAsync(Path selectedPath, Consumer<DatabaseLocation> onSuccess, Consumer<Throwable> onFailure)
    {
        executorService.submit(() -> {
            try
            {
                DatabaseLocation location = publishDatabasePath(selectedPath, true);
                initializeSchema();
                if (onSuccess != null)
                {
                    onSuccess.accept(location);
                }
            }
            catch (Throwable throwable)
            {
                if (onFailure != null)
                {
                    onFailure.accept(throwable);
                }
            }
        });
    }

    void executeSqlAsync(String sql, Consumer<BurpDbQueryResult> onSuccess, Consumer<Throwable> onFailure)
    {
        executorService.submit(() -> {
            try
            {
                BurpDbQueryResult result = executeSql(sql);
                if (onSuccess != null)
                {
                    onSuccess.accept(result);
                }
            }
            catch (Throwable throwable)
            {
                if (onFailure != null)
                {
                    onFailure.accept(throwable);
                }
            }
        });
    }

    void listTablesAsync(Consumer<List<String>> onSuccess, Consumer<Throwable> onFailure)
    {
        executorService.submit(() -> {
            try
            {
                List<String> tables = listTables();
                if (onSuccess != null)
                {
                    onSuccess.accept(tables);
                }
            }
            catch (Throwable throwable)
            {
                if (onFailure != null)
                {
                    onFailure.accept(throwable);
                }
            }
        });
    }

    void shutdown()
    {
        String propertyValue = ownedJdbcUrl.get();
        String currentValue = System.getProperty(DB_URL_PROPERTY);

        if (propertyValue != null && Objects.equals(propertyValue, currentValue))
        {
            System.clearProperty(DB_URL_PROPERTY);
            logging.logToOutput("BurpDB unload cleanup removed the burp.db.url property.");
        }
        else
        {
            logging.logToOutput("BurpDB unload cleanup left burp.db.url unchanged because another owner replaced it.");
        }

        executorService.shutdownNow();
    }

    private DatabaseLocation publishDatabasePath(Path configuredPath, boolean persistPreference) throws IOException
    {
        Path normalizedPath = configuredPath.toAbsolutePath().normalize();
        Path parent = normalizedPath.getParent();
        if (parent != null)
        {
            Files.createDirectories(parent);
        }

        if (persistPreference)
        {
            preferences.setString(DB_PATH_PREFERENCE, normalizedPath.toString());
        }

        String jdbcUrl = "jdbc:sqlite:" + normalizedPath;
        String previousValue = System.getProperty(DB_URL_PROPERTY);
        if (previousValue != null && !previousValue.equals(jdbcUrl))
        {
            logging.logToOutput("BurpDB is replacing an existing burp.db.url value.");
        }

        System.setProperty(DB_URL_PROPERTY, jdbcUrl);
        currentDatabasePath.set(normalizedPath);
        currentJdbcUrl.set(jdbcUrl);
        ownedJdbcUrl.set(jdbcUrl);

        logging.logToOutput("BurpDB resolved database path: " + normalizedPath);
        return new DatabaseLocation(normalizedPath, jdbcUrl);
    }

    private void initializeSchema() throws SQLException
    {
        try (Connection connection = openConnection(); Statement statement = connection.createStatement())
        {
            configureConnection(statement);
            ensureUserVersion(statement);

            for (String schemaStatement : SCHEMA_STATEMENTS)
            {
                statement.execute(schemaStatement);
            }
        }

        logging.logToOutput("BurpDB schema initialization completed successfully.");
    }

    private BurpDbQueryResult executeSql(String rawSql) throws SQLException
    {
        String sql = normalizeSingleStatement(rawSql);

        try (Connection connection = openConnection(); Statement statement = connection.createStatement())
        {
            configureConnection(statement);
            boolean hasResultSet = statement.execute(sql);
            if (hasResultSet)
            {
                try (ResultSet resultSet = statement.getResultSet())
                {
                    return readResultSet(resultSet);
                }
            }

            return BurpDbQueryResult.updateCountResult(statement.getUpdateCount());
        }
    }

    private List<String> listTables() throws SQLException
    {
        String sql = """
                SELECT name
                FROM sqlite_master
                WHERE type = 'table'
                  AND name NOT LIKE 'sqlite_%'
                ORDER BY name
                """;

        try (Connection connection = openConnection();
             Statement statement = connection.createStatement())
        {
            configureConnection(statement);
            try (ResultSet resultSet = statement.executeQuery(sql))
            {
                List<String> tables = new ArrayList<>();
                while (resultSet.next())
                {
                    tables.add(resultSet.getString(1));
                }
                return List.copyOf(tables);
            }
        }
    }

    private Connection openConnection() throws SQLException
    {
        String jdbcUrl = currentJdbcUrl.get();
        if (jdbcUrl == null || jdbcUrl.isBlank())
        {
            throw new SQLException("BurpDB has not published a JDBC URL yet.");
        }

        return DriverManager.getConnection(jdbcUrl);
    }

    private void configureConnection(Statement statement) throws SQLException
    {
        statement.execute("PRAGMA busy_timeout = 5000");
        try
        {
            statement.execute("PRAGMA journal_mode = WAL");
        }
        catch (SQLException walException)
        {
            logging.logToOutput("BurpDB could not enable WAL mode: " + walException.getMessage());
        }
    }

    private void ensureUserVersion(Statement statement) throws SQLException
    {
        int currentVersion = 0;
        try (ResultSet resultSet = statement.executeQuery("PRAGMA user_version"))
        {
            if (resultSet.next())
            {
                currentVersion = resultSet.getInt(1);
            }
        }

        if (currentVersion < TARGET_USER_VERSION)
        {
            statement.execute("PRAGMA user_version = " + TARGET_USER_VERSION);
        }
    }

    private BurpDbQueryResult readResultSet(ResultSet resultSet) throws SQLException
    {
        ResultSetMetaData metaData = resultSet.getMetaData();
        int columnCount = metaData.getColumnCount();
        List<String> columns = new ArrayList<>(columnCount);
        for (int columnIndex = 1; columnIndex <= columnCount; columnIndex++)
        {
            columns.add(metaData.getColumnLabel(columnIndex));
        }

        List<List<String>> rows = new ArrayList<>();
        boolean truncated = false;
        while (resultSet.next())
        {
            if (rows.size() >= MAX_RENDERED_ROWS)
            {
                truncated = true;
                break;
            }

            List<String> row = new ArrayList<>(columnCount);
            for (int columnIndex = 1; columnIndex <= columnCount; columnIndex++)
            {
                row.add(renderValue(resultSet.getObject(columnIndex)));
            }
            rows.add(row);
        }

        return BurpDbQueryResult.resultSet(columns, rows, truncated);
    }

    private String normalizeSingleStatement(String rawSql)
    {
        if (rawSql == null || rawSql.isBlank())
        {
            throw new IllegalArgumentException("Enter a SQL statement first.");
        }

        String trimmed = rawSql.trim();
        int trailingSemicolonIndex = trimmed.endsWith(";") ? trimmed.length() - 1 : -1;
        for (int index = 0; index < trimmed.length(); index++)
        {
            if (trimmed.charAt(index) == ';' && index != trailingSemicolonIndex)
            {
                throw new IllegalArgumentException("Only one SQL statement can be executed at a time.");
            }
        }

        return trailingSemicolonIndex >= 0
                ? trimmed.substring(0, trailingSemicolonIndex).trim()
                : trimmed;
    }

    private Path defaultDatabasePath()
    {
        return Paths.get(System.getProperty("user.home"), ".burp", "burpdb.db");
    }

    private Path expandPath(String rawPath)
    {
        if (rawPath.startsWith("~" + java.io.File.separator))
        {
            return Paths.get(System.getProperty("user.home"), rawPath.substring(2));
        }
        if (rawPath.equals("~"))
        {
            return Paths.get(System.getProperty("user.home"));
        }
        return Paths.get(rawPath);
    }

    private String renderValue(Object value)
    {
        if (value == null)
        {
            return "NULL";
        }
        if (value instanceof byte[] bytes)
        {
            return "<" + bytes.length + " bytes>";
        }
        return String.valueOf(value);
    }

    record DatabaseLocation(Path path, String jdbcUrl)
    {
    }

    private static final class BurpDbThreadFactory implements ThreadFactory
    {
        private final AtomicInteger threadId = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable runnable)
        {
            Thread thread = new Thread(runnable, "BurpDB-" + threadId.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
