package com.github.wooju.oracleinspector.actions

import com.github.wooju.oracleinspector.ui.OraclePackageInfoDialog
import com.github.wooju.oracleinspector.ui.OracleRoutineInfoDialog
import com.github.wooju.oracleinspector.ui.OracleTableInfoDialog
import com.intellij.database.Dbms
import com.intellij.database.model.DasObject
import com.intellij.database.model.DasRoutine
import com.intellij.database.model.DasTable
import com.intellij.database.model.ObjectKind
import com.intellij.database.psi.DbElement
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
 * Database 트리에서 선택된 객체에 대해 Oracle Dictionary Info 다이얼로그를 연다.
 *  - TABLE / VIEW                  → OracleTableInfoDialog
 *  - 표준 PROCEDURE / FUNCTION     → OracleRoutineInfoDialog
 *  - PACKAGE                       → OraclePackageInfoDialog
 *  - 패키지 내부 루틴은 제외       (current 단계 비지원)
 */
class ShowOracleTableInfoAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val element = e.getData(DatabaseView.DATABASE_ELEMENTS)?.getOrNull(0) as? DasObject ?: run {
            LOG.warn("actionPerformed: DATABASE_ELEMENTS 비어있음")
            return
        }
        val facade = DbPsiFacade.getInstance(project)

        when {
            element is DasTable -> {
                val tbl = facade.findElement(element) as? DbTable ?: return
                openTable(project, tbl)
            }
            element is DasRoutine && element.packageName.isNullOrBlank() -> {
                val rtn = facade.findElement(element) as? DbRoutine ?: return
                openRoutine(project, rtn)
            }
            element.kind == ObjectKind.PACKAGE -> {
                openPackage(project, element)
            }
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val element = e.getData(DatabaseView.DATABASE_ELEMENTS)?.getOrNull(0) as? DasObject
        if (project == null || element == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        val isSupported: Boolean = when {
            element is DasTable -> true
            element is DasRoutine && element.packageName.isNullOrBlank() -> true
            element.kind == ObjectKind.PACKAGE -> true
            else -> false
        }
        if (!isSupported) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        e.presentation.isEnabledAndVisible = dbmsOf(project, element) == Dbms.ORACLE
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

    private fun openPackage(project: Project, pkg: DasObject) {
        val schema = pkg.dasParent?.name ?: "UNKNOWN"
        val ds = (DbPsiFacade.getInstance(project).findElement(pkg) as? DbElement)?.dataSource ?: run {
            LOG.warn("openPackage: 데이터소스를 찾을 수 없음 ($schema.${pkg.name})")
            return
        }
        LOG.info("Opening package dialog: $schema.${pkg.name}")
        OraclePackageInfoDialog(project, ds, schema, pkg.name).show()
    }

    /** DAS 객체로부터 DBMS 종류 판별 — DbPsiFacade로 일반 DbElement 변환 후 dataSource. */
    private fun dbmsOf(project: Project, das: DasObject): Dbms? {
        val element = DbPsiFacade.getInstance(project).findElement(das) as? DbElement ?: return null
        return element.dataSource?.getDatabaseDialect()?.getDbms()
    }
}
