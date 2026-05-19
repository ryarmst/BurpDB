package burpdb;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Delegates java.sql.Driver calls to a real driver instance loaded in a
 * different classloader (BurpDB's extension classloader). Registering this
 * shim with DriverManager makes the SQLite driver visible to any caller,
 * including Bambda scripts that run in Burp's own classloader.
 */
final class BurpDbDriverShim implements Driver
{
    private final Driver delegate;

    BurpDbDriverShim(Driver delegate)
    {
        this.delegate = delegate;
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException
    {
        return delegate.connect(url, info);
    }

    @Override
    public boolean acceptsURL(String url) throws SQLException
    {
        return delegate.acceptsURL(url);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException
    {
        return delegate.getPropertyInfo(url, info);
    }

    @Override
    public int getMajorVersion()
    {
        return delegate.getMajorVersion();
    }

    @Override
    public int getMinorVersion()
    {
        return delegate.getMinorVersion();
    }

    @Override
    public boolean jdbcCompliant()
    {
        return delegate.jdbcCompliant();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException
    {
        return delegate.getParentLogger();
    }
}
