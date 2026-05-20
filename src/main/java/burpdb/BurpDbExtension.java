package burpdb;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;

import javax.swing.SwingUtilities;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;

public final class BurpDbExtension implements BurpExtension
{
    private BurpDbService service;
    private Driver registeredShim;
    private Driver registeredRealDriver;

    @Override
    public void initialize(MontoyaApi api)
    {
        api.extension().setName("BurpDB");

        try
        {
            Class.forName("org.sqlite.JDBC");
            Driver realDriver = DriverManager.getDriver("jdbc:sqlite:");
            this.registeredRealDriver = realDriver;
            System.getProperties().put(BurpDbService.DB_DRIVER_INSTANCE_PROPERTY, realDriver);
            api.logging().logToOutput("BurpDB published driver instance as " + BurpDbService.DB_DRIVER_INSTANCE_PROPERTY);
            BurpDbDriverShim shim = new BurpDbDriverShim(realDriver);
            DriverManager.registerDriver(shim);
            this.registeredShim = shim;
            api.logging().logToOutput("BurpDB registered SQLite DriverShim with system DriverManager.");

            System.setProperty(BurpDbService.DB_DRIVER_PROPERTY, BurpDbService.SQLITE_DRIVER_CLASS);

            this.service = new BurpDbService(api.persistence().preferences(), api.logging());
            service.claimDriverProperty();
            service.claimDriverInstance();
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
                if (registeredShim != null)
                {
                    try
                    {
                        DriverManager.deregisterDriver(registeredShim);
                        api.logging().logToOutput("BurpDB deregistered SQLite DriverShim.");
                    }
                    catch (SQLException e)
                    {
                        api.logging().logToOutput("BurpDB could not deregister DriverShim: " + e.getMessage());
                    }
                }
                if (registeredRealDriver != null)
                {
                    try
                    {
                        DriverManager.deregisterDriver(registeredRealDriver);
                    }
                    catch (SQLException e)
                    {
                        api.logging().logToOutput("BurpDB could not deregister SQLite driver: " + e.getMessage());
                    }
                }
                System.getProperties().remove(BurpDbService.DB_DRIVER_INSTANCE_PROPERTY);
                api.logging().logToOutput("BurpDB removed driver instance from system properties.");
                if (service != null)
                {
                    service.shutdown();
                }
                api.logging().logToOutput("BurpDB unloaded.");
            });

            api.logging().logToOutput("BurpDB loaded successfully.");
            api.logging().raiseInfoEvent("BurpDB is ready. Bambdas can use burp.db.driver.instance.");
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
