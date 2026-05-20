package com.github.wooju.oracleinspector.ui

import javax.swing.table.AbstractTableModel

/**
 * 컬럼명과 행 데이터를 받아 JTable에 바인딩하기 위한 범용 TableModel.
 *
 * 모든 인덱스 접근(getValueAt/getColumnName/getColumnClass)은 boundary check를 거친다.
 * JTable이 model 교체 중간에 stale 인덱스로 paint 사이클을 돌리는 경우가 있어
 * (예: Sessions 탭의 Explain Plan sub-tab — 세션 선택 전 빈 모델 상태에서 paint),
 * 가드가 없으면 IndexOutOfBoundsException으로 IDE 로그에 SEVERE가 찍힌다.
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

    override fun getColumnName(column: Int): String =
        if (column in columns.indices) columns[column] else ""

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any? {
        if (rowIndex !in rows.indices) return null
        val row = rows[rowIndex]
        if (columnIndex !in row.indices) return null
        return row[columnIndex]
    }

    override fun isCellEditable(rowIndex: Int, columnIndex: Int) = false

    /** 첫 번째 non-null 값의 타입 반환 → 숫자 컬럼은 숫자로 정렬 */
    override fun getColumnClass(columnIndex: Int): Class<*> {
        for (row in rows) {
            if (columnIndex !in row.indices) continue
            val v = row[columnIndex]
            if (v != null) return v::class.javaObjectType
        }
        return String::class.java
    }
}
