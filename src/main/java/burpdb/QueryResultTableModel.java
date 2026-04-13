package burpdb;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;

final class QueryResultTableModel extends AbstractTableModel
{
    private List<String> columns = List.of();
    private List<List<String>> rows = List.of();

    void setResult(BurpDbQueryResult queryResult)
    {
        this.columns = List.copyOf(queryResult.columns());
        this.rows = copyRows(queryResult.rows());
        fireTableStructureChanged();
    }

    void clear()
    {
        this.columns = List.of();
        this.rows = List.of();
        fireTableStructureChanged();
    }

    @Override
    public int getRowCount()
    {
        return rows.size();
    }

    @Override
    public int getColumnCount()
    {
        return columns.size();
    }

    @Override
    public String getColumnName(int column)
    {
        return columns.get(column);
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex)
    {
        return rows.get(rowIndex).get(columnIndex);
    }

    private List<List<String>> copyRows(List<List<String>> sourceRows)
    {
        List<List<String>> copiedRows = new ArrayList<>(sourceRows.size());
        for (List<String> row : sourceRows)
        {
            copiedRows.add(List.copyOf(row));
        }
        return List.copyOf(copiedRows);
    }
}
