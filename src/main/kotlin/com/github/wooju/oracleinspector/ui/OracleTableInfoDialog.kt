package com.github.wooju.oracleinspector.ui

import com.github.wooju.oracleinspector.service.OracleDictionaryService
import com.intellij.database.psi.DbTable
import com.intellij.icons.AllIcons
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.table.JBTable
import java.awt.*
import java.awt.datatransfer.StringSelection
import javax.swing.*
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableColumn
import javax.swing.table.TableRowSorter

class OracleTableInfoDialog(
    private val project: Project,
    private val table: DbTable,
    private val schemaName: String,
    private val tableName: String,
) : DialogWrapper(project) {

    private lateinit var tabs: JBTabbedPane

    init {
        title = "$schemaName.$tableName"
        isModal = false
        init()
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout(0, 0))
        root.preferredSize = Dimension(1060, 600)

        root.add(buildTopBar(), BorderLayout.NORTH)

        tabs = JBTabbedPane()
        buildTabs()
        root.add(tabs, BorderLayout.CENTER)

        return root
    }

    // ── 상단 바: 코멘트 + 새로고침 ────────────────────────────────────────────
    private fun buildTopBar(): JComponent {
        val comment = table.comment?.takeIf { it.isNotBlank() } ?: ""

        val commentLabel = JBLabel(comment).apply {
            font = font.deriveFont(Font.ITALIC, 12f)
            foreground = UIManager.getColor("Label.disabledForeground")
        }

        val refreshBtn = JButton(AllIcons.Actions.Refresh).apply {
            toolTipText = "새로고침"
            isBorderPainted = false
            isContentAreaFilled = false
            cursor = Cursor(Cursor.HAND_CURSOR)
            preferredSize = Dimension(28, 28)
            addActionListener { refresh() }
        }

        val bar = JPanel(BorderLayout(8, 0)).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UIManager.getColor("Separator.foreground")),
                BorderFactory.createEmptyBorder(5, 10, 5, 6)
            )
            add(commentLabel, BorderLayout.CENTER)
            add(refreshBtn,   BorderLayout.EAST)
        }
        return bar
    }

    // ── 탭 빌드 ───────────────────────────────────────────────────────────────
    private fun buildTabs() {
        fun addTab(name: String, model: DictionaryTableModel) =
            tabs.addTab("$name  (${model.rowCount})", createTablePanel(model))

        addTab("Columns",      OracleDictionaryService.buildColumnsModel(table))
        addTab("Keys",         OracleDictionaryService.buildKeysModel(table))
        addTab("Foreign Keys", OracleDictionaryService.buildForeignKeysModel(table))
        addTab("Indexes",      OracleDictionaryService.buildIndexesModel(table))
        addTab("Checks",       OracleDictionaryService.buildChecksModel(table))
        tabs.addTab("DDL",          createSqlPanel(OracleDictionaryService.buildDdl(table, schemaName, tableName)))
        tabs.addTab("SELECT",       createSqlPanel(OracleDictionaryService.buildSelectQuery(table, schemaName, tableName)))
        tabs.addTab("Comments SQL", createSqlPanel(OracleDictionaryService.commentsQuery(schemaName, tableName)))
        tabs.addTab("Triggers SQL", createSqlPanel(OracleDictionaryService.triggersQuery(schemaName, tableName)))
    }

    // ── 새로고침 ──────────────────────────────────────────────────────────────
    private fun refresh() {
        val selected = tabs.selectedIndex
        tabs.removeAll()
        buildTabs()
        if (selected < tabs.tabCount) tabs.selectedIndex = selected
    }

    // ── 테이블 패널 ───────────────────────────────────────────────────────────
    private fun createTablePanel(model: DictionaryTableModel): JComponent {
        val sorter = object : TableRowSorter<DictionaryTableModel>(model) {
            // 클릭 사이클: 미정렬 → 오름차순 → 내림차순 → 미정렬
            override fun toggleSortOrder(column: Int) {
                val keys = sortKeys
                if (keys.isNotEmpty() && keys[0].column == column) {
                    when (keys[0].sortOrder) {
                        SortOrder.ASCENDING  -> sortKeys = listOf(RowSorter.SortKey(column, SortOrder.DESCENDING))
                        SortOrder.DESCENDING -> sortKeys = emptyList()   // 정렬 해제
                        else                 -> super.toggleSortOrder(column)
                    }
                } else {
                    super.toggleSortOrder(column)
                }
            }
        }

        val jbTable = JBTable(model).apply {
            setShowGrid(false)
            intercellSpacing = Dimension(0, 0)
            rowHeight = 24
            rowSorter = sorter
            autoResizeMode = JTable.AUTO_RESIZE_OFF
            tableHeader.reorderingAllowed = true
            tableHeader.defaultRenderer = centerHeaderRenderer(tableHeader.defaultRenderer)
            setDefaultRenderer(Any::class.java, StripedCellRenderer(SwingConstants.LEFT))
        }
        autoFitColumns(jbTable, model)
        return JBScrollPane(jbTable)
    }

    private fun autoFitColumns(tbl: JBTable, model: DictionaryTableModel) {
        val fm  = tbl.getFontMetrics(tbl.font)
        val pad = 24

        // PK / Index 컬럼은 고정 너비 + 가운데 정렬
        val centerCols = setOf("PK", "Index")
        val centerRenderer = StripedCellRenderer(align = SwingConstants.CENTER)

        for (col in 0 until model.columnCount) {
            val column: TableColumn = tbl.columnModel.getColumn(col)
            val header = model.getColumnName(col)

            if (header in centerCols) {
                column.preferredWidth = 44
                column.cellRenderer = centerRenderer
                continue
            }

            var max = fm.stringWidth(header) + pad
            for (row in 0 until model.rowCount) {
                val w = fm.stringWidth(model.getValueAt(row, col)?.toString() ?: "") + pad
                if (w > max) max = w
            }
            column.preferredWidth = max.coerceIn(44, 380)
        }
    }

    private fun centerHeaderRenderer(delegate: javax.swing.table.TableCellRenderer) =
        javax.swing.table.TableCellRenderer { tbl, value, sel, foc, row, col ->
            delegate.getTableCellRendererComponent(tbl, value, sel, foc, row, col)
                .also { (it as? JLabel)?.horizontalAlignment = SwingConstants.CENTER }
        }

    private inner class StripedCellRenderer(
        private val align: Int = SwingConstants.LEFT,
    ) : DefaultTableCellRenderer() {
        private val evenBg = UIManager.getColor("Table.background")
        private val oddBg  = UIManager.getColor("Table.stripeColor")
            ?: evenBg?.let { Color(it.red, it.green, it.blue, 220) }

        override fun getTableCellRendererComponent(
            tbl: JTable, value: Any?,
            isSelected: Boolean, hasFocus: Boolean,
            row: Int, column: Int,
        ): Component {
            super.getTableCellRendererComponent(tbl, value, isSelected, hasFocus, row, column)
            if (!isSelected) background = if (row % 2 == 0) evenBg else oddBg
            horizontalAlignment = align
            border = BorderFactory.createEmptyBorder(0, 6, 0, 6)
            return this
        }
    }

    // ── SQL 텍스트 패널 (복사 버튼 포함) ─────────────────────────────────────
    private fun createSqlPanel(sql: String): JComponent {
        val textArea = JTextArea(sql).apply {
            isEditable = false
            font = Font(Font.MONOSPACED, Font.PLAIN, 13)
            border = BorderFactory.createEmptyBorder(10, 12, 10, 12)
        }

        val copyBtn = JButton(AllIcons.Actions.Copy).apply {
            toolTipText = "클립보드에 복사"
            isBorderPainted = false
            isContentAreaFilled = false
            cursor = Cursor(Cursor.HAND_CURSOR)
            preferredSize = Dimension(28, 28)
            addActionListener {
                CopyPasteManager.getInstance().setContents(StringSelection(sql))
                // 버튼 아이콘을 잠깐 체크로 바꿔서 복사됐음을 피드백
                icon = AllIcons.Actions.Checked
                Timer(1200) { icon = AllIcons.Actions.Copy }.also { it.isRepeats = false; it.start() }
            }
        }

        val toolbar = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UIManager.getColor("Separator.foreground")),
                BorderFactory.createEmptyBorder(2, 6, 2, 4)
            )
            add(JLabel("SQL").apply { font = font.deriveFont(Font.BOLD, 11f) }, BorderLayout.WEST)
            add(copyBtn, BorderLayout.EAST)
        }

        return JPanel(BorderLayout()).apply {
            add(toolbar,             BorderLayout.NORTH)
            add(JBScrollPane(textArea), BorderLayout.CENTER)
        }
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)
}
