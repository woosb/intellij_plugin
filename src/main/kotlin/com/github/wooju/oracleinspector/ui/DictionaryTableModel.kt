package com.github.wooju.oracleinspector.ui

import javax.swing.table.AbstractTableModel

/**
 * 컬럼명과 행 데이터를 받아 JTable에 바인딩하기 위한 범용 TableModel.
 */
class DictionaryTableModel(
    private val columns: List<String>,
    private val rows: List<List<Any?>>,
) : AbstractTableModel() {

    companion object {
        fun empty(columns: List<String>): DictionaryTableModel =
            DictionaryTableModel(columns, emptyList())
    }

    override fun getRowCount() = rows.size
    override fun getColumnCount() = columns.size
    override fun getColumnName(column: Int) = columns[column]
    override fun getValueAt(rowIndex: Int, columnIndex: Int) = rows[rowIndex][columnIndex]
    override fun isCellEditable(rowIndex: Int, columnIndex: Int) = false

    /** 첫 번째 non-null 값의 타입 반환 → 숫자 컬럼은 숫자로 정렬 */
    override fun getColumnClass(columnIndex: Int): Class<*> {
        for (row in rows) {
            val v = row[columnIndex]
            if (v != null) return v::class.javaObjectType
        }
        return String::class.java
    }
}
