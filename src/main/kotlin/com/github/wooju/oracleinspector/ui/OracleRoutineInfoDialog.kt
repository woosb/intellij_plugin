package com.github.wooju.oracleinspector.ui

import com.github.wooju.oracleinspector.model.RoutineInfo
import com.github.wooju.oracleinspector.repository.DasRoutineRepository
import com.github.wooju.oracleinspector.repository.JdbcRoutineRepository
import com.github.wooju.oracleinspector.service.OracleDictionaryService
import com.intellij.database.psi.DbRoutine
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
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
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.datatransfer.StringSelection
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.SwingConstants
import javax.swing.Timer
import javax.swing.UIManager
import javax.swing.table.DefaultTableCellRenderer

private val LOG = logger<OracleRoutineInfoDialog>()

class OracleRoutineInfoDialog(
    private val project: Project,
    private val routine: DbRoutine,
    private val schemaName: String,
    private val routineName: String,
) : DialogWrapper(project) {

    private lateinit var tabs: JBTabbedPane
    private lateinit var refreshBtn: JButton
    private lateinit var statusLabel: JBLabel
    private lateinit var kindLabel: JBLabel
    private var info: RoutineInfo = DasRoutineRepository(routine, schemaName, routineName).loadRoutine()
    @Volatile private var loading: Boolean = false

    init {
        title = "$schemaName.$routineName"
        isModal = false
        init()
        // 소스가 비어있으면 자동으로 JDBC 폴백
        if (info.isIncomplete()) {
            reloadFromJdbc(reason = "캐시에 소스 없음 — 자동 새로고침")
        }
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout())
        root.preferredSize = Dimension(960, 600)
        root.add(buildTopBar(), BorderLayout.NORTH)
        tabs = JBTabbedPane()
        buildTabs()
        root.add(tabs, BorderLayout.CENTER)
        return root
    }

    // ── 상단 바 ───────────────────────────────────────────────────────────────
    private fun buildTopBar(): JComponent {
        kindLabel = JBLabel(kindText()).apply {
            font = font.deriveFont(Font.ITALIC, 12f)
            foreground = UIManager.getColor("Label.disabledForeground")
        }
        statusLabel = JBLabel("").apply {
            font = font.deriveFont(11f)
            foreground = UIManager.getColor("Label.disabledForeground")
            border = BorderFactory.createEmptyBorder(0, 0, 0, 8)
        }
        refreshBtn = JButton(AllIcons.Actions.Refresh).apply {
            toolTipText = "DB에서 새로고침"
            isBorderPainted = false
            isContentAreaFilled = false
            cursor = Cursor(Cursor.HAND_CURSOR)
            preferredSize = Dimension(28, 28)
            addActionListener { reloadFromJdbc(reason = "새로고침") }
        }

        val right = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(statusLabel, BorderLayout.CENTER)
            add(refreshBtn, BorderLayout.EAST)
        }

        return JPanel(BorderLayout(8, 0)).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UIManager.getColor("Separator.foreground")),
                BorderFactory.createEmptyBorder(5, 10, 5, 6),
            )
            add(kindLabel, BorderLayout.CENTER)
            add(right, BorderLayout.EAST)
        }
    }

    private fun kindText(): String = info.kind.name + (if (info.errors.isNotEmpty()) "  •  컴파일 오류 ${info.errors.size}건" else "")

    // ── 탭 빌드 ───────────────────────────────────────────────────────────────
    private fun buildTabs() {
        tabs.addTab("Source", createSqlPanel(info.source.ifBlank { "-- 소스 없음 --" }, header = "Source"))
        tabs.addTab("Execute", createSqlPanel(OracleDictionaryService.buildExecuteBlock(info), header = "Execute"))

        val errorsModel = DictionaryTableModel(
            listOf("Line", "Position", "Text"),
            info.errors.map { listOf(it.line, it.position, it.text) },
        )
        tabs.addTab("Errors  (${errorsModel.rowCount})", createTablePanel(errorsModel))

        val argsModel = DictionaryTableModel(
            listOf("Pos", "Name", "Direction", "Data Type", "Default"),
            info.arguments.map { listOf(it.position, it.name, it.direction, it.dataType, it.defaultValue) },
        )
        tabs.addTab("Arguments  (${argsModel.rowCount})", createTablePanel(argsModel))
    }

    // ── JDBC 재로딩 ──────────────────────────────────────────────────────────
    private fun reloadFromJdbc(reason: String) {
        if (loading) return
        val ds = routine.dataSource
        loading = true
        setLoadingUi(true, reason)

        object : Task.Backgroundable(project, "$schemaName.$routineName — DB에서 메타데이터 조회", true) {
            private var fetched: RoutineInfo? = null
            private var failure: Throwable? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                try {
                    fetched = JdbcRoutineRepository(project, ds, schemaName, routineName).loadRoutine()
                } catch (t: Throwable) {
                    LOG.warn("JDBC 루틴 조회 실패", t)
                    failure = t
                }
            }

            override fun onFinished() {
                ApplicationManager.getApplication().invokeLater {
                    loading = false
                    setLoadingUi(false, "")
                    val ok = fetched
                    val err = failure
                    when {
                        ok != null -> applyNewInfo(ok)
                        err != null -> notifyError("DB 조회 실패: ${err.message ?: err::class.simpleName}")
                        else -> notifyError("DB 조회가 취소되었습니다.")
                    }
                }
            }
        }.queue()
    }

    private fun applyNewInfo(newInfo: RoutineInfo) {
        val selected = tabs.selectedIndex
        info = newInfo
        kindLabel.text = kindText()
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
        group?.createNotification(text, NotificationType.WARNING)?.notify(project)
            ?: run { statusLabel.text = text }
    }

    // ── 공용 패널 헬퍼 (테이블 다이얼로그와 동일 스타일) ─────────────────────
    private fun createTablePanel(model: DictionaryTableModel): JComponent {
        val jbTable = JBTable(model).apply {
            setShowGrid(false)
            intercellSpacing = Dimension(0, 0)
            rowHeight = 24
            autoResizeMode = JTable.AUTO_RESIZE_OFF
            tableHeader.reorderingAllowed = true
            setDefaultRenderer(Any::class.java, StripedCellRenderer())
        }
        autoFitColumns(jbTable, model)
        return JBScrollPane(jbTable)
    }

    private fun autoFitColumns(tbl: JBTable, model: DictionaryTableModel) {
        val fm = tbl.getFontMetrics(tbl.font)
        val pad = 24
        for (col in 0 until model.columnCount) {
            val column = tbl.columnModel.getColumn(col)
            var max = fm.stringWidth(model.getColumnName(col)) + pad
            for (row in 0 until model.rowCount) {
                val w = fm.stringWidth(model.getValueAt(row, col)?.toString() ?: "") + pad
                if (w > max) max = w
            }
            column.preferredWidth = max.coerceIn(44, 480)
        }
    }

    private inner class StripedCellRenderer : DefaultTableCellRenderer() {
        private val evenBg = UIManager.getColor("Table.background")
        private val oddBg = UIManager.getColor("Table.stripeColor")
            ?: evenBg?.let { Color(it.red, it.green, it.blue, 220) }

        override fun getTableCellRendererComponent(
            tbl: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
        ): Component {
            super.getTableCellRendererComponent(tbl, value, isSelected, hasFocus, row, column)
            if (!isSelected) background = if (row % 2 == 0) evenBg else oddBg
            border = BorderFactory.createEmptyBorder(0, 6, 0, 6)
            return this
        }
    }

    private fun createSqlPanel(sql: String, header: String = "SQL"): JComponent {
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
                icon = AllIcons.Actions.Checked
                Timer(1200) { icon = AllIcons.Actions.Copy }.also { it.isRepeats = false; it.start() }
            }
        }
        val toolbar = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UIManager.getColor("Separator.foreground")),
                BorderFactory.createEmptyBorder(2, 6, 2, 4),
            )
            add(JLabel(header).apply { font = font.deriveFont(Font.BOLD, 11f) }, BorderLayout.WEST)
            add(copyBtn, BorderLayout.EAST)
        }
        return JPanel(BorderLayout()).apply {
            add(toolbar, BorderLayout.NORTH)
            add(JBScrollPane(textArea), BorderLayout.CENTER)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun centerHeaderRenderer(delegate: javax.swing.table.TableCellRenderer) =
        javax.swing.table.TableCellRenderer { tbl, value, sel, foc, row, col ->
            delegate.getTableCellRendererComponent(tbl, value, sel, foc, row, col)
                .also { (it as? JLabel)?.horizontalAlignment = SwingConstants.CENTER }
        }

    override fun createActions() = arrayOf(okAction)
}
