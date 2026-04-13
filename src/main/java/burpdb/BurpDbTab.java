package burpdb;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.logging.Logging;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JList;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;

final class BurpDbTab
{
    private final MontoyaApi api;
    private final BurpDbService service;
    private final Logging logging;
    private final JPanel panel;
    private final JTextField pathField;
    private final JTextArea sqlArea;
    private final JLabel statusLabel;
    private final QueryResultTableModel tableModel;
    private final JTable resultTable;
    private final DefaultListModel<String> tableListModel;
    private final JList<String> tableList;
    private final JButton changePathButton;
    private final JButton refreshTablesButton;
    private final JButton runButton;
    private final JButton clearButton;

    BurpDbTab(MontoyaApi api, BurpDbService service, Logging logging)
    {
        this.api = api;
        this.service = service;
        this.logging = logging;
        this.panel = new JPanel(new BorderLayout(8, 8));
        this.pathField = new JTextField();
        this.sqlArea = new JTextArea(10, 80);
        this.statusLabel = new JLabel("Ready.");
        this.tableModel = new QueryResultTableModel();
        this.resultTable = new JTable(tableModel);
        this.tableListModel = new DefaultListModel<>();
        this.tableList = new JList<>(tableListModel);
        this.changePathButton = new JButton("Change...");
        this.refreshTablesButton = new JButton("Refresh tables");
        this.runButton = new JButton("Run");
        this.clearButton = new JButton("Clear");

        buildUi();
    }

    Component uiComponent()
    {
        return panel;
    }

    void setPath(Path databasePath)
    {
        pathField.setText(databasePath == null ? "" : databasePath.toString());
    }

    void setStatus(String message)
    {
        statusLabel.setText(message);
    }

    void refreshTables()
    {
        service.listTablesAsync(tables -> SwingUtilities.invokeLater(() -> {
            tableListModel.clear();
            for (String table : tables)
            {
                tableListModel.addElement(table);
            }
        }), throwable -> SwingUtilities.invokeLater(() -> {
            tableListModel.clear();
            tableListModel.addElement("<failed to load tables>");
            logging.logToError(stackTrace(throwable));
        }));
    }

