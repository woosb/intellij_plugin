package com.github.wooju.oracleinspector.ui.sessions

import com.github.wooju.oracleinspector.model.LockInfo
import com.github.wooju.oracleinspector.model.SessionInfo
import com.github.wooju.oracleinspector.repository.JdbcSessionRepository
import com.github.wooju.oracleinspector.ui.DictionaryTableModel
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

class OracleSessionsPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    // ── 공통 툴바 컴포넌트 ────────────────────────────────────────────────────
    private val dsCombo = ComboBox<DbDataSource>()
    private val refreshBtn = JButton(AllIcons.Actions.Refresh)
    private val autoToggle = JBCheckBox("Auto 5s", false)
    private val includeBg = JBCheckBox("Background", false)
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

    init {
        sessionsTable.commonInit(SessionsCellRenderer())
        locksTable.commonInit(LocksCellRenderer())

        // Sessions: 행 선택 → SQL 패널 갱신
        sessionsTable.selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting) loadCurrentSqlForSelected()
        }

        // Sessions: 우클릭 → Kill Session
        sessionsTable.addMouseListener(rightClickHandler { e ->
            val s = selectedSession() ?: return@rightClickHandler
            JPopupMenu().apply {
                add(JMenuItem("Kill Session ${s.sid},${s.serial} (${s.username ?: "?"})").apply {
                    addActionListener { confirmAndKill(s.sid, s.serial, s.username, s.machine, s.program, s.status) }
                })
            }.show(e.component, e.x, e.y)
        })

        // Locks: 우클릭 → Kill Holder
        locksTable.addMouseListener(rightClickHandler { e ->
            val l = selectedLock() ?: return@rightClickHandler
            JPopupMenu().apply {
                add(JMenuItem("Kill Holder Session ${l.sid},${l.serial} (${l.username ?: "?"})").apply {
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
                text = (value as? DbDataSource)?.name ?: "(데이터소스 없음)"
                return c
            }
        }
        dsCombo.preferredSize = Dimension(220, 28)
        dsCombo.addActionListener { if (dsCombo.selectedItem != null) reloadActiveTab() }

        refreshBtn.apply {
            toolTipText = "지금 새로고침"
            isBorderPainted = false
            isContentAreaFilled = false
            cursor = Cursor(Cursor.HAND_CURSOR)
            preferredSize = Dimension(28, 28)
            addActionListener { reloadActiveTab() }
        }
        autoToggle.toolTipText = "5초마다 활성 탭 자동 새로고침"
        autoToggle.addActionListener {
            if (autoToggle.isSelected) scheduleAutoRefresh() else alarm.cancelAllRequests()
        }
        includeBg.toolTipText = "Sessions 탭에서 TYPE='BACKGROUND' 세션도 포함"
        includeBg.addActionListener { if (currentTab() == TAB_SESSIONS) reloadActiveTab() }

        val toolbar = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UIManager.getColor("Separator.foreground")),
                BorderFactory.createEmptyBorder(3, 6, 3, 6),
            )
            val left = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                isOpaque = false
                add(JBLabel("DataSource").apply { font = font.deriveFont(Font.BOLD, 11f) })
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

        // ── Sessions 탭 빌드 ──────────────────────────────────────────────────
        val sessionsTab = JPanel(BorderLayout()).apply {
            add(buildSessionFilterRow(), BorderLayout.NORTH)
            add(
                OnePixelSplitter(true, 0.6f).apply {
                    firstComponent = JBScrollPane(sessionsTable)
                    secondComponent = JPanel(BorderLayout()).apply {
                        add(JBLabel("Current SQL").apply {
                            font = font.deriveFont(Font.BOLD, 11f)
                            border = BorderFactory.createEmptyBorder(2, 6, 2, 0)
                        }, BorderLayout.NORTH)
                        add(sqlEditor.component, BorderLayout.CENTER)
                    }
                },
                BorderLayout.CENTER,
            )
        }

        // ── Locks 탭 빌드 ─────────────────────────────────────────────────────
        val locksTab = JPanel(BorderLayout()).apply {
            add(JBScrollPane(locksTable), BorderLayout.CENTER)
        }

        tabs.addTab(TAB_SESSIONS, sessionsTab)
        tabs.addTab(TAB_LOCKS, locksTab)
        tabs.addChangeListener {
            // 탭을 처음 열 때 자동 로드 (이미 로딩 중이거나 DS가 없으면 스킵)
            reloadActiveTab()
        }

        add(toolbar, BorderLayout.NORTH)
        add(tabs, BorderLayout.CENTER)

        populateDataSources()
    }

    private fun buildSessionFilterRow(): JPanel {
        val clearBtn = JButton("Clear").apply {
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
            add(filterLabel("USER")); add(filterUser)
            add(filterLabel("STATUS")); add(filterStatus)
            add(filterLabel("PROGRAM")); add(filterProgram)
            add(filterLabel("MODULE")); add(filterModule)
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
            statusLabel.text = "Oracle 데이터소스가 없습니다"
        }
    }

    private fun selectedDs(): DbDataSource? = dsCombo.selectedItem as? DbDataSource
    private fun currentTab(): String = tabs.getTitleAt(tabs.selectedIndex.coerceAtLeast(0))

    private fun reloadActiveTab() {
        when (currentTab()) {
            TAB_SESSIONS -> reloadSessions()
            TAB_LOCKS -> reloadLocks()
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
        setBusy(true, "조회 중…")

        object : Task.Backgroundable(project, "Oracle 세션 조회", true) {
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
        sessionsTable.rowSorter = sorter
        autoFitColumns(sessionsTable, newModel)
        statusLabel.text = "세션 ${list.size}건 · ${java.time.LocalTime.now().withNano(0)}"
        ApplicationManager.getApplication().runWriteAction { sqlDocument.setText("") }
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
                sqlDocument.setText("-- (이 세션의 현재 SQL_ID 없음) --")
            }
            return
        }
        val ds = selectedDs() ?: return
        object : Task.Backgroundable(project, "Current SQL 조회 (SQL_ID=$sqlId)", true) {
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
                        ?: failure?.let { "-- SQL 조회 실패: ${it.message ?: it::class.simpleName} --" }
                        ?: "-- V\$SQLAREA에서 SQL을 찾을 수 없습니다 (캐시에서 제거되었을 수 있음) --"
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
        setBusy(true, "락 조회 중…")

        object : Task.Backgroundable(project, "Oracle Lock 조회", true) {
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
        statusLabel.text = "락 ${list.size}건 · ${java.time.LocalTime.now().withNano(0)}"
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
        val msg = """
            정말 다음 세션을 KILL 하시겠습니까?

              SID      : $sid
              SERIAL#  : $serial
              USER     : ${user ?: "?"}
              MACHINE  : ${machine ?: "?"}
              PROGRAM  : ${program ?: "?"}
              STATUS   : ${status ?: "?"}

            실행 SQL:
              ALTER SYSTEM KILL SESSION '$sid,$serial' IMMEDIATE
        """.trimIndent()
        val ok = Messages.showOkCancelDialog(
            project, msg, "Kill Session", "KILL", "취소", AllIcons.General.WarningDialog,
        )
        if (ok != Messages.OK) return

        val ds = selectedDs() ?: return
        object : Task.Backgroundable(project, "KILL SESSION $sid,$serial", true) {
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
                        notifyInfo("세션 $sid,$serial KILL 명령을 보냈습니다.")
                        reloadActiveTab()
                    } else {
                        notifyError("KILL 실패: ${err.message ?: err::class.simpleName}")
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
                "V\$SESSION / V\$LOCKED_OBJECT 권한이 없습니다. DBA에게 다음 권한을 요청하세요:\n" +
                    "  GRANT SELECT ON V_\$SESSION TO <사용자>;\n" +
                    "  GRANT SELECT ON V_\$SQLAREA TO <사용자>;\n" +
                    "  GRANT SELECT ON V_\$LOCKED_OBJECT TO <사용자>;"
            "ORA-01031" in msg || "insufficient privileges" in msg ->
                "권한 부족 (ORA-01031). KILL SESSION은 ALTER SYSTEM 권한이 필요합니다."
            else -> "오류: ${msg.ifBlank { t::class.simpleName.orEmpty() }}"
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
