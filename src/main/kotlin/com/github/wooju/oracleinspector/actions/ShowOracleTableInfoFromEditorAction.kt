package com.github.wooju.oracleinspector.actions

import com.github.wooju.oracleinspector.ui.OracleTableInfoDialog
import com.intellij.database.Dbms
import com.intellij.database.model.DasTable
import com.intellij.database.model.ObjectKind
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.psi.DbTable
import com.intellij.database.view.DatabaseView
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.ui.Messages

private val LOG = logger<ShowOracleTableInfoFromEditorAction>()

class ShowOracleTableInfoFromEditorAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project  = e.project ?: return
        val editor   = e.getData(CommonDataKeys.EDITOR) ?: return
        val selected = editor.selectionModel.selectedText?.trim() ?: return
        if (selected.isBlank()) return

        val facade   = DbPsiFacade.getInstance(project)
        val dsNode   = e.getData(DatabaseView.DATABASE_RELATED_SINGLE_DATA_SOURCE)

        // 콘솔에 연결된 데이터소스 → 없으면 전체 데이터소스에서 Oracle만 대상
        val dataSources = if (dsNode != null) {
            facade.dataSources.filter { it.delegate == dsNode.localDataSource }
        } else {
            facade.dataSources.filter {
                it.getDatabaseDialect()?.getDbms() == Dbms.ORACLE
            }
        }

        // 선택된 텍스트와 이름이 일치하는 테이블 검색
        val candidates = mutableListOf<DbTable>()
        for (ds in dataSources) {
            ds.getDasChildren(ObjectKind.SCHEMA).forEach { schema ->
                schema.getDasChildren(ObjectKind.TABLE)
                    .filterIsInstance<DasTable>()
                    .filter { it.name.equals(selected, ignoreCase = true) }
                    .forEach { dasTable ->
                        val dbTable = facade.findElement(dasTable) as? DbTable
                        if (dbTable != null) candidates += dbTable
                    }
            }
        }

        LOG.info("Editor action: selected='$selected', candidates=${candidates.map { "${it.dasParent?.name}.${it.name}" }}")

        when {
            candidates.isEmpty() -> Messages.showInfoMessage(
                project,
                "'$selected' 테이블을 Oracle 데이터소스에서 찾을 수 없습니다.",
                "Oracle Dictionary Inspector"
            )
            candidates.size == 1 -> openDialog(project, candidates[0])
            else -> {
                // 동일 이름 테이블이 여러 스키마에 있으면 선택
                val items = candidates.map { "${it.dasParent?.name}.${it.name}" }.toTypedArray()
                val choice = Messages.showChooseDialog(
                    project,
                    "동일한 이름의 테이블이 여러 스키마에 있습니다.",
                    "테이블 선택",
                    null,
                    items,
                    items[0]
                ) ?: return
                openDialog(project, candidates[choice])
            }
        }
    }

    override fun update(e: AnActionEvent) {
        val editor   = e.getData(CommonDataKeys.EDITOR)
        val selected = editor?.selectionModel?.selectedText?.trim()
        e.presentation.isEnabledAndVisible = !selected.isNullOrBlank()
    }

    private fun openDialog(project: com.intellij.openapi.project.Project, table: DbTable) {
        val schemaName = table.dasParent?.name ?: "UNKNOWN"
        OracleTableInfoDialog(
            project    = project,
            table      = table,
            schemaName = schemaName,
            tableName  = table.name,
        ).show()
    }
}
