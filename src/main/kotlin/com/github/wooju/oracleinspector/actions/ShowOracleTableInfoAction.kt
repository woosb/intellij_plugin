package com.github.wooju.oracleinspector.actions

import com.github.wooju.oracleinspector.ui.OracleTableInfoDialog
import com.intellij.database.Dbms
import com.intellij.database.model.DasTable
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.psi.DbTable
import com.intellij.database.view.DatabaseView
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.logger

private val LOG = logger<ShowOracleTableInfoAction>()

class ShowOracleTableInfoAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val table = getSelectedDbTable(e, project) ?: run {
            LOG.warn("actionPerformed: DbTable 변환 실패")
            return
        }
        val schemaName = table.dasParent?.name ?: "UNKNOWN"
        LOG.info("Opening dialog: $schemaName.${table.name}")

        OracleTableInfoDialog(
            project = project,
            table = table,
            schemaName = schemaName,
            tableName = table.name,
        ).show()
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val elements = e.getData(DatabaseView.DATABASE_ELEMENTS)
        val dasTable = elements?.getOrNull(0) as? DasTable

        if (dasTable == null || project == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        // 모델 객체 → PSI 객체로 변환해서 dialect 확인
        val dbTable = DbPsiFacade.getInstance(project).findElement(dasTable) as? DbTable
        val dbms = dbTable?.dataSource?.getDatabaseDialect()?.getDbms()
        LOG.info("update — dasTable=${dasTable.name}, dbms=$dbms")

        e.presentation.isEnabledAndVisible = dbms == Dbms.ORACLE
    }

    private fun getSelectedDbTable(e: AnActionEvent, project: com.intellij.openapi.project.Project): DbTable? {
        val elements = e.getData(DatabaseView.DATABASE_ELEMENTS) ?: return null
        val dasTable = elements.getOrNull(0) as? DasTable ?: return null
        return DbPsiFacade.getInstance(project).findElement(dasTable) as? DbTable
    }
}