    private void buildUi()
    {
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        pathField.setEditable(false);
        pathField.setCaretPosition(0);

        sqlArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, sqlArea.getFont().getSize()));
        sqlArea.setLineWrap(false);
        sqlArea.setTabSize(2);

        resultTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        resultTable.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        tableList.setVisibleRowCount(8);

        changePathButton.addActionListener(event -> chooseDatabasePath());
        refreshTablesButton.addActionListener(event -> refreshTables());
        runButton.addActionListener(event -> runSql());
        clearButton.addActionListener(event -> clearSql());

        panel.add(buildPathPanel(), BorderLayout.NORTH);
        panel.add(buildMainSplitPane(), BorderLayout.CENTER);
        panel.add(buildHelperPanel(), BorderLayout.SOUTH);

        BurpDbService.DatabaseLocation currentLocation = service.currentLocation();
        if (currentLocation.path() != null)
        {
            setPath(currentLocation.path());
        }

        applyThemeRecursively(panel);
    }

    private Component buildPathPanel()
    {
        JPanel pathPanel = new JPanel(new BorderLayout(8, 8));
        pathPanel.add(new JLabel("Database path:"), BorderLayout.WEST);
        pathPanel.add(pathField, BorderLayout.CENTER);
        pathPanel.add(changePathButton, BorderLayout.EAST);
        return pathPanel;
    }

    private Component buildMainSplitPane()
    {
        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, buildSqlPanel(), buildDataPanel());
        splitPane.setResizeWeight(0.35);
        splitPane.setBorder(BorderFactory.createEmptyBorder());
        return splitPane;
    }

    private Component buildSqlPanel()
    {
        JPanel sqlPanel = new JPanel(new BorderLayout(8, 8));
        sqlPanel.setBorder(BorderFactory.createTitledBorder("SQL"));
        sqlPanel.add(new JScrollPane(sqlArea), BorderLayout.CENTER);

        JPanel buttonsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        buttonsPanel.add(runButton);
        buttonsPanel.add(clearButton);
        sqlPanel.add(buttonsPanel, BorderLayout.SOUTH);
        return sqlPanel;
    }

    private Component buildResultsPanel()
    {
        JPanel resultsPanel = new JPanel(new BorderLayout(8, 8));
        resultsPanel.setBorder(BorderFactory.createTitledBorder("Results"));
        resultsPanel.add(new JScrollPane(resultTable), BorderLayout.CENTER);
        resultsPanel.add(statusLabel, BorderLayout.SOUTH);
        return resultsPanel;
    }

    private Component buildDataPanel()
    {
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildTablesPanel(), buildResultsPanel());
        splitPane.setResizeWeight(0.2);
        splitPane.setBorder(BorderFactory.createEmptyBorder());
        return splitPane;
    }

    private Component buildTablesPanel()
    {
        JPanel tablesPanel = new JPanel(new BorderLayout(8, 8));
        tablesPanel.setBorder(BorderFactory.createTitledBorder("Tables"));
        tablesPanel.add(new JScrollPane(tableList), BorderLayout.CENTER);
        tablesPanel.add(refreshTablesButton, BorderLayout.SOUTH);
        return tablesPanel;
    }

    private Component buildHelperPanel()
    {
        JTextArea helperArea = new JTextArea();
        helperArea.setEditable(false);
        helperArea.setRows(8);
        helperArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, helperArea.getFont().getSize()));
        helperArea.setText("""
                Example Bambda snippets:

                // Connect
                var conn = java.sql.DriverManager.getConnection(System.getProperty("burp.db.url"));

                // SELECT
                try (var conn = java.sql.DriverManager.getConnection(System.getProperty("burp.db.url"));
                     var stmt = conn.createStatement();
                     var rs = stmt.executeQuery("SELECT host, issue FROM findings ORDER BY created_at DESC")) {
                    while (rs.next()) {
                        System.out.println(rs.getString("host") + " -> " + rs.getString("issue"));
                    }
                }

                // INSERT
                try (var conn = java.sql.DriverManager.getConnection(System.getProperty("burp.db.url"));
                     var ps = conn.prepareStatement("INSERT INTO kv(key, value, updated_at) VALUES (?, ?, strftime('%s','now'))")) {
                    ps.setString(1, "example");
                    ps.setString(2, "value");
                    ps.executeUpdate();
                }

                // UPSERT
                try (var conn = java.sql.DriverManager.getConnection(System.getProperty("burp.db.url"));
                     var ps = conn.prepareStatement(
                         "INSERT INTO kv(key, value, updated_at) VALUES (?, ?, strftime('%s','now')) " +
                         "ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at")) {
                    ps.setString(1, "example");
                    ps.setString(2, "value");
                    ps.executeUpdate();
                }

                // Log troubleshooting details
                try (var conn = java.sql.DriverManager.getConnection(System.getProperty("burp.db.url"));
                     var ps = conn.prepareStatement(
                         "INSERT INTO logs(created_at, reporter, details) VALUES (strftime('%s','now'), ?, ?)")) {
                    ps.setString(1, "ProxyErrorBambda");
                    ps.setString(2, "Observed upstream timeout while replaying request.");
                    ps.executeUpdate();
                }
                """);

        JPanel helperPanel = new JPanel(new BorderLayout(8, 8));
        helperPanel.setBorder(BorderFactory.createTitledBorder("Bambda examples"));
        helperPanel.add(new JScrollPane(helperArea), BorderLayout.CENTER);
        return helperPanel;
    }

    private void chooseDatabasePath()
    {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Select BurpDB SQLite file");
        fileChooser.setFileSelectionMode(JFileChooser.FILES_ONLY);

        BurpDbService.DatabaseLocation currentLocation = service.currentLocation();
        if (currentLocation.path() != null)
        {
            fileChooser.setSelectedFile(currentLocation.path().toFile());
        }

        int selection = fileChooser.showSaveDialog(api.userInterface().swingUtils().suiteFrame());
        if (selection != JFileChooser.APPROVE_OPTION)
        {
            return;
        }

        setBusy(true);
        setStatus("Updating database path...");
        Path selectedPath = fileChooser.getSelectedFile().toPath();
        service.changeDatabasePathAsync(selectedPath, location -> SwingUtilities.invokeLater(() -> {
            setBusy(false);
            setPath(location.path());
            refreshTables();
            setStatus("Database path updated and schema initialized.");
            logging.logToOutput("BurpDB database path changed from the UI to: " + location.path());
        }), throwable -> SwingUtilities.invokeLater(() -> {
            setBusy(false);
            setStatus(throwable.getMessage());
            logging.logToError(stackTrace(throwable));
        }));
    }

    private void runSql()
    {
        setBusy(true);
        setStatus("Running query...");
        service.executeSqlAsync(sqlArea.getText(), result -> SwingUtilities.invokeLater(() -> {
            setBusy(false);
            if (result.hasResultSet())
            {
                tableModel.setResult(result);
            }
            else
            {
                tableModel.clear();
            }
            refreshTables();
            setStatus(result.statusMessage());
        }), throwable -> SwingUtilities.invokeLater(() -> {
            setBusy(false);
            setStatus(throwable.getMessage());
            logging.logToError(stackTrace(throwable));
        }));
    }

    private void clearSql()
    {
        sqlArea.setText("");
        tableModel.clear();
        setStatus("Ready.");
    }

    private void setBusy(boolean busy)
    {
        runButton.setEnabled(!busy);
        changePathButton.setEnabled(!busy);
        refreshTablesButton.setEnabled(!busy);
        clearButton.setEnabled(!busy);
    }

    private void applyThemeRecursively(Component component)
    {
        api.userInterface().applyThemeToComponent(component);
        if (component instanceof java.awt.Container container)
        {
            for (Component child : container.getComponents())
            {
                applyThemeRecursively(child);
            }
        }
    }

    private String stackTrace(Throwable throwable)
    {
        StringWriter stringWriter = new StringWriter();
        throwable.printStackTrace(new PrintWriter(stringWriter));
        return stringWriter.toString();
    }
}
