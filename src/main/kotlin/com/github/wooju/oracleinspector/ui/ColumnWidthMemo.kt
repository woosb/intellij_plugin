package com.github.wooju.oracleinspector.ui

import com.intellij.ide.util.PropertiesComponent
import com.intellij.ui.table.JBTable
import javax.swing.event.ChangeEvent
import javax.swing.event.ListSelectionEvent
import javax.swing.event.TableColumnModelEvent
import javax.swing.event.TableColumnModelListener

/**
 * JBTable의 컬럼 너비를 PropertiesComponent에 영속화한다.
 *
 *  사용:
 *    - 테이블 모델을 set한 직후 apply(table, "logical-table-id") 호출.
 *    - 모델이 자주 교체되는 패널(예: Sessions 5초 자동 새로고침)에서는 매번 호출해도 됨 —
 *      새 columnModel에 listener 새로 등록되고 이전 columnModel은 GC 대상.
 *
 *  키 네임스페이스: OracleInspector.colWidth.<tableId>.<columnHeader>
 */
object ColumnWidthMemo {

    private const val PREFIX = "OracleInspector.colWidth"

    fun apply(table: JBTable, tableId: String) {
        val props = PropertiesComponent.getInstance()
        val cm = table.columnModel

        for (i in 0 until cm.columnCount) {
            val column = cm.getColumn(i)
            val header = column.headerValue?.toString() ?: continue
            val saved = props.getInt(key(tableId, header), -1)
            if (saved > 0) column.preferredWidth = saved
        }

        cm.addColumnModelListener(object : TableColumnModelListener {
            override fun columnMarginChanged(e: ChangeEvent?) = saveAll(table, tableId)
            override fun columnAdded(e: TableColumnModelEvent?) = saveAll(table, tableId)
            override fun columnMoved(e: TableColumnModelEvent?) {}
            override fun columnRemoved(e: TableColumnModelEvent?) {}
            override fun columnSelectionChanged(e: ListSelectionEvent?) {}
        })
    }

    private fun saveAll(table: JBTable, tableId: String) {
        val props = PropertiesComponent.getInstance()
        val cm = table.columnModel
        for (i in 0 until cm.columnCount) {
            val column = cm.getColumn(i)
            val header = column.headerValue?.toString() ?: continue
            // 0이거나 음수면 의미 없는 너비 — 저장 스킵
            val w = column.width
            if (w > 0) props.setValue(key(tableId, header), w, -1)
        }
    }

    private fun key(tableId: String, header: String) = "$PREFIX.$tableId.$header"
}
