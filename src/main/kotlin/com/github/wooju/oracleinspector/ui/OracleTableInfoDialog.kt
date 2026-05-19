package com.github.wooju.oracleinspector.ui

import com.github.wooju.oracleinspector.OracleInspectorBundle
import com.github.wooju.oracleinspector.actions.OracleInspectorDataKeys
import com.github.wooju.oracleinspector.model.TableInfo
import com.github.wooju.oracleinspector.repository.DasTableRepository
import com.github.wooju.oracleinspector.repository.JdbcTableDataRepository
import com.github.wooju.oracleinspector.repository.JdbcTableMetadataRepository
import com.github.wooju.oracleinspector.service.OracleDictionaryService
import com.intellij.database.psi.DbTable
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import java.awt.*
import java.awt.datatransfer.StringSelection
import javax.swing.*
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableColumn
import javax.swing.table.TableRowSorter

private val LOG = logger<OracleTableInfoDialog>()

class OracleTableInfoDialog(
    private val project: Project,
    private val table: DbTable,
    private val schemaName: String,
    private val tableName: String,
) : DialogWrapper(project) {

    private lateinit var tabs: JBTabbedPane
    private lateinit var refreshBtn: JButton
    private lateinit var statusLabel: JBLabel
    private lateinit var commentLabel: JBLabel
    private var info: TableInfo = DasTableRepository(table, schemaName, tableName).loadTable()
    @Volatile private var loading: Boolean = false
    private var dataPanel: JComponent? = null  // Data 탭은 메타데이터에 의존하지 않으므로 한 번만 생성

    init {
        title = "$schemaName.$tableName"
        isModal = false
        init()
        // 캐시 데이터가 불완전하면 자동으로 JDBC 폴백
        if (info.isIncomplete()) {
            reloadFromJdbc(reason = OracleInspectorBundle.message("table.auto.refresh.reason.cache.incomplete"))
        }
    }

    override fun createCenterPanel(): JComponent {
        val root = object : JPanel(BorderLayout(0, 0)), DataProvider {
            override fun getData(dataId: String): Any? = when (dataId) {
                OracleInspectorDataKeys.CURRENT_OWNER.name -> schemaName
                OracleInspectorDataKeys.CURRENT_DATA_SOURCE.name -> table.dataSource
                else -> null
            }
        }
        root.preferredSize = Dimension(1060, 600)

        root.add(buildTopBar(), BorderLayout.NORTH)

        tabs = JBTabbedPane()
        buildTabs()
        root.add(tabs, BorderLayout.CENTER)

        return root
    }

    // ── 상단 바: 코멘트 + 상태 + 새로고침 ─────────────────────────────────────
    private fun buildTopBar(): JComponent {
        commentLabel = JBLabel(info.comment?.takeIf { it.isNotBlank() } ?: "").apply {
            font = font.deriveFont(Font.ITALIC, 12f)
            foreground = UIManager.getColor("Label.disabledForeground")
        }

        statusLabel = JBLabel("").apply {
            font = font.deriveFont(11f)
            foreground = UIManager.getColor("Label.disabledForeground")
            border = BorderFactory.createEmptyBorder(0, 0, 0, 8)
        }

        refreshBtn = JButton(AllIcons.Actions.Refresh).apply {
            toolTipText = OracleInspectorBundle.message("dialog.tooltip.refresh.from.db")
            isBorderPainted = false
            isContentAreaFilled = false
            cursor = Cursor(Cursor.HAND_CURSOR)
            preferredSize = Dimension(28, 28)
            addActionListener { reloadFromJdbc(reason = OracleInspectorBundle.message("table.refresh.reason.manual")) }
        }

        val right = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(statusLabel, BorderLayout.CENTER)
            add(refreshBtn, BorderLayout.EAST)
        }

        return JPanel(BorderLayout(8, 0)).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UIManager.getColor("Separator.foreground")),
                BorderFactory.createEmptyBorder(5, 10, 5, 6)
            )
            add(commentLabel, BorderLayout.CENTER)
            add(right, BorderLayout.EAST)
        }
    }

    // ── 탭 빌드 ───────────────────────────────────────────────────────────────
    private fun buildTabs() {
        fun addTab(name: String, model: DictionaryTableModel) =
            tabs.addTab("$name  (${model.rowCount})", createTablePanel(model))

        addTab("Columns",      OracleDictionaryService.buildColumnsModel(info))
        addTab("Keys",         OracleDictionaryService.buildKeysModel(info))
        addTab("Foreign Keys", OracleDictionaryService.buildForeignKeysModel(info))
        addTab("Indexes",      OracleDictionaryService.buildIndexesModel(info))
        addTab("Checks",       OracleDictionaryService.buildChecksModel(info))
        addTab("Triggers",     OracleDictionaryService.buildTriggersModel(info))
        tabs.addTab("Data",         dataPanel ?: createDataPanel().also { dataPanel = it })
        tabs.addTab("DDL",          createSqlPanel(OracleDictionaryService.buildDdl(info)))
        tabs.addTab("SELECT",       createSqlPanel(OracleDictionaryService.buildSelectQuery(info)))
        tabs.addTab("Comments SQL", createSqlPanel(OracleDictionaryService.commentsQuery(schemaName, tableName)))
    }

    // ── JDBC 재로딩 (백그라운드) ──────────────────────────────────────────────
    private fun reloadFromJdbc(reason: String) {
        if (loading) return
        val ds = table.dataSource
        loading = true
        setLoadingUi(true, message = reason)

        val taskTitle = OracleInspectorBundle.message("table.task.title.fetch.metadata", schemaName, tableName)
        object : Task.Backgroundable(project, taskTitle, true) {
            private var fetched: TableInfo? = null
            private var failure: Throwable? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                try {
                    fetched = JdbcTableMetadataRepository(project, ds, schemaName, tableName).loadTable()
                } catch (t: Throwable) {
                    LOG.warn("JDBC 메타데이터 조회 실패", t)
                    failure = t
                }
            }

            override fun onFinished() {
                ApplicationManager.getApplication().invokeLater {
                    loading = false
                    setLoadingUi(false, message = "")
                    val ok = fetched
                    val err = failure
                    when {
                        ok != null -> applyNewInfo(ok)
                        err != null -> notifyError(OracleInspectorBundle.message("common.query.failed", err.message ?: err::class.simpleName.orEmpty()))
                        else -> notifyError(OracleInspectorBundle.message("common.query.cancelled"))
                    }
                }
            }
        }.queue()
    }

    private fun applyNewInfo(newInfo: TableInfo) {
        val selected = tabs.selectedIndex
        info = newInfo
        commentLabel.text = info.comment?.takeIf { it.isNotBlank() } ?: ""
        tabs.removeAll()
        buildTabs()
        if (selected in 0 until tabs.tabCount) tabs.selectedIndex = selected
    }

    private fun setLoadingUi(busy: Boolean, message: String) {
        refreshBtn.isEnabled = !busy
        refreshBtn.icon = if (busy) AllIcons.Process.Step_1 else AllIcons.Actions.Refresh
        statusLabel.text = message
    }

    private fun notifyError(text: String) {
        val group = NotificationGroupManager.getInstance().getNotificationGroup("Oracle Dictionary Inspector")
        if (group != null) {
            group.createNotification(text, NotificationType.WARNING).notify(project)
        } else {
            statusLabel.text = text
            statusLabel.foreground = UIManager.getColor("Label.errorForeground") ?: statusLabel.foreground
        }
    }

    // ── 테이블 패널 ───────────────────────────────────────────────────────────
    private fun tabIdFor(model: DictionaryTableModel): String {
        // 첫 컬럼명 기반으로 안정적 ID 생성 (탭별로 컬럼 구성이 다르기 때문)
        val firstCol = model.getColumnName(0).lowercase().replace(' ', '_')
        return "table.$firstCol"
    }

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
        ColumnWidthMemo.apply(jbTable, tabIdFor(model))
        TableSearchSupport.install(jbTable)
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

    // ── Data 탭 (페이징 조회) ────────────────────────────────────────────────
    private fun createDataPanel(): JComponent {
        val pageSize = 500
        val state = DataTabState(pageIndex = 0)

        val tableModel = DictionaryTableModel.empty(listOf(OracleInspectorBundle.message("data.placeholder")))
        val dataTable = JBTable(tableModel).apply {
            setShowGrid(false)
            intercellSpacing = Dimension(0, 0)
            rowHeight = 24
            autoResizeMode = JTable.AUTO_RESIZE_OFF
            tableHeader.reorderingAllowed = true
            tableHeader.defaultRenderer = centerHeaderRenderer(tableHeader.defaultRenderer)
            setDefaultRenderer(Any::class.java, StripedCellRenderer(SwingConstants.LEFT))
        }
        TableSearchSupport.install(dataTable)
        val scroll = JBScrollPane(dataTable)

        val pageLabel = JBLabel(OracleInspectorBundle.message("data.label.page.none")).apply {
            font = font.deriveFont(11f)
            border = BorderFactory.createEmptyBorder(0, 8, 0, 8)
        }
        val rowCountLabel = JBLabel("").apply {
            font = font.deriveFont(11f)
            foreground = UIManager.getColor("Label.disabledForeground")
        }
        val prevBtn = JButton(AllIcons.Actions.Back).apply {
            toolTipText = OracleInspectorBundle.message("data.tooltip.prev", pageSize)
            isBorderPainted = false
            isContentAreaFilled = false
            cursor = Cursor(Cursor.HAND_CURSOR)
            preferredSize = Dimension(28, 28)
            isEnabled = false
        }
        val nextBtn = JButton(AllIcons.Actions.Forward).apply {
            toolTipText = OracleInspectorBundle.message("data.tooltip.next", pageSize)
            isBorderPainted = false
            isContentAreaFilled = false
            cursor = Cursor(Cursor.HAND_CURSOR)
            preferredSize = Dimension(28, 28)
            isEnabled = false
        }
        val reloadBtn = JButton(AllIcons.Actions.Refresh).apply {
            toolTipText = OracleInspectorBundle.message("data.tooltip.reload")
            isBorderPainted = false
            isContentAreaFilled = false
            cursor = Cursor(Cursor.HAND_CURSOR)
            preferredSize = Dimension(28, 28)
        }

        val whereField = JBTextField(28).apply {
            toolTipText = OracleInspectorBundle.message("data.tooltip.where")
        }
        val orderByField = JBTextField(20).apply {
            toolTipText = OracleInspectorBundle.message("data.tooltip.orderby")
        }

        fun render(page: JdbcTableDataRepository.DataPage) {
            val cols = page.columns
            val rows = page.rows
            val newModel = DictionaryTableModel(cols, rows)
            dataTable.model = newModel
            dataTable.rowSorter = null  // 페이지가 바뀌면 정렬 초기화
            autoFitColumns(dataTable, newModel)

            val from = page.pageIndex.toLong() * page.pageSize + (if (rows.isEmpty()) 0 else 1)
            val to = page.pageIndex.toLong() * page.pageSize + rows.size
            pageLabel.text = OracleInspectorBundle.message("data.label.page.current", page.pageIndex + 1)
            rowCountLabel.text =
                if (rows.isEmpty()) OracleInspectorBundle.message("data.label.no.result")
                else OracleInspectorBundle.message("data.label.rows.range", from, to) +
                    (if (page.hasMore) OracleInspectorBundle.message("data.label.has.more") else "")

            prevBtn.isEnabled = page.pageIndex > 0
            nextBtn.isEnabled = page.hasMore
        }

        fun load(pageIndex: Int) {
            if (state.loading) return
            state.loading = true
            prevBtn.isEnabled = false
            nextBtn.isEnabled = false
            reloadBtn.isEnabled = false
            pageLabel.text = OracleInspectorBundle.message("common.loading")
            rowCountLabel.text = ""

            val ds = table.dataSource
            val title = OracleInspectorBundle.message("data.task.title", schemaName, tableName, pageIndex + 1)
            object : Task.Backgroundable(project, title, true) {
                private var fetched: JdbcTableDataRepository.DataPage? = null
                private var failure: Throwable? = null

                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    try {
                        fetched = JdbcTableDataRepository(project, ds, schemaName, tableName)
                            .loadPage(
                                pageIndex = pageIndex,
                                pageSize = pageSize,
                                where = whereField.text,
                                orderBy = orderByField.text,
                            )
                    } catch (t: Throwable) {
                        LOG.warn("테이블 데이터 조회 실패", t)
                        failure = t
                    }
                }

                override fun onFinished() {
                    ApplicationManager.getApplication().invokeLater {
                        state.loading = false
                        reloadBtn.isEnabled = true
                        val ok = fetched
                        val err = failure
                        when {
                            ok != null -> {
                                state.pageIndex = ok.pageIndex
                                render(ok)
                            }
                            err != null -> {
                                pageLabel.text = OracleInspectorBundle.message("data.label.page.current", state.pageIndex + 1)
                                rowCountLabel.text = OracleInspectorBundle.message("common.error")
                                prevBtn.isEnabled = state.pageIndex > 0
                                nextBtn.isEnabled = false
                                notifyError(OracleInspectorBundle.message("data.error.fetch", err.message ?: err::class.simpleName.orEmpty()))
                            }
                            else -> {
                                pageLabel.text = OracleInspectorBundle.message("data.label.page.current", state.pageIndex + 1)
                                rowCountLabel.text = OracleInspectorBundle.message("common.cancelled")
                                prevBtn.isEnabled = state.pageIndex > 0
                            }
                        }
                    }
                }
            }.queue()
        }

        prevBtn.addActionListener { if (state.pageIndex > 0) load(state.pageIndex - 1) }
        nextBtn.addActionListener { load(state.pageIndex + 1) }
        reloadBtn.addActionListener { load(state.pageIndex) }

        // 엔터 키로 필터 적용 — 필터가 바뀌면 항상 첫 페이지부터
        val applyFilter = java.awt.event.ActionListener { load(0) }
        whereField.addActionListener(applyFilter)
        orderByField.addActionListener(applyFilter)

        val pageRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            val left = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)).apply {
                isOpaque = false
                add(prevBtn)
                add(nextBtn)
                add(pageLabel)
                add(rowCountLabel)
            }
            val right = JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0)).apply {
                isOpaque = false
                add(reloadBtn)
            }
            add(left, BorderLayout.WEST)
            add(right, BorderLayout.EAST)
        }

        val filterRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(2, 4, 2, 0)
            add(JLabel("WHERE").apply { font = font.deriveFont(Font.BOLD, 11f) })
            add(whereField)
            add(JLabel("ORDER BY").apply { font = font.deriveFont(Font.BOLD, 11f) })
            add(orderByField)
        }

        val toolbar = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UIManager.getColor("Separator.foreground")),
                BorderFactory.createEmptyBorder(2, 6, 2, 4)
            )
            add(pageRow)
            add(filterRow)
        }

        val panel = JPanel(BorderLayout()).apply {
            add(toolbar, BorderLayout.NORTH)
            add(scroll, BorderLayout.CENTER)
        }

        // 탭이 처음 열릴 때 한 번만 자동 로드
        var loadedOnce = false
        tabs.addChangeListener {
            val title = if (tabs.selectedIndex >= 0) tabs.getTitleAt(tabs.selectedIndex) else ""
            if (!loadedOnce && title == "Data") {
                loadedOnce = true
                load(0)
            }
        }

        return panel
    }

    private class DataTabState(
        var pageIndex: Int = 0,
        @Volatile var loading: Boolean = false,
    )

    // ── SQL 텍스트 패널 (복사 버튼 포함) ─────────────────────────────────────
    private fun createSqlPanel(sql: String): JComponent {
        val textArea = JTextArea(sql).apply {
            isEditable = false
            font = Font(Font.MONOSPACED, Font.PLAIN, 13)
            border = BorderFactory.createEmptyBorder(10, 12, 10, 12)
        }

        val copyBtn = JButton(AllIcons.Actions.Copy).apply {
            toolTipText = OracleInspectorBundle.message("common.copy.to.clipboard")
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
