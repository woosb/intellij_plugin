package com.github.wooju.oracleinspector.actions

import com.github.wooju.oracleinspector.OracleInspectorBundle
import com.github.wooju.oracleinspector.ui.OraclePackageInfoDialog
import com.github.wooju.oracleinspector.ui.OracleRoutineInfoDialog
import com.github.wooju.oracleinspector.ui.OracleSequenceInfoDialog
import com.github.wooju.oracleinspector.ui.OracleSynonymInfoDialog
import com.github.wooju.oracleinspector.ui.OracleTableInfoDialog
import com.intellij.database.Dbms
import com.intellij.database.model.DasObject
import com.intellij.database.model.DasRoutine
import com.intellij.database.model.DasTable
import com.intellij.database.model.ObjectKind
import com.intellij.database.util.DbImplUtil
import com.intellij.database.psi.DbDataSource
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.psi.DbRoutine
import com.intellij.database.psi.DbTable
import com.intellij.database.view.DatabaseView
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.JBLabel
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JPanel
import javax.swing.UIManager

private val LOG = logger<ShowOracleTableInfoFromEditorAction>()

/**
 * 에디터에서 선택한 텍스트(또는 캐럿 위치 식별자)를 Oracle 객체로 보고 적절한 뷰를 연다.
 *  - TABLE / VIEW            → OracleTableInfoDialog
 *  - PROCEDURE / FUNCTION    → OracleRoutineInfoDialog
 *  - PACKAGE / SEQUENCE /
 *    SYNONYM / 그 외 종류    → 가벼운 JBPopup (전용 다이얼로그 없음)
 *
 * 검색 우선순위:
 *  1) "SCHEMA.NAME"처럼 스키마가 명시되면 그 OWNER 만 사용
 *  2) 다이얼로그 안에서 호출되어 currentOwner 컨텍스트가 있으면 그 OWNER 우선
 *  3) 그래도 못 찾으면 모든 스키마를 후보로 두고 동명일 경우 선택 다이얼로그
 */
class ShowOracleTableInfoFromEditorAction : AnAction() {

    private sealed class Candidate(val schema: String, val name: String, val kind: String) {
        class Tab(val table: DbTable, schema: String, name: String, kind: String) :
            Candidate(schema, name, kind)
        class Rtn(val routine: DbRoutine, schema: String, name: String, kind: String) :
            Candidate(schema, name, kind)
        /** PACKAGE — 전용 다이얼로그 있음 (Spec/Body/Routines/Errors). */
        class Pkg(val ds: DbDataSource, schema: String, name: String) :
            Candidate(schema, name, "PACKAGE")
        /** SEQUENCE — 경량 Property/Value 다이얼로그. */
        class Seq(val ds: DbDataSource, schema: String, name: String) :
            Candidate(schema, name, "SEQUENCE")
        /** SYNONYM — 경량 Property/Value 다이얼로그. */
        class Syn(val ds: DbDataSource, schema: String, name: String) :
            Candidate(schema, name, "SYNONYM")
        /** 그 외 종류 (현재 시점에 없음 — 미래 확장용) JBPopup 폴백. */
        class Meta(schema: String, name: String, kind: String) : Candidate(schema, name, kind)

        fun display(): String = "$schema.$name  ($kind)"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val raw = resolveTargetWord(editor) ?: return

        val (explicitOwner, baseName) = parseQualified(raw)
        val currentOwner = e.getData(OracleInspectorDataKeys.CURRENT_OWNER)?.uppercase()
        val currentDs = e.getData(OracleInspectorDataKeys.CURRENT_DATA_SOURCE)

        val facade = DbPsiFacade.getInstance(project)
        val dsNode = e.getData(DatabaseView.DATABASE_RELATED_SINGLE_DATA_SOURCE)
        val dataSources: List<DbDataSource> = when {
            currentDs != null -> listOf(currentDs)
            dsNode != null -> facade.dataSources.filter { DbImplUtil.getMaybeLocalDataSource(it) == dsNode.localDataSource }
            else -> facade.dataSources.filter { it.getDatabaseDialect()?.getDbms() == Dbms.ORACLE }
        }

        var candidates = collectCandidates(facade, dataSources, baseName)
        if (explicitOwner != null) {
            candidates = candidates.filter { it.schema.equals(explicitOwner, ignoreCase = true) }
        }
        LOG.info(
            "Editor action: input='$raw', owner='${explicitOwner ?: currentOwner ?: "-"}', " +
                "candidates=${candidates.map { it.display() }}"
        )

