package com.github.wooju.oracleinspector.ui

import com.github.wooju.oracleinspector.OracleInspectorBundle
import com.github.wooju.oracleinspector.actions.OracleInspectorDataKeys
import com.github.wooju.oracleinspector.model.PackageError
import com.github.wooju.oracleinspector.model.PackageInfo
import com.github.wooju.oracleinspector.repository.DasPackageRepository
import com.github.wooju.oracleinspector.repository.JdbcPackageRepository
import com.intellij.database.psi.DbDataSource
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.JBColor
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
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.Timer
import javax.swing.UIManager
import javax.swing.table.DefaultTableCellRenderer

private val LOG = logger<OraclePackageInfoDialog>()

/**
 * Oracle PL/SQL PACKAGE 정보 다이얼로그.
 *
 *  탭 구성:
 *   - Spec     : ALL_SOURCE TYPE='PACKAGE' — IntelliJ Editor (line numbers + SQL syntax + error markers)
 *   - Body     : ALL_SOURCE TYPE='PACKAGE BODY' — 없으면 안내 텍스트
 *   - Routines : ALL_PROCEDURES (NAME / OVERLOAD / KIND)
 *   - Errors   : ALL_ERRORS — 행 더블클릭 시 sourceType에 맞춰 Spec/Body 탭으로 점프
 */
