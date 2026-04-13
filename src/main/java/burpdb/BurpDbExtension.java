package burpdb;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;

import javax.swing.SwingUtilities;
import java.io.PrintWriter;
import java.io.StringWriter;

public final class BurpDbExtension implements BurpExtension
{
    private BurpDbService service;

    @Override
    public void initialize(MontoyaApi api)
    {
        api.extension().setName("BurpDB");

        try
        {
            Class.forName("org.sqlite.JDBC");

            this.service = new BurpDbService(api.persistence().preferences(), api.logging());
            BurpDbService.DatabaseLocation initialLocation = service.initializePath();

            BurpDbTab tab = new BurpDbTab(api, service, api.logging());
            tab.setPath(initialLocation.path());
            tab.setStatus("Initializing schema...");
            api.userInterface().registerSuiteTab("BurpDB", tab.uiComponent());

            service.initializeSchemaAsync(() -> SwingUtilities.invokeLater(
                    () -> {
                        tab.refreshTables();
                        tab.setStatus("Schema ready. Query results will appear below.");
                    }),
                    throwable -> SwingUtilities.invokeLater(() -> {
                        tab.setStatus(throwable.getMessage());
                        api.logging().logToError(stackTrace(throwable));
                        api.logging().raiseErrorEvent("BurpDB failed to initialize its schema.");
                    }));

            api.extension().registerUnloadingHandler(() -> {
                if (service != null)
                {
                    service.shutdown();
                }
                api.logging().logToOutput("BurpDB unloaded.");
            });

            api.logging().logToOutput("BurpDB loaded successfully.");
            api.logging().raiseInfoEvent("BurpDB is ready. Bambdas can use burp.db.url.");
        }
        catch (Throwable throwable)
        {
            api.logging().logToError(stackTrace(throwable));
            api.logging().raiseErrorEvent("BurpDB failed to load.");
            throw new IllegalStateException("Failed to initialize BurpDB.", throwable);
        }
    }

    private String stackTrace(Throwable throwable)
    {
        StringWriter stringWriter = new StringWriter();
        throwable.printStackTrace(new PrintWriter(stringWriter));
        return stringWriter.toString();
    }
}
