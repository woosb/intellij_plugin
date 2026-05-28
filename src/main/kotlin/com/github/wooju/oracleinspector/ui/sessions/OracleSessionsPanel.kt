package com.github.wooju.oracleinspector.ui.sessions

import com.github.wooju.oracleinspector.OracleInspectorBundle
import com.github.wooju.oracleinspector.model.LockInfo
import com.github.wooju.oracleinspector.model.LongOpInfo
import com.github.wooju.oracleinspector.model.PlanRow
import com.github.wooju.oracleinspector.model.SessionInfo
import com.github.wooju.oracleinspector.model.SessionStat
import com.github.wooju.oracleinspector.model.WaitEvent
import com.github.wooju.oracleinspector.repository.JdbcSessionRepository
import com.github.wooju.oracleinspector.ui.ColumnWidthMemo
import com.github.wooju.oracleinspector.ui.DictionaryTableModel
import com.github.wooju.oracleinspector.ui.TableSearchSupport
import com.intellij.database.Dbms
import com.intellij.database.psi.DbDataSource
import com.intellij.database.psi.DbPsiFacade
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.Alarm
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTable
import javax.swing.RowFilter
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableRowSorter

private val LOG = logger<OracleSessionsPanel>()
private const val AUTO_REFRESH_MILLIS = 5_000
private const val TAB_SESSIONS = "Sessions"
private const val TAB_LOCKS = "Locks"
private const val TAB_LONG_OPS = "Long Ops"

class OracleSessionsPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    // ── 공통 툴바 컴포넌트 ────────────────────────────────────────────────────
    private val dsCombo = ComboBox<DbDataSource>()
    private val refreshBtn = JButton(AllIcons.Actions.Refresh)
    private val autoToggle = JBCheckBox(OracleInspectorBundle.message("sessions.toggle.auto5s"), false)
    private val includeBg = JBCheckBox(OracleInspectorBundle.message("sessions.toggle.background"), false)
    private val statusLabel = JBLabel("").apply {
        font = font.deriveFont(11f)
        foreground = UIManager.getColor("Label.disabledForeground")
    }

    private val tabs = JBTabbedPane()
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    @Volatile private var loading = false

    // ── Sessions 탭 ──────────────────────────────────────────────────────────
    private val sessionColumns = listOf(
        "SID", "SERIAL#", "USER", "STATUS", "WAIT", "EVENT",
        "LAST CALL", "MACHINE", "PROGRAM", "MODULE", "SQL_ID", "BLOCKED BY",
    )
    private var sessions: List<SessionInfo> = emptyList()
    private val sessionsTable = JBTable(DictionaryTableModel.empty(sessionColumns))
    private val filterUser = JBTextField(10)
    private val filterStatus = JBTextField(8)
    private val filterProgram = JBTextField(12)
    private val filterModule = JBTextField(12)
    private val sqlDocument = EditorFactory.getInstance().createDocument("")
    private val sqlEditor: EditorEx

    // ── Locks 탭 ─────────────────────────────────────────────────────────────
    private val lockColumns = listOf(
        "SID", "SERIAL#", "USER", "OBJECT", "OBJ TYPE", "MODE",
        "SECS WAIT", "STATUS", "MACHINE", "PROGRAM", "MODULE", "BLOCKED BY",
    )
    private var locks: List<LockInfo> = emptyList()
    private val locksTable = JBTable(DictionaryTableModel.empty(lockColumns))

    // ── Long Ops 탭 ──────────────────────────────────────────────────────────
    private val longOpColumns = listOf(
        "SID", "SERIAL#", "USER", "OPERATION", "TARGET",
        "PROGRESS", "SOFAR", "TOTAL", "UNITS", "ELAPSED", "REMAINING", "MESSAGE",
    )
    private var longOps: List<LongOpInfo> = emptyList()
    private val longOpsTable = JBTable(DictionaryTableModel.empty(longOpColumns))

    // ── Wait History (Sessions 탭 하단 sub-tab) ──────────────────────────────
    private val waitColumns = listOf("SEQ#", "EVENT", "WAIT_TIME (cs)", "P1", "P2", "P3")
    private var waits: List<WaitEvent> = emptyList()
    private val waitsTable = JBTable(DictionaryTableModel.empty(waitColumns))

    // ── Session Stats (Sessions 탭 하단 sub-tab) ──────────────────────────────
    private val statColumns = listOf("Statistic", "Value")
    private var stats: List<SessionStat> = emptyList()
    private val statsTable = JBTable(DictionaryTableModel.empty(statColumns))

    // ── Explain Plan (Sessions 탭 하단 sub-tab) ──────────────────────────────
    private val planColumns = listOf("ID", "Operation", "Object", "Rows", "Bytes", "Cost", "CPU", "Time")
    private var plan: List<PlanRow> = emptyList()
    private val planTable = JBTable(DictionaryTableModel.empty(planColumns))

    init {
        sessionsTable.commonInit(SessionsCellRenderer())
        locksTable.commonInit(LocksCellRenderer())
        longOpsTable.commonInit(LongOpsCellRenderer())
        waitsTable.commonInit(object : StripedRenderer() {
            override fun decorate(tbl: JTable, row: Int, modelRow: Int, value: Any?, isSelected: Boolean) {
                if (!isSelected) background = if (row % 2 == 0) evenBg else oddBg
            }
        })
        statsTable.commonInit(object : StripedRenderer() {
            override fun decorate(tbl: JTable, row: Int, modelRow: Int, value: Any?, isSelected: Boolean) {
                if (!isSelected) background = if (row % 2 == 0) evenBg else oddBg
                horizontalAlignment = if (value is Number || (value is String && value.all { it.isDigit() || it == ',' })) SwingConstants.RIGHT else SwingConstants.LEFT
            }
        })
        planTable.commonInit(PlanCellRenderer())

        // Sessions: 행 선택 → Current SQL + Wait History + Stats + Plan 동시 갱신
        sessionsTable.selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                loadCurrentSqlForSelected()
                loadWaitHistoryForSelected()
                loadSessionStatsForSelected()
                loadExplainPlanForSelected()
            }
        }

        // Sessions: 우클릭 → Kill Session
        sessionsTable.addMouseListener(rightClickHandler { e ->
            val s = selectedSession() ?: return@rightClickHandler
            JPopupMenu().apply {
                add(JMenuItem(OracleInspectorBundle.message("sessions.kill.menu.session", s.sid, s.serial, s.username ?: "?")).apply {
                    addActionListener { confirmAndKill(s.sid, s.serial, s.username, s.machine, s.program, s.status) }
                })
            }.show(e.component, e.x, e.y)
        })

        // Locks: 우클릭 → Kill Holder
        locksTable.addMouseListener(rightClickHandler { e ->
            val l = selectedLock() ?: return@rightClickHandler
            JPopupMenu().apply {
                add(JMenuItem(OracleInspectorBundle.message("sessions.kill.menu.holder", l.sid, l.serial, l.username ?: "?")).apply {
                    addActionListener { confirmAndKill(l.sid, l.serial, l.username, l.machine, l.program, l.status) }
                })
            }.show(e.component, e.x, e.y)
        })

        // Current SQL 에디터
        val editorFactory = EditorFactory.getInstance()
        sqlEditor = editorFactory.createViewer(sqlDocument, project) as EditorEx
        val sqlFileType = FileTypeManager.getInstance().getFileTypeByExtension("sql")
        sqlEditor.highlighter = EditorHighlighterFactory.getInstance()
            .createEditorHighlighter(project, LightVirtualFile("current.sql", sqlFileType, ""))
        sqlEditor.settings.apply {
            isLineNumbersShown = true
            isLineMarkerAreaShown = false
            isFoldingOutlineShown = true
            isIndentGuidesShown = false
            isCaretRowShown = true
            additionalLinesCount = 0
            additionalColumnsCount = 1
        }
        Disposer.register(this) { EditorFactory.getInstance().releaseEditor(sqlEditor) }

        // 필터 입력 → 즉시 RowFilter 재평가
        val filterListener = simpleDocumentListener { applySessionFilter() }
        listOf(filterUser, filterStatus, filterProgram, filterModule)
            .forEach { it.document.addDocumentListener(filterListener) }

        // ── 공통 툴바 ─────────────────────────────────────────────────────────
        dsCombo.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean,
            ): Component {
                val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                text = (value as? DbDataSource)?.name ?: OracleInspectorBundle.message("common.no.datasource")
                return c
            }
        }
        dsCombo.preferredSize = Dimension(220, 28)
        dsCombo.addActionListener { if (dsCombo.selectedItem != null) reloadActiveTab() }

        refreshBtn.apply {
            toolTipText = OracleInspectorBundle.message("sessions.tooltip.refresh.now")
            isBorderPainted = false
            isContentAreaFilled = false
            cursor = Cursor(Cursor.HAND_CURSOR)
            preferredSize = Dimension(28, 28)
            addActionListener { reloadActiveTab() }
        }
        autoToggle.toolTipText = OracleInspectorBundle.message("sessions.tooltip.auto.refresh")
        autoToggle.addActionListener {
            if (autoToggle.isSelected) scheduleAutoRefresh() else alarm.cancelAllRequests()
        }
        includeBg.toolTipText = OracleInspectorBundle.message("sessions.tooltip.background")
        includeBg.addActionListener { if (currentTab() == TAB_SESSIONS) reloadActiveTab() }

        val toolbar = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UIManager.getColor("Separator.foreground")),
                BorderFactory.createEmptyBorder(3, 6, 3, 6),
            )
            val left = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                isOpaque = false
                add(JBLabel(OracleInspectorBundle.message("sessions.label.datasource")).apply { font = font.deriveFont(Font.BOLD, 11f) })
                add(dsCombo)
                add(refreshBtn)
                add(autoToggle)
                add(includeBg)
            }
            val right = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
                isOpaque = false
                add(statusLabel)
            }
            add(left, BorderLayout.WEST)
            add(right, BorderLayout.EAST)
        }

        // ── Sessions 탭 빌드 (하단은 Current SQL / Wait History / Stats sub-tab) ─────
        val detailTabs = JBTabbedPane().apply {
            addTab(OracleInspectorBundle.message("sessions.label.current.sql"), sqlEditor.component)
            addTab(OracleInspectorBundle.message("sessions.label.wait.history"), JBScrollPane(waitsTable))
            addTab(OracleInspectorBundle.message("sessions.label.stats"), JBScrollPane(statsTable))
            addTab(OracleInspectorBundle.message("sessions.label.plan"), JBScrollPane(planTable))
        }
        val sessionsTab = JPanel(BorderLayout()).apply {
            add(buildSessionFilterRow(), BorderLayout.NORTH)
            add(
                OnePixelSplitter(true, 0.6f).apply {
                    firstComponent = JBScrollPane(sessionsTable)
                    secondComponent = detailTabs
                },
                BorderLayout.CENTER,
            )
        }

        // ── Locks 탭 빌드 ─────────────────────────────────────────────────────
        val locksTab = JPanel(BorderLayout()).apply {
            add(JBScrollPane(locksTable), BorderLayout.CENTER)
        }

        // ── Long Ops 탭 빌드 ──────────────────────────────────────────────────
        val longOpsTab = JPanel(BorderLayout()).apply {
            add(JBScrollPane(longOpsTable), BorderLayout.CENTER)
        }

        tabs.addTab(TAB_SESSIONS, sessionsTab)
        tabs.addTab(TAB_LOCKS, locksTab)
        tabs.addTab(TAB_LONG_OPS, longOpsTab)
        tabs.addChangeListener {
            // 탭을 처음 열 때 자동 로드 (이미 로딩 중이거나 DS가 없으면 스킵)
            reloadActiveTab()
        }

        add(toolbar, BorderLayout.NORTH)
        add(tabs, BorderLayout.CENTER)

        populateDataSources()
    }

    private fun buildSessionFilterRow(): JPanel {
        val clearBtn = JButton(OracleInspectorBundle.message("sessions.filter.clear")).apply {
            isFocusable = false
            margin = java.awt.Insets(2, 8, 2, 8)
            addActionListener {
                filterUser.text = ""
                filterStatus.text = ""
                filterProgram.text = ""
                filterModule.text = ""
            }
        }
        return JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UIManager.getColor("Separator.foreground")),
                BorderFactory.createEmptyBorder(2, 4, 2, 4),
            )
            add(filterLabel(OracleInspectorBundle.message("sessions.filter.user"))); add(filterUser)
            add(filterLabel(OracleInspectorBundle.message("sessions.filter.status"))); add(filterStatus)
            add(filterLabel(OracleInspectorBundle.message("sessions.filter.program"))); add(filterProgram)
            add(filterLabel(OracleInspectorBundle.message("sessions.filter.module"))); add(filterModule)
            add(clearBtn)
        }
    }

    private fun filterLabel(text: String) = JBLabel(text).apply {
        font = font.deriveFont(Font.BOLD, 11f)
    }

    private fun JBTable.commonInit(renderer: DefaultTableCellRenderer) {
        setShowGrid(false)
        intercellSpacing = Dimension(0, 0)
        rowHeight = 24
        autoResizeMode = JTable.AUTO_RESIZE_OFF
        tableHeader.reorderingAllowed = true
        setDefaultRenderer(Any::class.java, renderer)
        rowSorter = TableRowSorter(model as DictionaryTableModel)
        TableSearchSupport.install(this)
    }

    private fun rightClickHandler(action: (MouseEvent) -> Unit) = object : MouseAdapter() {
        override fun mouseClicked(e: MouseEvent) {
            if (SwingUtilities.isRightMouseButton(e)) {
                val table = e.source as JBTable
                val viewRow = table.rowAtPoint(e.point)
                if (viewRow >= 0) {
                    if (!table.isRowSelected(viewRow)) table.setRowSelectionInterval(viewRow, viewRow)
                    action(e)
                }
            }
        }
    }

    private fun simpleDocumentListener(action: () -> Unit) = object : DocumentListener {
        override fun insertUpdate(e: DocumentEvent) { action() }
        override fun removeUpdate(e: DocumentEvent) { action() }
        override fun changedUpdate(e: DocumentEvent) { action() }
    }

    // ── 데이터소스 / 탭 전환 ─────────────────────────────────────────────────
    private fun populateDataSources() {
        val all = DbPsiFacade.getInstance(project).dataSources
            .filter { it.getDatabaseDialect()?.getDbms() == Dbms.ORACLE }
        dsCombo.removeAllItems()
        all.forEach { dsCombo.addItem(it) }
        if (all.isNotEmpty()) {
            dsCombo.selectedIndex = 0  // 액션 리스너가 자동 로드 트리거
        } else {
            statusLabel.text = OracleInspectorBundle.message("sessions.no.oracle.datasource")
        }
    }

    private fun selectedDs(): DbDataSource? = dsCombo.selectedItem as? DbDataSource
    private fun currentTab(): String = tabs.getTitleAt(tabs.selectedIndex.coerceAtLeast(0))

    private fun reloadActiveTab() {
        when (currentTab()) {
            TAB_SESSIONS -> reloadSessions()
            TAB_LOCKS -> reloadLocks()
            TAB_LONG_OPS -> reloadLongOps()
        }
    }

    private fun scheduleAutoRefresh() {
        alarm.cancelAllRequests()
        alarm.addRequest({ if (autoToggle.isSelected) reloadActiveTab() }, AUTO_REFRESH_MILLIS)
    }

    // ── Sessions 로딩 ────────────────────────────────────────────────────────
    private fun reloadSessions() {
        val ds = selectedDs() ?: return
        if (loading) return
        loading = true
        setBusy(true, OracleInspectorBundle.message("sessions.status.loading"))

        object : Task.Backgroundable(project, OracleInspectorBundle.message("sessions.task.title"), true) {
            private var fetched: List<SessionInfo>? = null
            private var failure: Throwable? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                try {
                    fetched = JdbcSessionRepository(project, ds).loadSessions(includeBg.isSelected)
                } catch (t: Throwable) {
                    LOG.warn("세션 조회 실패", t)
                    failure = t
                }
            }

            override fun onFinished() {
                ApplicationManager.getApplication().invokeLater {
                    loading = false
                    setBusy(false, "")
                    val ok = fetched
                    val err = failure
                    when {
                        ok != null -> renderSessions(ok)
                        err != null -> notifyError(humanizeError(err))
                    }
                    if (autoToggle.isSelected && !alarm.isDisposed) scheduleAutoRefresh()
                }
            }
        }.queue()
    }

    private fun renderSessions(list: List<SessionInfo>) {
        // Preserve user state across the model swap so Auto-5s refresh does not
        // wipe the current selection / sort. Without this, the selected row (and
        // therefore Current SQL / Wait History / Stats / Plan) flickers away
        // every 5 seconds.
        val keepKey: Pair<Int, Long>? = run {
            val viewRow = sessionsTable.selectedRow.takeIf { it >= 0 } ?: return@run null
            val modelRow = sessionsTable.convertRowIndexToModel(viewRow)
            sessions.getOrNull(modelRow)?.let { it.sid to it.serial }
        }
        val keepSortKeys = sessionsTable.rowSorter?.sortKeys?.toList()

        sessions = list
        val rows = list.map { s ->
            listOf(
                s.sid, s.serial, s.username ?: "", s.status ?: "",
                s.waitClass ?: "", s.event ?: "", s.lastCallEt ?: 0L,
                s.machine ?: "", s.program ?: "", s.module ?: "",
                s.sqlId ?: "", s.blockingSession?.toString() ?: "",
            )
        }
        val newModel = DictionaryTableModel(sessionColumns, rows)
        sessionsTable.model = newModel
        val sorter = TableRowSorter(newModel)
        sorter.rowFilter = makeSessionRowFilter()
        if (!keepSortKeys.isNullOrEmpty()) sorter.sortKeys = keepSortKeys
        sessionsTable.rowSorter = sorter
        autoFitColumns(sessionsTable, newModel)
        ColumnWidthMemo.apply(sessionsTable, "sessions.list")
        statusLabel.text = OracleInspectorBundle.message(
            "sessions.status.count.with.time", list.size, java.time.LocalTime.now().withNano(0),
        )

        // Restore selection if the same (SID, SERIAL#) is still alive.
        // The ListSelectionListener will re-fire and repopulate the bottom
        // sub-tabs (Current SQL / Wait History / Stats / Plan).
        val restored = keepKey?.let { (sid, serial) ->
            val modelIdx = list.indexOfFirst { it.sid == sid && it.serial == serial }
            if (modelIdx < 0) return@let false
            val viewIdx = sessionsTable.convertRowIndexToView(modelIdx)
            if (viewIdx < 0) return@let false
            sessionsTable.setRowSelectionInterval(viewIdx, viewIdx)
            sessionsTable.scrollRectToVisible(sessionsTable.getCellRect(viewIdx, 0, true))
            true
        } ?: false

        // Only clear the SQL editor when the previously selected session is gone
        // (logged out / killed). Otherwise the selection listener will refill it.
        if (!restored) {
            ApplicationManager.getApplication().runWriteAction { sqlDocument.setText("") }
        }
    }

    private fun applySessionFilter() {
        (sessionsTable.rowSorter as? TableRowSorter<*>)?.rowFilter = makeSessionRowFilter()
    }

    private fun makeSessionRowFilter(): RowFilter<Any, Any>? {
        val u = filterUser.text.trim()
        val s = filterStatus.text.trim()
        val p = filterProgram.text.trim()
        val m = filterModule.text.trim()
        if (u.isEmpty() && s.isEmpty() && p.isEmpty() && m.isEmpty()) return null
        return object : RowFilter<Any, Any>() {
            override fun include(entry: Entry<out Any, out Any>): Boolean {
                val modelRow = (entry.identifier as? Int) ?: return true
                val si = sessions.getOrNull(modelRow) ?: return true
                if (u.isNotEmpty() && si.username?.contains(u, ignoreCase = true) != true) return false
                if (s.isNotEmpty() && si.status?.contains(s, ignoreCase = true) != true) return false
                if (p.isNotEmpty() && si.program?.contains(p, ignoreCase = true) != true) return false
                if (m.isNotEmpty() && si.module?.contains(m, ignoreCase = true) != true) return false
                return true
            }
        }
    }

    private fun selectedSession(): SessionInfo? {
        val viewRow = sessionsTable.selectedRow.takeIf { it >= 0 } ?: return null
        val modelRow = sessionsTable.convertRowIndexToModel(viewRow)
        return sessions.getOrNull(modelRow)
    }

    private fun loadCurrentSqlForSelected() {
        val s = selectedSession() ?: return
        val sqlId = s.sqlId
        if (sqlId.isNullOrBlank()) {
            ApplicationManager.getApplication().runWriteAction {
                sqlDocument.setText(OracleInspectorBundle.message("sessions.current.sql.empty"))
            }
            return
        }
        val ds = selectedDs() ?: return
        object : Task.Backgroundable(project, OracleInspectorBundle.message("sessions.current.sql.task.title", sqlId), true) {
            private var fetched: String? = null
            private var failure: Throwable? = null
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                try { fetched = JdbcSessionRepository(project, ds).loadCurrentSqlText(sqlId) }
                catch (t: Throwable) { failure = t }
            }
            override fun onFinished() {
                ApplicationManager.getApplication().invokeLater {
                    val text = fetched
                        ?: failure?.let {
                            OracleInspectorBundle.message(
                                "sessions.current.sql.fetch.failed",
                                it.message ?: it::class.simpleName.orEmpty(),
                            )
                        }
                        ?: OracleInspectorBundle.message("sessions.current.sql.not.found")
                    ApplicationManager.getApplication().runWriteAction {
                        sqlDocument.setText(text)
                    }
                }
            }
        }.queue()
    }

    // ── Locks 로딩 ────────────────────────────────────────────────────────────
    private fun reloadLocks() {
        val ds = selectedDs() ?: return
        if (loading) return
        loading = true
        setBusy(true, OracleInspectorBundle.message("sessions.locks.status.loading"))

        object : Task.Backgroundable(project, OracleInspectorBundle.message("sessions.locks.task.title"), true) {
            private var fetched: List<LockInfo>? = null
            private var failure: Throwable? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                try { fetched = JdbcSessionRepository(project, ds).loadLocks() }
                catch (t: Throwable) {
                    LOG.warn("락 조회 실패", t)
                    failure = t
                }
            }

            override fun onFinished() {
                ApplicationManager.getApplication().invokeLater {
                    loading = false
                    setBusy(false, "")
                    val ok = fetched
                    val err = failure
                    when {
                        ok != null -> renderLocks(ok)
                        err != null -> notifyError(humanizeError(err))
                    }
                    if (autoToggle.isSelected && !alarm.isDisposed) scheduleAutoRefresh()
                }
            }
        }.queue()
    }

    private fun renderLocks(list: List<LockInfo>) {
        locks = list
        val rows = list.map { l ->
            val obj = listOfNotNull(l.objectOwner, l.objectName).joinToString(".").ifEmpty { "(unknown)" }
            listOf(
                l.sid, l.serial, l.username ?: "", obj,
                l.objectType ?: "", l.lockMode ?: "",
                l.secondsInWait ?: 0L, l.status ?: "",
                l.machine ?: "", l.program ?: "", l.module ?: "",
                l.blockingSession?.toString() ?: "",
            )
        }
        val newModel = DictionaryTableModel(lockColumns, rows)
        locksTable.model = newModel
        locksTable.rowSorter = TableRowSorter(newModel)
        autoFitColumns(locksTable, newModel)
        statusLabel.text = OracleInspectorBundle.message(
            "sessions.locks.status.count.with.time", list.size, java.time.LocalTime.now().withNano(0),
        )
    }

    // ── Long Ops 로딩 ────────────────────────────────────────────────────────
    private fun reloadLongOps() {
        val ds = selectedDs() ?: return
        if (loading) return
        loading = true
        setBusy(true, OracleInspectorBundle.message("sessions.longops.status.loading"))

        object : Task.Backgroundable(project, OracleInspectorBundle.message("sessions.longops.task.title"), true) {
            private var fetched: List<LongOpInfo>? = null
            private var failure: Throwable? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                try { fetched = JdbcSessionRepository(project, ds).loadLongOps() }
                catch (t: Throwable) {
                    LOG.warn("Long Ops 조회 실패", t)
                    failure = t
                }
            }

            override fun onFinished() {
                ApplicationManager.getApplication().invokeLater {
                    loading = false
                    setBusy(false, "")
                    val ok = fetched
                    val err = failure
                    when {
                        ok != null -> renderLongOps(ok)
                        err != null -> notifyError(humanizeError(err))
                    }
                    if (autoToggle.isSelected && !alarm.isDisposed) scheduleAutoRefresh()
                }
            }
        }.queue()
    }

    private fun renderLongOps(list: List<LongOpInfo>) {
        longOps = list
        val rows = list.map { op ->
            val pct = op.progressPercent()
            listOf(
                op.sid, op.serial, op.username ?: "",
                op.opname ?: "", op.target ?: "",
                if (pct == null) "" else "$pct%",
                op.sofar, op.totalwork, op.units ?: "",
                op.elapsedSec ?: 0L, op.timeRemainingSec ?: 0L,
                op.message ?: "",
            )
        }
        val newModel = DictionaryTableModel(longOpColumns, rows)
        longOpsTable.model = newModel
        longOpsTable.rowSorter = TableRowSorter(newModel)
        autoFitColumns(longOpsTable, newModel)
        statusLabel.text = OracleInspectorBundle.message(
            "sessions.longops.status.count.with.time", list.size, java.time.LocalTime.now().withNano(0),
        )
    }

    // ── Explain Plan 로딩 (선택 세션의 SQL_ID 기준) ─────────────────────────
    private fun loadExplainPlanForSelected() {
        val s = selectedSession()
        val sqlId = s?.sqlId
        if (s == null || sqlId.isNullOrBlank()) {
            plan = emptyList()
            planTable.model = DictionaryTableModel.empty(planColumns)
            return
        }
        val ds = selectedDs() ?: return
        object : Task.Backgroundable(
            project,
            OracleInspectorBundle.message("sessions.plan.task.title", sqlId),
            true,
        ) {
            private var fetched: List<PlanRow>? = null
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                try { fetched = JdbcSessionRepository(project, ds).loadExplainPlan(sqlId) }
                catch (t: Throwable) { LOG.warn("Explain plan 조회 실패", t) }
            }
            override fun onFinished() {
                ApplicationManager.getApplication().invokeLater {
                    val list = fetched ?: emptyList()
                    plan = list
                    val rows = list.map { p ->
                        val opLabel = buildString {
                            repeat(p.depth) { append("  ") }
                            append(p.operation ?: "")
                            if (!p.options.isNullOrBlank()) append(" ").append(p.options)
                        }
                        val obj = listOfNotNull(p.objectOwner, p.objectName)
                            .joinToString(".").ifEmpty { "" }
                        listOf(
                            p.id, opLabel, obj,
                            p.cardinality ?: 0L,
                            p.bytes ?: 0L,
                            p.cost ?: 0L,
                            p.cpuCost ?: 0L,
                            p.timeSec ?: 0L,
                        )
                    }
                    val newModel = DictionaryTableModel(planColumns, rows)
                    planTable.model = newModel
                    planTable.rowSorter = TableRowSorter(newModel)
                    autoFitColumns(planTable, newModel)
                }
            }
        }.queue()
    }

    // ── Session Stats 로딩 (선택 세션 기준) ─────────────────────────────────
    private fun loadSessionStatsForSelected() {
        val s = selectedSession()
        if (s == null) {
            stats = emptyList()
            statsTable.model = DictionaryTableModel.empty(statColumns)
            return
        }
        val ds = selectedDs() ?: return
        object : Task.Backgroundable(
            project,
            OracleInspectorBundle.message("sessions.stats.task.title", s.sid),
            true,
        ) {
            private var fetched: List<SessionStat>? = null
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                try { fetched = JdbcSessionRepository(project, ds).loadSessionStats(s.sid) }
                catch (t: Throwable) { LOG.warn("Session stats 조회 실패", t) }
            }
            override fun onFinished() {
                ApplicationManager.getApplication().invokeLater {
                    val list = fetched ?: emptyList()
                    stats = list
                    val rows = list.map { st ->
                        listOf(st.name, formatNumber(st.value))
                    }
                    val newModel = DictionaryTableModel(statColumns, rows)
                    statsTable.model = newModel
                    statsTable.rowSorter = TableRowSorter(newModel)
                    autoFitColumns(statsTable, newModel)
                }
            }
        }.queue()
    }

    private fun formatNumber(v: Long): String =
        if (v >= 1000) String.format("%,d", v) else v.toString()

    // ── Wait History 로딩 (선택 세션 기준) ──────────────────────────────────
    private fun loadWaitHistoryForSelected() {
        val s = selectedSession()
        if (s == null) {
            waits = emptyList()
            waitsTable.model = DictionaryTableModel.empty(waitColumns)
            return
        }
        val ds = selectedDs() ?: return
        object : Task.Backgroundable(
            project,
            OracleInspectorBundle.message("sessions.waits.task.title", s.sid),
            true,
        ) {
            private var fetched: List<WaitEvent>? = null
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                try { fetched = JdbcSessionRepository(project, ds).loadWaitHistory(s.sid) }
                catch (t: Throwable) { LOG.warn("Wait history 조회 실패", t) }
            }
            override fun onFinished() {
                ApplicationManager.getApplication().invokeLater {
                    val list = fetched ?: emptyList()
                    waits = list
                    val rows = list.map { w ->
                        listOf(w.seq, w.event ?: "", w.waitTime ?: 0L, w.p1 ?: "", w.p2 ?: "", w.p3 ?: "")
                    }
                    val newModel = DictionaryTableModel(waitColumns, rows)
                    waitsTable.model = newModel
                    waitsTable.rowSorter = TableRowSorter(newModel)
                    autoFitColumns(waitsTable, newModel)
                }
            }
        }.queue()
    }

    private fun selectedLock(): LockInfo? {
        val viewRow = locksTable.selectedRow.takeIf { it >= 0 } ?: return null
        val modelRow = locksTable.convertRowIndexToModel(viewRow)
        return locks.getOrNull(modelRow)
    }

    // ── 공용 유틸 ─────────────────────────────────────────────────────────────
    private fun autoFitColumns(table: JBTable, model: DictionaryTableModel) {
        val fm = table.getFontMetrics(table.font)
        val pad = 24
        for (col in 0 until model.columnCount) {
            val column = table.columnModel.getColumn(col)
            var max = fm.stringWidth(model.getColumnName(col)) + pad
            for (row in 0 until model.rowCount) {
                val w = fm.stringWidth(model.getValueAt(row, col)?.toString() ?: "") + pad
                if (w > max) max = w
            }
            column.preferredWidth = max.coerceIn(44, 320)
        }
    }

    private fun confirmAndKill(
        sid: Int, serial: Long, user: String?, machine: String?, program: String?, status: String?,
    ) {
        val msg = OracleInspectorBundle.message(
            "sessions.kill.confirm.message",
            sid, serial,
            user ?: "?", machine ?: "?", program ?: "?", status ?: "?",
        )
        val ok = Messages.showOkCancelDialog(
            project, msg,
            OracleInspectorBundle.message("sessions.kill.confirm.title"),
            OracleInspectorBundle.message("sessions.kill.confirm.button"),
            OracleInspectorBundle.message("sessions.kill.confirm.cancel"),
            AllIcons.General.WarningDialog,
        )
        if (ok != Messages.OK) return

        val ds = selectedDs() ?: return
        object : Task.Backgroundable(project, OracleInspectorBundle.message("sessions.kill.task.title", sid, serial), true) {
            private var failure: Throwable? = null
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                try { JdbcSessionRepository(project, ds).killSession(sid, serial) }
                catch (t: Throwable) { failure = t }
            }
            override fun onFinished() {
                ApplicationManager.getApplication().invokeLater {
                    val err = failure
                    if (err == null) {
                        notifyInfo(OracleInspectorBundle.message("sessions.kill.notify.sent", sid, serial))
                        reloadActiveTab()
                    } else {
                        notifyError(OracleInspectorBundle.message("sessions.kill.failed", err.message ?: err::class.simpleName.orEmpty()))
                    }
                }
            }
        }.queue()
    }

    private fun setBusy(busy: Boolean, message: String) {
        refreshBtn.isEnabled = !busy
        refreshBtn.icon = if (busy) AllIcons.Process.Step_1 else AllIcons.Actions.Refresh
        if (message.isNotEmpty()) statusLabel.text = message
    }

    private fun humanizeError(t: Throwable): String {
        val msg = t.message.orEmpty()
        return when {
            "ORA-00942" in msg || "table or view does not exist" in msg ->
                OracleInspectorBundle.message("sessions.error.ora00942")
            "ORA-01031" in msg || "insufficient privileges" in msg ->
                OracleInspectorBundle.message("sessions.error.ora01031")
            else -> OracleInspectorBundle.message(
                "sessions.error.generic", msg.ifBlank { t::class.simpleName.orEmpty() },
            )
        }
    }

    private fun notifyError(text: String) {
        val group = NotificationGroupManager.getInstance().getNotificationGroup("Oracle Dictionary Inspector")
        group?.createNotification(text, NotificationType.WARNING)?.notify(project)
            ?: run { statusLabel.text = text }
    }
    private fun notifyInfo(text: String) {
        val group = NotificationGroupManager.getInstance().getNotificationGroup("Oracle Dictionary Inspector")
        group?.createNotification(text, NotificationType.INFORMATION)?.notify(project)
    }

    override fun dispose() {
        alarm.cancelAllRequests()
        Disposer.dispose(alarm)
    }

    // ── 셀 렌더러: Sessions ──────────────────────────────────────────────────
    private inner class SessionsCellRenderer : StripedRenderer() {
        private val activeFg = JBColor(Color(0, 110, 0), Color(140, 220, 140))
        private val blockedBg = JBColor(Color(255, 230, 230), Color(80, 35, 35))

        override fun decorate(tbl: JTable, row: Int, modelRow: Int, value: Any?, isSelected: Boolean) {
            val s = sessions.getOrNull(modelRow)
            if (!isSelected) {
                background = when {
                    s?.blockingSession != null -> blockedBg
                    row % 2 == 0 -> evenBg
                    else -> oddBg
                }
                foreground = if (s?.status == "ACTIVE") activeFg else UIManager.getColor("Table.foreground")
            }
        }
    }

    // ── 셀 렌더러: Locks (Exclusive/Row-X 모드 강조) ─────────────────────────
    private inner class LocksCellRenderer : StripedRenderer() {
        private val strongBg = JBColor(Color(255, 215, 215), Color(85, 35, 35))

        override fun decorate(tbl: JTable, row: Int, modelRow: Int, value: Any?, isSelected: Boolean) {
            val l = locks.getOrNull(modelRow)
            if (!isSelected) {
                background = when {
                    l?.lockMode == "Exclusive" || l?.lockMode == "Row-X" -> strongBg
                    row % 2 == 0 -> evenBg
                    else -> oddBg
                }
            }
        }
    }

    // ── 셀 렌더러: Explain Plan (비싼 OPERATION 빨강 강조) ──────────────────
    private inner class PlanCellRenderer : StripedRenderer() {
        private val expensiveBg = JBColor(Color(255, 220, 220), Color(90, 35, 35))
        private val indexFg = JBColor(Color(0, 110, 0), Color(140, 220, 140))

        override fun decorate(tbl: JTable, row: Int, modelRow: Int, value: Any?, isSelected: Boolean) {
            val p = plan.getOrNull(modelRow)
            val isExpensive = p != null && (
                (p.operation == "TABLE ACCESS" && p.options == "FULL") ||
                p.operation?.contains("CARTESIAN") == true
            )
            val isIndex = p != null && p.operation?.startsWith("INDEX") == true
            if (!isSelected) {
                background = when {
                    isExpensive -> expensiveBg
                    row % 2 == 0 -> evenBg
                    else -> oddBg
                }
                foreground = if (isIndex) indexFg else UIManager.getColor("Table.foreground")
            }
            horizontalAlignment = if (value is Number) SwingConstants.RIGHT else SwingConstants.LEFT
        }
    }

    // ── 셀 렌더러: Long Ops (Progress 컬럼만 진행률 그라데이션) ──────────────
    private inner class LongOpsCellRenderer : StripedRenderer() {
        private val progressBg = JBColor(Color(220, 240, 255), Color(40, 70, 95))
        override fun decorate(tbl: JTable, row: Int, modelRow: Int, value: Any?, isSelected: Boolean) {
            if (!isSelected) {
                background = if (row % 2 == 0) evenBg else oddBg
                // PROGRESS 컬럼이면 살짝 강조
                val colName = tbl.columnModel.getColumn(tbl.convertColumnIndexToModel(0)).headerValue
                if (value is String && value.endsWith("%")) {
                    background = progressBg
                    horizontalAlignment = SwingConstants.RIGHT
                }
            }
        }
    }

    private abstract inner class StripedRenderer : DefaultTableCellRenderer() {
        protected val evenBg = UIManager.getColor("Table.background")
        protected val oddBg = UIManager.getColor("Table.stripeColor")
            ?: evenBg?.let { Color(it.red, it.green, it.blue, 220) }

        abstract fun decorate(tbl: JTable, row: Int, modelRow: Int, value: Any?, isSelected: Boolean)

        override fun getTableCellRendererComponent(
            tbl: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
        ): Component {
            super.getTableCellRendererComponent(tbl, value, isSelected, hasFocus, row, column)
            val modelRow = tbl.convertRowIndexToModel(row)
            decorate(tbl, row, modelRow, value, isSelected)
            horizontalAlignment = if (value is Number) SwingConstants.RIGHT else SwingConstants.LEFT
            border = BorderFactory.createEmptyBorder(0, 6, 0, 6)
            return this
        }
    }
}
