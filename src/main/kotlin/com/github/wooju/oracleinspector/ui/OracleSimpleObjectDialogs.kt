package com.github.wooju.oracleinspector.ui

import com.github.wooju.oracleinspector.OracleInspectorBundle
import com.github.wooju.oracleinspector.cache.OracleMetadataCache
import com.github.wooju.oracleinspector.model.SequenceInfo
import com.github.wooju.oracleinspector.model.SynonymInfo
import com.github.wooju.oracleinspector.repository.JdbcObjectChangeRepository
import com.github.wooju.oracleinspector.repository.JdbcSimpleObjectRepository
import com.intellij.database.psi.DbDataSource
import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.UIManager
import javax.swing.table.DefaultTableCellRenderer

/**
 * 단일 행 객체(SEQUENCE / SYNONYM)용 경량 다이얼로그.
 * UI 패턴: 상단에 객체 헤더(스키마.이름 + 종류 + 새로고침), 본문에 Property/Value 테이블 한 개.
 */
abstract class SimpleObjectDialog(
    project: Project,
    schemaName: String,
    objectName: String,
    private val kindLabel: String,
    private val dataSource: DbDataSource,
    private val objectTypes: List<String>,
) : DialogWrapper(project) {

    protected val project_ = project
    protected val schema = schemaName
    protected val name = objectName
    protected val tableModel: DictionaryTableModel = DictionaryTableModel.empty(listOf("Property", "Value"))
    protected val tbl: JBTable = JBTable(tableModel)

    @Volatile private var stale: Boolean = false
    private lateinit var refreshBtn: JButton
    private val cache = OracleMetadataCache.getInstance(project)
    private val cacheKey = OracleMetadataCache.key(dataSource.uniqueId, schemaName, objectName, kindLabel)

    private val statusLabel = JBLabel("").apply {
        font = font.deriveFont(11f)
        foreground = UIManager.getColor("Label.disabledForeground")
        border = BorderFactory.createEmptyBorder(0, 0, 0, 8)
    }

    init {
        title = "$schemaName.$objectName"
        isModal = false
        tbl.apply {
            setShowGrid(false)
            intercellSpacing = Dimension(0, 0)
            rowHeight = 24
            autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
            tableHeader.reorderingAllowed = false
            setDefaultRenderer(Any::class.java, PropertyCellRenderer())
        }
        TableSearchSupport.install(tbl)
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout())
        root.preferredSize = Dimension(520, 360)
        root.add(buildHeader(), BorderLayout.NORTH)
        root.add(JBScrollPane(tbl), BorderLayout.CENTER)
        return root
    }

    private fun buildHeader(): JComponent {
        val kind = JBLabel(kindLabel).apply {
            font = font.deriveFont(Font.ITALIC, 12f)
            foreground = UIManager.getColor("Label.disabledForeground")
        }
        refreshBtn = JButton(AllIcons.Actions.Refresh).apply {
            toolTipText = OracleInspectorBundle.message("dialog.tooltip.refresh.from.db")
            isBorderPainted = false
            isContentAreaFilled = false
            cursor = Cursor(Cursor.HAND_CURSOR)
            preferredSize = Dimension(28, 28)
            addActionListener { reload() }
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
            add(kind, BorderLayout.CENTER)
            add(right, BorderLayout.EAST)
        }
    }

    /** Repository 호출 결과를 Property/Value 리스트로 반환. null이면 "찾을 수 없음" 안내. */
    protected abstract fun fetchRows(): List<Pair<String, String?>>?

    /** 서브클래스가 init() 직후 호출 — 캐시 우선, 없으면 JDBC reload. */
    @Suppress("UNCHECKED_CAST")
    protected fun loadInitial() {
        val cached = cache.get(cacheKey)?.dto as? List<Pair<String, String?>>
        if (cached != null) {
            applyRows(cached)            // 즉시 표시 (JDBC 스킵)
            scheduleStaleCheck()         // DB 변경만 백그라운드 검증
        } else {
            reload()
        }
    }

    protected fun reload() {
        statusLabel.text = OracleInspectorBundle.message("common.loading")
        object : Task.Backgroundable(project_, OracleInspectorBundle.message("simple.task.title", schema, name), true) {
            private var rows: List<Pair<String, String?>>? = null
            private var fetchedDdl: String? = null
            private var failure: Throwable? = null
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                try {
                    rows = fetchRows()
                    fetchedDdl = JdbcObjectChangeRepository(project_, dataSource)
                        .loadLastDdlTime(schema, name, objectTypes)
                } catch (t: Throwable) {
                    failure = t
                }
            }
            override fun onFinished() {
                ApplicationManager.getApplication().invokeLater {
                    stale = false
                    setStatusNormal("")
                    val ok = rows
                    val err = failure
                    when {
                        ok != null -> {
                            applyRows(ok)
                            cache.put(cacheKey, ok, fetchedDdl)  // 성공 결과만 캐시
                        }
                        err != null -> {
                            applyRows(emptyList())
                            notifyError(OracleInspectorBundle.message("common.query.failed", err.message ?: err::class.simpleName.orEmpty()))
                        }
                        else -> applyRows(emptyList())
                    }
                }
            }
        }.queue()
    }

    private fun scheduleStaleCheck() {
        val baseline = cache.get(cacheKey)?.lastDdlTime ?: return
        object : Task.Backgroundable(project_, OracleInspectorBundle.message("dialog.tooltip.refresh.from.db"), true) {
            private var current: String? = null
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                current = JdbcObjectChangeRepository(project_, dataSource).loadLastDdlTime(schema, name, objectTypes)
            }
            override fun onFinished() {
                ApplicationManager.getApplication().invokeLater {
                    if (current != null && current != baseline) {
                        stale = true
                        refreshBtn.icon = AllIcons.Actions.ForceRefresh
                        refreshBtn.toolTipText = OracleInspectorBundle.message("cache.stale.tooltip")
                        statusLabel.text = OracleInspectorBundle.message("cache.stale.notice")
                        statusLabel.foreground = STALE_FG
                    }
                }
            }
        }.queue()
    }

    private fun setStatusNormal(message: String) {
        refreshBtn.icon = AllIcons.Actions.Refresh
        refreshBtn.toolTipText = OracleInspectorBundle.message("dialog.tooltip.refresh.from.db")
        statusLabel.text = message
        statusLabel.foreground = UIManager.getColor("Label.disabledForeground")
    }

    private fun applyRows(rows: List<Pair<String, String?>>) {
        val data = if (rows.isEmpty())
            listOf(listOf<Any?>("(not found)", ""))
        else
            rows.map { (k, v) -> listOf<Any?>(k, v) }
        val newModel = DictionaryTableModel(listOf("Property", "Value"), data)
        tbl.model = newModel
    }

    private fun notifyError(text: String) {
        val group = NotificationGroupManager.getInstance().getNotificationGroup("Oracle Dictionary Inspector")
        group?.createNotification(text, NotificationType.WARNING)?.notify(project_)
    }

    override fun createActions() = arrayOf(okAction)

    private class PropertyCellRenderer : DefaultTableCellRenderer() {
        // Live getters so colours follow the current IDE theme on the fly.
        private val evenBg: Color? get() = UIManager.getColor("Table.background")
        private val oddBg: Color? get() = UIManager.getColor("Table.stripeColor")
            ?: evenBg?.let { Color(it.red, it.green, it.blue, 220) }

        override fun getTableCellRendererComponent(
            tbl: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
        ): Component {
            super.getTableCellRendererComponent(tbl, value, isSelected, hasFocus, row, column)
            if (!isSelected) background = if (row % 2 == 0) evenBg else oddBg
            if (column == 0) {
                font = font.deriveFont(Font.BOLD)
                foreground = UIManager.getColor("Label.foreground")
            } else {
                font = font.deriveFont(Font.PLAIN)
                foreground = UIManager.getColor("Label.foreground")
            }
            border = BorderFactory.createEmptyBorder(0, 8, 0, 8)
            return this
        }
    }

    companion object {
        /** stale 안내 색 — 테마 대응 amber. */
        private val STALE_FG = JBColor(Color(0xB8, 0x6B, 0x00), Color(0xE0, 0xA8, 0x3A))
    }
}

