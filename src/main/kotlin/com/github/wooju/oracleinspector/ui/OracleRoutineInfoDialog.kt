package com.github.wooju.oracleinspector.ui

import com.github.wooju.oracleinspector.OracleInspectorBundle
import com.github.wooju.oracleinspector.actions.OracleInspectorDataKeys
import com.github.wooju.oracleinspector.cache.OracleMetadataCache
import com.github.wooju.oracleinspector.model.RoutineInfo
import com.github.wooju.oracleinspector.repository.DasRoutineRepository
import com.github.wooju.oracleinspector.repository.JdbcObjectChangeRepository
import com.github.wooju.oracleinspector.repository.JdbcRoutineRepository
import com.github.wooju.oracleinspector.service.OracleDictionaryService
import com.intellij.database.psi.DbRoutine
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.EditorFactory
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
    @Volatile private var loading: Boolean = false
    @Volatile private var stale: Boolean = false
    private var sourceEditor: EditorEx? = null

    private val cache = OracleMetadataCache.getInstance(project)
    private val cacheKey = OracleMetadataCache.key(
        routine.dataSource.uniqueId, schemaName, routineName, "ROUTINE",
    )
    private var cachedFrom: Boolean = false

    // 1순위: 우리 캐시(이전 JDBC 결과) → 없으면 DAS. DAS도 불완전하면 init에서 JDBC 폴백.
    private var info: RoutineInfo = run {
        val entry = cache.get(cacheKey)
        (entry?.dto as? RoutineInfo)?.also { cachedFrom = true }
            ?: DasRoutineRepository(routine, schemaName, routineName).loadRoutine()
    }

    init {
        title = "$schemaName.$routineName"
        isModal = false
        init()
        when {
            // 캐시에서 즉시 표시됨 → DB 변경 여부만 백그라운드로 가볍게 검증
            cachedFrom -> scheduleStaleCheck()
            // 소스가 비어있으면 자동으로 JDBC 폴백 (결과는 캐시에 저장됨)
            info.isIncomplete() ->
                reloadFromJdbc(reason = OracleInspectorBundle.message("routine.auto.refresh.reason.no.cached.source"))
        }
    }

    override fun createCenterPanel(): JComponent {
        val root = object : JPanel(BorderLayout()), DataProvider {
            override fun getData(dataId: String): Any? = when (dataId) {
                OracleInspectorDataKeys.CURRENT_OWNER.name -> schemaName
                OracleInspectorDataKeys.CURRENT_DATA_SOURCE.name -> routine.dataSource
                else -> null
            }
        }
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
            add(kindLabel, BorderLayout.CENTER)
            add(right, BorderLayout.EAST)
        }
    }

    private fun kindText(): String = if (info.errors.isNotEmpty())
        OracleInspectorBundle.message("routine.kind.with.errors", info.kind.name, info.errors.size)
    else info.kind.name

    // ── 탭 빌드 ───────────────────────────────────────────────────────────────
    private fun buildTabs() {
        tabs.addTab(
            OracleInspectorBundle.message("routine.tab.source"),
            createSourceEditorPanel(info.source.ifBlank { OracleInspectorBundle.message("routine.source.empty") }),
        )
        tabs.addTab(
            OracleInspectorBundle.message("routine.tab.execute"),
            createSqlPanel(OracleDictionaryService.buildExecuteBlock(info), header = OracleInspectorBundle.message("routine.tab.execute")),
        )

        val errorsModel = DictionaryTableModel(
            listOf("Line", "Position", "Text"),
            info.errors.map { listOf(it.line, it.position, it.text) },
        )
        tabs.addTab(OracleInspectorBundle.message("routine.tab.errors", errorsModel.rowCount), createErrorsTablePanel(errorsModel))

        val argsModel = DictionaryTableModel(
            listOf("Pos", "Name", "Direction", "Data Type", "Default"),
            info.arguments.map { listOf(it.position, it.name, it.direction, it.dataType, it.defaultValue) },
        )
        tabs.addTab(OracleInspectorBundle.message("routine.tab.arguments", argsModel.rowCount), createTablePanel(argsModel))
    }

    // ── JDBC 재로딩 ──────────────────────────────────────────────────────────
    private fun reloadFromJdbc(reason: String) {
        if (loading) return
        val ds = routine.dataSource
        loading = true
        setLoadingUi(true, reason)

        object : Task.Backgroundable(
            project,
            OracleInspectorBundle.message("routine.task.title.fetch.metadata", schemaName, routineName),
            true,
        ) {
            private var fetched: RoutineInfo? = null
            private var fetchedDdl: String? = null
            private var failure: Throwable? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                try {
                    fetched = JdbcRoutineRepository(project, ds, schemaName, routineName).loadRoutine()
                    // 캐시 staleness 기준선 — 풀 fetch 시점의 LAST_DDL_TIME
                    fetchedDdl = JdbcObjectChangeRepository(project, ds)
                        .loadLastDdlTime(schemaName, routineName, ROUTINE_OBJECT_TYPES)
                } catch (t: Throwable) {
                    LOG.warn("JDBC 루틴 조회 실패", t)
                    failure = t
                }
            }

            override fun onFinished() {
                ApplicationManager.getApplication().invokeLater {
                    loading = false
                    val ok = fetched
                    val err = failure
                    when {
                        ok != null -> {
                            applyNewInfo(ok)
                            cache.put(cacheKey, ok, fetchedDdl)   // 다음에 같은 객체 열면 JDBC 스킵
                            stale = false
                            setLoadingUi(false, "")               // stale 표시도 같이 해제됨
                        }
                        err != null -> {
                            setLoadingUi(false, "")
                            notifyError(OracleInspectorBundle.message("common.query.failed", err.message ?: err::class.simpleName.orEmpty()))
                        }
                        else -> {
                            setLoadingUi(false, "")
                            notifyError(OracleInspectorBundle.message("common.query.cancelled"))
                        }
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

    // ── 캐시 staleness: DB에서 변경됐는지 백그라운드로 가볍게 검증 ──────────────
    private fun scheduleStaleCheck() {
        val ds = routine.dataSource
        val baseline = cache.get(cacheKey)?.lastDdlTime ?: return  // 기준선 없으면 비교 불가
        object : Task.Backgroundable(project, OracleInspectorBundle.message("dialog.tooltip.refresh.from.db"), true) {
            private var current: String? = null
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                current = JdbcObjectChangeRepository(project, ds)
                    .loadLastDdlTime(schemaName, routineName, ROUTINE_OBJECT_TYPES)
            }
            override fun onFinished() {
                ApplicationManager.getApplication().invokeLater {
                    // current 가 null(권한 등)이면 조용히 무시. 다를 때만 표시.
                    if (current != null && current != baseline && !loading) {
                        stale = true
                        applyStaleIndicator()
                    }
                }
            }
        }.queue()
    }

    private fun applyStaleIndicator() {
        refreshBtn.icon = AllIcons.Actions.ForceRefresh
        refreshBtn.toolTipText = OracleInspectorBundle.message("cache.stale.tooltip")
        statusLabel.text = OracleInspectorBundle.message("cache.stale.notice")
        statusLabel.foreground = STALE_FG
    }

    private fun setLoadingUi(busy: Boolean, message: String) {
        refreshBtn.isEnabled = !busy
        when {
            busy -> refreshBtn.icon = AllIcons.Process.Step_1
            stale -> refreshBtn.icon = AllIcons.Actions.ForceRefresh
            else -> refreshBtn.icon = AllIcons.Actions.Refresh
        }
        if (busy) {
            statusLabel.text = message
        } else if (stale) {
            statusLabel.text = OracleInspectorBundle.message("cache.stale.notice")
            statusLabel.foreground = STALE_FG
        } else {
            statusLabel.text = message
            statusLabel.foreground = UIManager.getColor("Label.disabledForeground")
            refreshBtn.toolTipText = OracleInspectorBundle.message("dialog.tooltip.refresh.from.db")
        }
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
        TableSearchSupport.install(jbTable)
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
        // Live getters so colours follow the current IDE theme on the fly.
        private val evenBg: Color? get() = UIManager.getColor("Table.background")
        private val oddBg: Color? get() = UIManager.getColor("Table.stripeColor")
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

    // ── Source 전용: IntelliJ Editor (라인번호 + SQL 신택스 + 에러 라인 강조) ──
    private fun createSourceEditorPanel(source: String): JComponent {
        val editorFactory = EditorFactory.getInstance()
        val document = editorFactory.createDocument(source)
        val editor = editorFactory.createViewer(document, project) as EditorEx
        sourceEditor = editor

        // SQL FileType 있으면 신택스 하이라이팅, 없으면 PlainText로 자연 폴백
        val sqlFileType = FileTypeManager.getInstance().getFileTypeByExtension("sql")
        val vfile = LightVirtualFile("source.sql", sqlFileType, source)
        editor.highlighter = EditorHighlighterFactory.getInstance()
            .createEditorHighlighter(project, vfile)

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

        applyErrorHighlights(editor)

        Disposer.register(disposable) {
            EditorFactory.getInstance().releaseEditor(editor)
        }

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
            add(JLabel("Source").apply { font = font.deriveFont(Font.BOLD, 11f) }, BorderLayout.WEST)
            add(copyBtn, BorderLayout.EAST)
        }

        return JPanel(BorderLayout()).apply {
            add(toolbar, BorderLayout.NORTH)
            add(editor.component, BorderLayout.CENTER)
        }
    }

    private fun applyErrorHighlights(editor: EditorEx) {
        val markup = editor.markupModel
        markup.removeAllHighlighters()
        if (info.errors.isEmpty()) return

        val errorBg = JBColor(Color(255, 220, 220), Color(90, 35, 35))
        val attr = TextAttributes().apply { backgroundColor = errorBg }

        val lineCount = editor.document.lineCount
        info.errors.forEach { err ->
            val lineIdx = (err.line - 1).coerceAtLeast(0)
            if (lineIdx >= lineCount) return@forEach
            val highlighter = markup.addLineHighlighter(
                lineIdx,
                HighlighterLayer.WARNING,
                attr,
            )
            highlighter.setErrorStripeMarkColor(JBColor.RED)
            highlighter.setErrorStripeTooltip(
                OracleInspectorBundle.message("routine.error.stripe.tooltip", err.line, err.position, err.text)
            )
        }
    }

    // ── Errors 탭: 행 더블클릭 → Source 탭 + 라인 점프 ──────────────────────
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
                    val line = model.getValueAt(modelRow, 0) as? Int ?: return
                    jumpToSourceLine(line)
                }
            }
        })

        return JBScrollPane(jbTable)
    }

    private fun jumpToSourceLine(oneBasedLine: Int) {
        val editor = sourceEditor ?: return
        val srcIdx = (0 until tabs.tabCount).indexOfFirst { tabs.getTitleAt(it) == "Source" }
        if (srcIdx >= 0) tabs.selectedIndex = srcIdx

        val lineCount = editor.document.lineCount
        if (lineCount == 0) return
        val line = (oneBasedLine - 1).coerceIn(0, lineCount - 1)
        val offset = editor.document.getLineStartOffset(line)
        editor.caretModel.moveToOffset(offset)
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
        editor.contentComponent.requestFocusInWindow()
    }

    private fun createSqlPanel(sql: String, header: String = "SQL"): JComponent {
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

    companion object {
        /** standalone 루틴은 PROCEDURE 또는 FUNCTION 중 하나로 잡힘 — 둘 다 조회. */
        private val ROUTINE_OBJECT_TYPES = listOf("PROCEDURE", "FUNCTION")
        /** stale 안내 색 — 테마 대응 amber. */
        private val STALE_FG = JBColor(Color(0xB8, 0x6B, 0x00), Color(0xE0, 0xA8, 0x3A))
    }
}
