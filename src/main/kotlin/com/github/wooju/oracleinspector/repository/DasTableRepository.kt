package com.github.wooju.oracleinspector.repository

import com.github.wooju.oracleinspector.model.CheckInfo
import com.github.wooju.oracleinspector.model.ColumnInfo
import com.github.wooju.oracleinspector.model.ForeignKeyInfo
import com.github.wooju.oracleinspector.model.IndexInfo
import com.github.wooju.oracleinspector.model.KeyInfo
import com.github.wooju.oracleinspector.model.TableInfo
import com.github.wooju.oracleinspector.model.TriggerInfo
import com.intellij.database.model.DasColumn
import com.intellij.database.model.DasConstraint
import com.intellij.database.model.DasForeignKey
import com.intellij.database.model.DasIndex
import com.intellij.database.model.DataType
import com.intellij.database.model.ObjectKind
import com.intellij.database.psi.DbTable
import com.intellij.database.util.DasUtil

/**
 * DataGrip이 이미 로드한 DAS(Database Abstraction Schema) 모델에서 테이블 정보를 추출한다.
 * JDBC 연결 없이 즉시 데이터를 가져온다.
 */
class DasTableRepository(
    private val table: DbTable,
    private val schemaName: String,
    private val tableName: String,
) : TableMetadataRepository {

    override fun loadTable(): TableInfo {
        val keys = extractKeys()
        val pkPositions: Map<String, Int> = keys.firstOrNull()
            ?.columns
            ?.mapIndexed { idx, name -> name.uppercase() to (idx + 1) }
            ?.toMap()
            ?: emptyMap()

        val indexes = extractIndexes()
        val indexedColumns: Set<String> = indexes
            .flatMap { it.columns.map { c -> c.uppercase() } }
            .toSet()

        val columns = DasUtil.getColumns(table).map { col: DasColumn ->
            val dt = col.dataType
            val colName = col.name.uppercase()
            ColumnInfo(
                position = col.position.toInt(),
                name = col.name,
                comment = col.comment,
                dataType = dt.typeName,
                size = validSize(dt.size),
                precision = validSize(dt.precision),
                scale = validSize(dt.scale),
                isNotNull = col.isNotNull,
                default = col.default,
                pkPosition = pkPositions[colName],
                isIndexed = colName in indexedColumns,
            )
        }.toList()

        return TableInfo(
            schema = schemaName,
            name = tableName,
            comment = table.comment,
            columns = columns,
            keys = keys,
            foreignKeys = extractForeignKeys(),
            indexes = indexes,
            checks = extractChecks(),
            triggers = extractTriggers(),
            isView = table.kind == ObjectKind.VIEW,
            viewDefinition = null,  // DAS 캐시는 VIEW 본문을 갖지 않음 → JDBC 폴백에서 채움
        )
    }

    private fun extractKeys(): List<KeyInfo> =
        table.getDasChildren(ObjectKind.KEY).map { key ->
            val cols = (key as? DasConstraint)?.columnsRef?.names()?.toList() ?: emptyList()
            KeyInfo(name = key.name, type = "KEY", columns = cols)
        }.toList()

    private fun extractForeignKeys(): List<ForeignKeyInfo> =
        table.getDasChildren(ObjectKind.FOREIGN_KEY)
            .filterIsInstance<DasForeignKey>()
            .map { fk ->
                ForeignKeyInfo(
                    name = fk.name,
                    columns = fk.columnsRef.names().toList(),
                    refSchema = fk.refTableSchema,
                    refTable = fk.refTableName,
                    refColumns = fk.refColumns.names().toList(),
                    deleteRule = fk.deleteRule?.name,
                    updateRule = fk.updateRule?.name,
                )
            }.toList()

    private fun extractIndexes(): List<IndexInfo> =
        DasUtil.getIndices(table).map { idx: DasIndex ->
            IndexInfo(
                name = idx.name,
                isUnique = idx.isUnique,
                columns = idx.columnsRef.names().toList(),
            )
        }.toList()

    private fun extractChecks(): List<CheckInfo> =
        table.getDasChildren(ObjectKind.CHECK).map { chk ->
            val cols = (chk as? DasConstraint)?.columnsRef?.names()?.toList() ?: emptyList()
            CheckInfo(name = chk.name, columns = cols)
        }.toList()

    /** DAS는 트리거의 상세 속성(type/event/status)을 일관되게 노출하지 않으므로 이름만 채운다.
     *  타입·이벤트·상태는 JDBC 폴백(JdbcTableMetadataRepository)에서 풍부하게 받는다. */
    private fun extractTriggers(): List<TriggerInfo> =
        table.getDasChildren(ObjectKind.TRIGGER).map { trig ->
            TriggerInfo(
                name = trig.name,
                type = null, event = null, status = null, actionType = null,
            )
        }.toList()

    /** MAX_SIZE(2147483646), NO_SIZE(-1), 0 등 의미없는 값은 null 반환 */
    private fun validSize(v: Int): Int? =
        v.takeIf { it > 0 && it < DataType.MAX_SIZE }
}