/** SEQUENCE 다이얼로그 — MIN/MAX/INCREMENT/CYCLE/ORDER/CACHE/LAST_NUMBER */
class OracleSequenceInfoDialog(
    project: Project,
    private val dataSource: DbDataSource,
    schemaName: String,
    sequenceName: String,
) : SimpleObjectDialog(project, schemaName, sequenceName, "SEQUENCE", dataSource, listOf("SEQUENCE")) {

    init {
        init()
        loadInitial()
    }

    override fun fetchRows(): List<Pair<String, String?>>? {
        val info = JdbcSimpleObjectRepository(project_, dataSource).loadSequence(schema, name) ?: return null
        return seqRows(info)
    }

    private fun seqRows(s: SequenceInfo): List<Pair<String, String?>> = listOf(
        "Owner"        to s.schema,
        "Name"         to s.name,
        "Min Value"    to s.minValue,
        "Max Value"    to s.maxValue,
        "Increment By" to s.incrementBy,
        "Cycle"        to if (s.cycle) "YES" else "NO",
        "Order"        to if (s.ordered) "YES" else "NO",
        "Cache Size"   to s.cacheSize?.toString(),
        "Last Number"  to s.lastNumber,
    )
}

/** SYNONYM 다이얼로그 — referenced owner/name/db_link. */
class OracleSynonymInfoDialog(
    project: Project,
    private val dataSource: DbDataSource,
    schemaName: String,
    synonymName: String,
) : SimpleObjectDialog(project, schemaName, synonymName, "SYNONYM", dataSource, listOf("SYNONYM")) {

    init {
        init()
        loadInitial()
    }

    override fun fetchRows(): List<Pair<String, String?>>? {
        val info = JdbcSimpleObjectRepository(project_, dataSource).loadSynonym(schema, name) ?: return null
        return synRows(info)
    }

    private fun synRows(s: SynonymInfo): List<Pair<String, String?>> = listOfNotNull(
        "Owner"      to s.schema,
        "Name"       to s.name,
        "References" to listOfNotNull(s.refOwner, s.refName).joinToString(".").ifEmpty { null },
        s.dbLink?.let { "DB Link" to it },
    )
}