class OraclePackageInfoDialog(
    private val project: Project,
    private val dataSource: DbDataSource,
    private val schemaName: String,
    private val packageName: String,
) : DialogWrapper(project), DataProvider {

    private lateinit var tabs: JBTabbedPane
    private lateinit var refreshBtn: JButton
    private lateinit var statusLabel: JBLabel
    private lateinit var headerLabel: JBLabel
    private var info: PackageInfo = DasPackageRepository(schemaName, packageName).loadPackage()
    @Volatile private var loading: Boolean = false

    // 에디터를 재로딩 시 점프/하이라이트 적용 위해 멤버 변수에 보관
    private var specEditor: EditorEx? = null
    private var bodyEditor: EditorEx? = null

    init {
        title = "$schemaName.$packageName"
        isModal = false
        init()
        if (info.isIncomplete()) {
            reloadFromJdbc(reason = OracleInspectorBundle.message("routine.auto.refresh.reason.no.cached.source"))
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

    override fun getData(dataId: String): Any? = when (dataId) {
        OracleInspectorDataKeys.CURRENT_OWNER.name -> schemaName
        OracleInspectorDataKeys.CURRENT_DATA_SOURCE.name -> dataSource
        else -> null
    }

    // ── 상단 바 ───────────────────────────────────────────────────────────────
    private fun buildTopBar(): JComponent {
        headerLabel = JBLabel(headerText()).apply {
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
            addActionListener { reloadFromJdbc(reason = OracleInspectorBundle.message("routine.refresh.reason.manual")) }
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
            add(headerLabel, BorderLayout.CENTER)
            add(right, BorderLayout.EAST)
        }
    }

    private fun headerText(): String {
        val kind = "PACKAGE"
        return if (info.errors.isNotEmpty())
            OracleInspectorBundle.message("routine.kind.with.errors", kind, info.errors.size)
        else kind
    }

    // ── 탭 빌드 ───────────────────────────────────────────────────────────────
    private fun buildTabs() {
        specEditor = null
        bodyEditor = null

        tabs.addTab(
            OracleInspectorBundle.message("package.tab.spec"),
            createSourceEditorPanel(
                info.specSource.ifBlank { OracleInspectorBundle.message("routine.source.empty") },
                sourceTypeLabel = "PACKAGE",
            ),
        )
        val bodyText = info.bodySource?.takeIf { it.isNotBlank() }
            ?: "-- ${OracleInspectorBundle.message("package.body.missing")} --"
        tabs.addTab(
            OracleInspectorBundle.message("package.tab.body"),
            createSourceEditorPanel(bodyText, sourceTypeLabel = "PACKAGE BODY"),
        )

        val routinesModel = DictionaryTableModel(
            listOf("Name", "Overload", "Kind"),
            info.routines.map { listOf(it.name, it.overload ?: "", it.kind) },
        )
        tabs.addTab(
            OracleInspectorBundle.message("package.tab.routines", routinesModel.rowCount),
            createTablePanel(routinesModel),
        )

        val errorsModel = DictionaryTableModel(
            listOf("Source", "Line", "Position", "Text"),
            info.errors.map { listOf(it.sourceType, it.line, it.position, it.text) },
        )
        tabs.addTab(
            OracleInspectorBundle.message("package.tab.errors", errorsModel.rowCount),
            createErrorsTablePanel(errorsModel),
        )
    }

    // ── JDBC 재로딩 ──────────────────────────────────────────────────────────
    private fun reloadFromJdbc(reason: String) {
        if (loading) return
        loading = true
        setLoadingUi(true, reason)

        object : Task.Backgroundable(
            project,
            OracleInspectorBundle.message("routine.task.title.fetch.metadata", schemaName, packageName),
            true,
        ) {
            private var fetched: PackageInfo? = null
            private var failure: Throwable? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                try {
                    fetched = JdbcPackageRepository(project, dataSource, schemaName, packageName).loadPackage()
                } catch (t: Throwable) {
                    LOG.warn("JDBC 패키지 조회 실패", t)
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
                        err != null -> notifyError(
                            OracleInspectorBundle.message(
                                "common.query.failed",
                                err.message ?: err::class.simpleName.orEmpty(),
                            )
                        )
                        else -> notifyError(OracleInspectorBundle.message("common.query.cancelled"))
                    }
                }
            }
        }.queue()
    }

    private fun applyNewInfo(newInfo: PackageInfo) {
        val selected = tabs.selectedIndex
        info = newInfo
        headerLabel.text = headerText()
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

    // ── Source Editor (Spec / Body 공통) ────────────────────────────────────
    private fun createSourceEditorPanel(source: String, sourceTypeLabel: String): JComponent {
        val editorFactory = EditorFactory.getInstance()
        val document = editorFactory.createDocument(source)
        val editor = editorFactory.createViewer(document, project) as EditorEx

        val sqlFileType = FileTypeManager.getInstance().getFileTypeByExtension("sql")
        val vfile = LightVirtualFile("source.sql", sqlFileType, source)
        editor.highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(project, vfile)

        editor.settings.apply {
            isLineNumbersShown = true
            isLineMarkerAreaShown = true
            isFoldingOutlineShown = true
            isIndentGuidesShown = false
            isCaretRowShown = true
            additionalLinesCount = 0
            additionalColumnsCount = 1
        }
        editor.setCaretEnabled(true)

        // 해당 sourceTypeLabel에 해당하는 에러만 라인 강조
        applyErrorHighlights(editor, sourceTypeLabel)

        // 멤버에 보관해 errors 탭 더블클릭 점프에서 사용
        when (sourceTypeLabel) {
            "PACKAGE" -> specEditor = editor
            "PACKAGE BODY" -> bodyEditor = editor
        }

        Disposer.register(disposable) { EditorFactory.getInstance().releaseEditor(editor) }

        val copyBtn = JButton(AllIcons.Actions.Copy).apply {
            toolTipText = OracleInspectorBundle.message("common.copy.to.clipboard")
            isBorderPainted = false
            isContentAreaFilled = false
            cursor = Cursor(Cursor.HAND_CURSOR)
            preferredSize = Dimension(28, 28)
            addActionListener {
                CopyPasteManager.getInstance().setContents(StringSelection(document.text))
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
            add(JLabel(sourceTypeLabel).apply { font = font.deriveFont(Font.BOLD, 11f) }, BorderLayout.WEST)
            add(copyBtn, BorderLayout.EAST)
        }
        return JPanel(BorderLayout()).apply {
            add(toolbar, BorderLayout.NORTH)
            add(editor.component, BorderLayout.CENTER)
        }
    }

    private fun applyErrorHighlights(editor: EditorEx, sourceTypeLabel: String) {
        val markup = editor.markupModel
        markup.removeAllHighlighters()
        val relevant = info.errors.filter { it.sourceType.equals(sourceTypeLabel, ignoreCase = true) }
        if (relevant.isEmpty()) return

        val errorBg = JBColor(Color(255, 220, 220), Color(90, 35, 35))
        val attr = TextAttributes().apply { backgroundColor = errorBg }
        val lineCount = editor.document.lineCount
        relevant.forEach { err ->
            val lineIdx = (err.line - 1).coerceAtLeast(0)
            if (lineIdx >= lineCount) return@forEach
            val h = markup.addLineHighlighter(lineIdx, HighlighterLayer.WARNING, attr)
            h.setErrorStripeMarkColor(JBColor.RED)
            h.setErrorStripeTooltip(
                OracleInspectorBundle.message("routine.error.stripe.tooltip", err.line, err.position, err.text)
            )
        }
    }

    // ── 공용 테이블 패널 ─────────────────────────────────────────────────────
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
        TableSearchSupport.install(jbTable)
        return JBScrollPane(jbTable)
    }

    private fun createErrorsTablePanel(model: DictionaryTableModel): JComponent {
        val jbTable = JBTable(model).apply {
            setShowGrid(false)
            intercellSpacing = Dimension(0, 0)
            rowHeight = 24
            autoResizeMode = JTable.AUTO_RESIZE_OFF
            tableHeader.reorderingAllowed = true
            setDefaultRenderer(Any::class.java, StripedCellRenderer())
        }
        autoFitColumns(jbTable, model)
        TableSearchSupport.install(jbTable)
        jbTable.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val viewRow = jbTable.rowAtPoint(e.point).takeIf { it >= 0 } ?: return
                    val modelRow = jbTable.convertRowIndexToModel(viewRow)
                    val err = info.errors.getOrNull(modelRow) ?: return
                    jumpToError(err)
                }
            }
        })
        return JBScrollPane(jbTable)
    }

    private fun jumpToError(err: PackageError) {
        val (label, editor) = when (err.sourceType.uppercase()) {
            "PACKAGE BODY" -> "package.tab.body" to bodyEditor
            else -> "package.tab.spec" to specEditor
        }
        val ed = editor ?: return
        val tabTitle = OracleInspectorBundle.message(label)
        val idx = (0 until tabs.tabCount).indexOfFirst { tabs.getTitleAt(it) == tabTitle }
        if (idx >= 0) tabs.selectedIndex = idx

        val lineCount = ed.document.lineCount
        if (lineCount == 0) return
        val line = (err.line - 1).coerceIn(0, lineCount - 1)
        val offset = ed.document.getLineStartOffset(line)
        ed.caretModel.moveToOffset(offset)
        ed.scrollingModel.scrollToCaret(ScrollType.CENTER)
        ed.contentComponent.requestFocusInWindow()
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
            column.preferredWidth = max.coerceIn(44, 540)
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

    override fun createActions() = arrayOf(okAction)
}
