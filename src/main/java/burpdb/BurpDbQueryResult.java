package burpdb;

import java.util.List;

record BurpDbQueryResult(boolean hasResultSet, List<String> columns, List<List<String>> rows, int affectedRowCount,
                         boolean truncated)
{
    static BurpDbQueryResult resultSet(List<String> columns, List<List<String>> rows, boolean truncated)
    {
        return new BurpDbQueryResult(true, columns, rows, -1, truncated);
    }

    static BurpDbQueryResult updateCountResult(int affectedRowCount)
    {
        return new BurpDbQueryResult(false, List.of(), List.of(), affectedRowCount, false);
    }

    String statusMessage()
    {
        if (!hasResultSet)
        {
            return "Statement executed successfully. Affected rows: " + affectedRowCount + ".";
        }

        String message = "Query returned " + rows.size() + " row(s).";
        if (truncated)
        {
            message += " Display capped at " + BurpDbService.MAX_RENDERED_ROWS + " row(s).";
        }
        return message;
    }
}