        when {
            candidates.isEmpty() -> Messages.showInfoMessage(
                project,
                OracleInspectorBundle.message("action.object.not.found", raw),
                OracleInspectorBundle.message("notification.group"),
            )
            candidates.size == 1 -> openOrPopup(project, editor, candidates[0])
            else -> {
                // 현재 OWNER 또는 explicit OWNER 매칭이 정확히 하나면 그것을 자동 선택
                val preferred = currentOwner ?: explicitOwner?.uppercase()
                val pref = if (preferred != null) {
                    candidates.filter { it.schema.equals(preferred, ignoreCase = true) }
                } else emptyList()
                if (pref.size == 1) {
                    openOrPopup(project, editor, pref[0])
                    return
                }
                val ordered = candidates.sortedBy {
                    if (preferred != null && it.schema.equals(preferred, ignoreCase = true)) 0 else 1
                }
                val items = ordered.map { it.display() }.toTypedArray()
                val choice = Messages.showChooseDialog(
                    project,
                    OracleInspectorBundle.message("action.dialog.message.multiple.matches"),
                    OracleInspectorBundle.message("action.dialog.title.choose.object"),
                    null,
                    items,
                    items[0],
                )
                if (choice >= 0) openOrPopup(project, editor, ordered[choice])
            }
        }
    }

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = editor != null && resolveTargetWord(editor) != null
    }

    /** SCHEMA.NAME 형식이면 (SCHEMA, NAME)로 분리, 아니면 (null, raw). */
    private fun parseQualified(raw: String): Pair<String?, String> {
        val dot = raw.indexOf('.')
        if (dot <= 0 || dot == raw.length - 1) return null to raw.trim('"', ' ')
        val owner = raw.substring(0, dot).trim('"', ' ')
        val name = raw.substring(dot + 1).trim('"', ' ')
        return owner to name
    }

    /**
     * 선택 텍스트가 있으면 그걸 사용하고, 없으면 캐럿 위치 식별자(SCHEMA.NAME 포함)를 추출.
     * Oracle 식별자: 영문자·숫자·_·#·$ + 한 단계의 '.' (qualifier).
     */
    private fun resolveTargetWord(editor: Editor): String? {
        editor.selectionModel.selectedText?.trim()?.takeIf { it.isNotBlank() }?.let { return it }

        val text = editor.document.charsSequence
        val offset = editor.caretModel.offset.coerceIn(0, text.length)
        var start = offset
        var end = offset
        while (start > 0 && isIdentifierOrDot(text[start - 1])) start--
        while (end < text.length && isIdentifierOrDot(text[end])) end++
        if (start == end) return null
        return text.subSequence(start, end).toString()
    }

    private fun isIdentifierOrDot(c: Char): Boolean =
        c.isLetterOrDigit() || c == '_' || c == '#' || c == '$' || c == '.'

    // ── 검색 ──────────────────────────────────────────────────────────────────
    private fun collectCandidates(
        facade: DbPsiFacade,
        dataSources: List<DbDataSource>,
        selected: String,
    ): List<Candidate> {
        val out = mutableListOf<Candidate>()
        for (ds in dataSources) {
            ds.getDasChildren(ObjectKind.SCHEMA).forEach { schema ->
                // TABLE
                schema.getDasChildren(ObjectKind.TABLE)
                    .filterIsInstance<DasTable>()
                    .filter { it.name.equals(selected, ignoreCase = true) }
                    .forEach { das ->
                        val psi = facade.findElement(das) as? DbTable
                        if (psi != null) out += Candidate.Tab(psi, schema.name, das.name, "TABLE")
                    }
                // VIEW (IntelliJ DB 모델에서도 DbTable 캐스팅 가능)
                schema.getDasChildren(ObjectKind.VIEW)
                    .filterIsInstance<DasTable>()
                    .filter { it.name.equals(selected, ignoreCase = true) }
                    .forEach { das ->
                        val psi = facade.findElement(das) as? DbTable
                        if (psi != null) out += Candidate.Tab(psi, schema.name, das.name, "VIEW")
                    }
                // 표준 ROUTINE (패키지 내부 제외)
                schema.getDasChildren(ObjectKind.ROUTINE)
                    .filterIsInstance<DasRoutine>()
                    .filter { it.name.equals(selected, ignoreCase = true) && it.packageName.isNullOrBlank() }
                    .forEach { das ->
                        val psi = facade.findElement(das as DasObject) as? DbRoutine
                        if (psi != null) {
                            val kind = when (das.routineKind) {
                                DasRoutine.Kind.FUNCTION -> "FUNCTION"
                                DasRoutine.Kind.PROCEDURE -> "PROCEDURE"
                                else -> "ROUTINE"
                            }
                            out += Candidate.Rtn(psi, schema.name, das.name, kind)
                        }
                    }
                // PACKAGE — 전용 다이얼로그
                try {
                    schema.getDasChildren(ObjectKind.PACKAGE)
                        .filter { it.name.equals(selected, ignoreCase = true) }
                        .forEach { das -> out += Candidate.Pkg(ds, schema.name, das.name) }
                } catch (t: Throwable) {
                    LOG.debug("PACKAGE 수집 실패 — 무시: ${t.message}")
                }
                // SEQUENCE / SYNONYM — 경량 전용 다이얼로그
                try {
                    schema.getDasChildren(ObjectKind.SEQUENCE)
                        .filter { it.name.equals(selected, ignoreCase = true) }
                        .forEach { das -> out += Candidate.Seq(ds, schema.name, das.name) }
                } catch (t: Throwable) {
                    LOG.debug("SEQUENCE 수집 실패 — 무시: ${t.message}")
                }
                try {
                    schema.getDasChildren(ObjectKind.SYNONYM)
                        .filter { it.name.equals(selected, ignoreCase = true) }
                        .forEach { das -> out += Candidate.Syn(ds, schema.name, das.name) }
                } catch (t: Throwable) {
                    LOG.debug("SYNONYM 수집 실패 — 무시: ${t.message}")
                }
            }
        }
        return out
    }

    private fun addMetaCandidates(
        schema: DasObject,
        kind: ObjectKind,
        kindLabel: String,
        selected: String,
        out: MutableList<Candidate>,
    ) {
        try {
            schema.getDasChildren(kind)
                .filter { it.name.equals(selected, ignoreCase = true) }
                .forEach { das -> out += Candidate.Meta(schema.name, das.name, kindLabel) }
        } catch (t: Throwable) {
            LOG.debug("addMetaCandidates($kind) 실패 — 무시: ${t.message}")
        }
    }

    // ── 결과 처리 ─────────────────────────────────────────────────────────────
    private fun openOrPopup(project: Project, editor: Editor, c: Candidate) {
        when (c) {
            is Candidate.Tab -> OracleTableInfoDialog(project, c.table, c.schema, c.name).show()
            is Candidate.Rtn -> OracleRoutineInfoDialog(project, c.routine, c.schema, c.name).show()
            is Candidate.Pkg -> OraclePackageInfoDialog(project, c.ds, c.schema, c.name).show()
            is Candidate.Seq -> OracleSequenceInfoDialog(project, c.ds, c.schema, c.name).show()
            is Candidate.Syn -> OracleSynonymInfoDialog(project, c.ds, c.schema, c.name).show()
            is Candidate.Meta -> showMetaPopup(editor, c)
        }
    }

    private fun showMetaPopup(editor: Editor, c: Candidate) {
        val html = buildString {
            append("<html><div style='padding:6px 4px;'>")
            append("<div style='font-size:11pt;'><b>").append(c.schema).append('.').append(c.name).append("</b></div>")
            append("<div style='color:gray;margin-top:2px;'>").append(c.kind).append("</div>")
            append("<div style='margin-top:8px;'>").append(OracleInspectorBundle.message("action.popup.meta.note")).append("</div>")
            append("</div></html>")
        }
        val label = JBLabel(html).apply {
            font = font.deriveFont(Font.PLAIN, 12f)
            border = BorderFactory.createEmptyBorder(8, 12, 8, 12)
        }
        val panel = JPanel(BorderLayout()).apply {
            background = UIManager.getColor("ToolTip.background") ?: background
            add(label, BorderLayout.CENTER)
        }
        JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, label)
            .setRequestFocus(false)
            .setResizable(false)
            .setMovable(true)
            .setTitle("${c.schema}.${c.name}")
            .createPopup()
            .showInBestPositionFor(editor)
    }
}
