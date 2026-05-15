package com.github.wooju.oracleinspector.actions

import com.github.wooju.oracleinspector.ui.OracleRoutineInfoDialog
import com.github.wooju.oracleinspector.ui.OracleTableInfoDialog
import com.intellij.database.Dbms
import com.intellij.database.model.DasObject
import com.intellij.database.model.DasRoutine
import com.intellij.database.model.DasTable
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.psi.DbRoutine
import com.intellij.database.psi.DbTable
import com.intellij.database.view.DatabaseView
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project

private val LOG = logger<ShowOracleTableInfoAction>()

/**
 * Database 트리에서 테이블 / 프로시저 / 펑션에 대해 Oracle Dictionary Info 다이얼로그를 연다.
 * 패키지 내부 루틴은 제외 (standalone 만 지원).
 */
class ShowOracleTableInfoAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val target = resolveTarget(e, project) ?: run {
            LOG.warn("actionPerformed: 대상 객체 변환 실패")
            return
        }
        when (target) {
            is DbTable -> openTable(project, target)
            is DbRoutine -> openRoutine(project, target)
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val element = e.getData(DatabaseView.DATABASE_ELEMENTS)?.getOrNull(0)
        if (project == null || element == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        val dbms: Dbms? = when (element) {
            is DasTable -> dbmsOf(project, element)
            is DasRoutine -> if (element.packageName.isNullOrBlank()) dbmsOf(project, element) else null
            else -> null
        }
        e.presentation.isEnabledAndVisible = dbms == Dbms.ORACLE
    }

    // ── 내부 ──────────────────────────────────────────────────────────────────
    private fun openTable(project: Project, table: DbTable) {
        val schema = table.dasParent?.name ?: "UNKNOWN"
        LOG.info("Opening table dialog: $schema.${table.name}")
        OracleTableInfoDialog(project, table, schema, table.name).show()
    }

    private fun openRoutine(project: Project, routine: DbRoutine) {
        val schema = routine.dasParent?.name ?: "UNKNOWN"
        LOG.info("Opening routine dialog: $schema.${routine.name}")
        OracleRoutineInfoDialog(project, routine, schema, routine.name).show()
    }

    private fun resolveTarget(e: AnActionEvent, project: Project): Any? {
        val element = e.getData(DatabaseView.DATABASE_ELEMENTS)?.getOrNull(0) ?: return null
        val facade = DbPsiFacade.getInstance(project)
        return when (element) {
            is DasTable -> facade.findElement(element) as? DbTable
            is DasRoutine -> {
                if (!element.packageName.isNullOrBlank()) return null
                facade.findElement(element) as? DbRoutine
            }
            else -> null
        }
    }

    private fun dbmsOf(project: Project, das: DasObject): Dbms? {
        val element = DbPsiFacade.getInstance(project).findElement(das) ?: return null
        val dataSource = (element as? DbTable)?.dataSource
            ?: (element as? DbRoutine)?.dataSource
            ?: return null
        return dataSource.getDatabaseDialect()?.getDbms()
    }
}
