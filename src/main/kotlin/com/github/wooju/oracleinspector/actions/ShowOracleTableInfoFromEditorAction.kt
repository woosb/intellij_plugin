package com.github.wooju.oracleinspector.actions

import com.github.wooju.oracleinspector.ui.OracleRoutineInfoDialog
import com.github.wooju.oracleinspector.ui.OracleTableInfoDialog
import com.intellij.database.Dbms
import com.intellij.database.model.DasObject
import com.intellij.database.model.DasRoutine
import com.intellij.database.model.DasTable
import com.intellij.database.model.ObjectKind
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

private val LOG = logger<ShowOracleTableInfoFromEditorAction>()

/**
 * 에디터에서 선택한 텍스트를 이름으로 보고 Oracle 데이터소스의 테이블 / 표준 루틴을 찾아 다이얼로그를 연다.
 * 패키지 내부 루틴은 검색 대상에서 제외한다.
 */
class ShowOracleTableInfoFromEditorAction : AnAction() {

    private sealed class Candidate(val schema: String, val name: String) {
        class Tab(val table: DbTable, schema: String, name: String) : Candidate(schema, name)
        class Rtn(val routine: DbRoutine, schema: String, name: String, val kind: String) : Candidate(schema, name)
        fun display(): String = "$schema.$name${typeSuffix()}"
        private fun typeSuffix(): String = when (this) {
            is Tab -> "  (TABLE)"
            is Rtn -> "  ($kind)"
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val selected = resolveTargetWord(editor) ?: return

        val facade = DbPsiFacade.getInstance(project)
        val dsNode = e.getData(DatabaseView.DATABASE_RELATED_SINGLE_DATA_SOURCE)
        val dataSources: List<DbDataSource> = if (dsNode != null) {
            facade.dataSources.filter { it.delegate == dsNode.localDataSource }
        } else {
            facade.dataSources.filter { it.getDatabaseDialect()?.getDbms() == Dbms.ORACLE }
        }

        val candidates = collectCandidates(facade, dataSources, selected)
        LOG.info("Editor action: selected='$selected', candidates=${candidates.map { it.display() }}")

        when {
            candidates.isEmpty() -> Messages.showInfoMessage(
                project,
                "'$selected' 객체를 Oracle 데이터소스에서 찾을 수 없습니다.",
                "Oracle Dictionary Inspector",
            )
            candidates.size == 1 -> openDialog(project, candidates[0])
            else -> {
                val items = candidates.map { it.display() }.toTypedArray()
                val choice = Messages.showChooseDialog(
                    project,
                    "동일한 이름의 객체가 여러 곳에 있습니다.",
                    "객체 선택",
                    null,
                    items,
                    items[0],
                ) ?: return
                openDialog(project, candidates[choice])
            }
        }
    }

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = editor != null && resolveTargetWord(editor) != null
    }

    /**
     * 선택 텍스트가 있으면 그걸 사용하고, 없으면 캐럿 위치 식별자를 사용한다.
     * Oracle 식별자 문자: 영문자, 숫자, _, #, $
     */
    private fun resolveTargetWord(editor: Editor): String? {
        editor.selectionModel.selectedText?.trim()?.takeIf { it.isNotBlank() }?.let { return it }

        val text = editor.document.charsSequence
        val offset = editor.caretModel.offset.coerceIn(0, text.length)
        var start = offset
        var end = offset
        while (start > 0 && isIdentifierChar(text[start - 1])) start--
        while (end < text.length && isIdentifierChar(text[end])) end++
        if (start == end) return null
        return text.subSequence(start, end).toString()
    }

    private fun isIdentifierChar(c: Char): Boolean =
        c.isLetterOrDigit() || c == '_' || c == '#' || c == '$'

    // ── 내부 ──────────────────────────────────────────────────────────────────
    private fun collectCandidates(
        facade: DbPsiFacade,
        dataSources: List<DbDataSource>,
        selected: String,
    ): List<Candidate> {
        val out = mutableListOf<Candidate>()
        for (ds in dataSources) {
            ds.getDasChildren(ObjectKind.SCHEMA).forEach { schema ->
                // Tables
                schema.getDasChildren(ObjectKind.TABLE)
                    .filterIsInstance<DasTable>()
                    .filter { it.name.equals(selected, ignoreCase = true) }
                    .forEach { das ->
                        val psi = facade.findElement(das) as? DbTable
                        if (psi != null) out += Candidate.Tab(psi, schema.name, das.name)
                    }
                // Standalone routines (패키지 내부는 제외)
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
            }
        }
        return out
    }

    private fun openDialog(project: Project, c: Candidate) {
        when (c) {
            is Candidate.Tab -> OracleTableInfoDialog(project, c.table, c.schema, c.name).show()
            is Candidate.Rtn -> OracleRoutineInfoDialog(project, c.routine, c.schema, c.name).show()
        }
    }
}
