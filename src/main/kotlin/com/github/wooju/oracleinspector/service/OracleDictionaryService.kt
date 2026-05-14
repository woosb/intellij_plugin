package com.github.wooju.oracleinspector.service

import com.github.wooju.oracleinspector.ui.DictionaryTableModel
import com.intellij.database.model.DasColumn
import com.intellij.database.model.DataType
import com.intellij.database.model.DasForeignKey
import com.intellij.database.model.DasIndex
import com.intellij.database.model.ObjectKind
import com.intellij.database.psi.DbTable
import com.intellij.database.util.DasUtil

/**
 * DataGrip이 이미 로드한 DAS(Database Abstraction Schema) 모델에서 테이블 정보를 추출합니다.
 * JDBC 연결 없이 DataGrip 내부 캐시에서 즉시 데이터를 가져옵니다.
 */
object OracleDictionaryService {

    fun isOracleDataSource(table: DbTable): Boolean {
        val dbms = table.dataSource?.databaseVersion?.name ?: return false
        return dbms.contains("Oracle", ignoreCase = true)
    }

    // ── Columns (DAS 모델) ───────────────────────────────────────────────────
    fun buildColumnsModel(table: DbTable): DictionaryTableModel {
        val headers = listOf("#", "Column Name", "Comment", "PK", "Index", "Data Type", "Size", "Precision", "Scale", "Nullable", "Default")

        // PK 컬럼 → 순번 맵 (컬럼명 대문자 → PK 내 위치)
        val pkPositions: Map<String, Int> = table.getDasChildren(ObjectKind.KEY)
            .firstOrNull()
            ?.let { it as? com.intellij.database.model.DasConstraint }
            ?.columnsRef?.names()
            ?.mapIndexed { idx, name -> name.uppercase() to (idx + 1) }
            ?.toMap()
            ?: emptyMap()

        // Index에 포함된 컬럼 집합 (컬럼명 대문자)
        val indexedColumns: Set<String> = DasUtil.getIndices(table)
            .flatMap { idx -> idx.columnsRef.names().map { it.uppercase() } }
            .toSet()

        val rows = DasUtil.getColumns(table).map { col: DasColumn ->
            val dt      = col.getDataType()
            val colName = col.name.uppercase()
            listOf(
                col.position,
                col.name,
                col.comment,
                pkPositions[colName],          // PK 순번 (없으면 null → 빈칸)
                if (colName in indexedColumns) "●" else null,  // Index 포함 여부
                dt.typeName,
                validSize(dt.size),
                validSize(dt.getPrecision()),
                validSize(dt.getScale()),
                if (col.isNotNull) "N" else "Y",
                col.default,
            )
        }.toList()
        return DictionaryTableModel(headers, rows)
    }

    /** MAX_SIZE(2147483646), NO_SIZE(-1), 0 등 의미없는 값은 null 반환 */
    private fun validSize(v: Int): Int? =
        v.takeIf { it > 0 && it < DataType.MAX_SIZE }

    // ── Primary / Unique Keys (ObjectKind.KEY) ───────────────────────────────
    fun buildKeysModel(table: DbTable): DictionaryTableModel {
        val headers = listOf("Key Name", "Type", "Columns")
        val rows = table.getDasChildren(ObjectKind.KEY).map { key ->
            val colNames = if (key is com.intellij.database.model.DasConstraint)
                key.columnsRef.names().joinToString(", ")
            else ""
            listOf(key.name, "KEY", colNames)
        }.toList()
        return DictionaryTableModel(headers, rows)
    }

    // ── Foreign Keys (DasForeignKey) ─────────────────────────────────────────
    fun buildForeignKeysModel(table: DbTable): DictionaryTableModel {
        val headers = listOf("FK Name", "Columns", "Ref Table", "Ref Schema", "Delete Rule", "Update Rule")
        val rows = table.getDasChildren(ObjectKind.FOREIGN_KEY)
            .filterIsInstance<DasForeignKey>()
            .map { fk ->
                listOf(
                    fk.name,
                    fk.columnsRef.names().joinToString(", "),
                    fk.refTableName,
                    fk.refTableSchema,
                    fk.deleteRule?.name,
                    fk.updateRule?.name,
                )
            }.toList()
        return DictionaryTableModel(headers, rows)
    }

    // ── Indexes (DasUtil.getIndices) ─────────────────────────────────────────
    fun buildIndexesModel(table: DbTable): DictionaryTableModel {
        val headers = listOf("Index Name", "Unique", "Columns")
        val rows = DasUtil.getIndices(table).map { idx: DasIndex ->
            listOf(
                idx.name,
                if (idx.isUnique) "YES" else "NO",
                idx.columnsRef.names().joinToString(", "),
            )
        }.toList()
        return DictionaryTableModel(headers, rows)
    }

    // ── Check Constraints ────────────────────────────────────────────────────
    fun buildChecksModel(table: DbTable): DictionaryTableModel {
        val headers = listOf("Check Name", "Columns")
        val rows = table.getDasChildren(ObjectKind.CHECK).map { chk ->
            val colNames = if (chk is com.intellij.database.model.DasConstraint)
                chk.columnsRef.names().joinToString(", ")
            else ""
            listOf(chk.name, colNames)
        }.toList()
        return DictionaryTableModel(headers, rows)
    }

    // ── DDL 생성 (DAS 모델 기반) ─────────────────────────────────────────────
    fun buildDdl(table: DbTable, schemaName: String, tableName: String): String {
        val sb = StringBuilder()
        val columns   = DasUtil.getColumns(table).toList()
        val keys      = table.getDasChildren(ObjectKind.KEY).toList()
        val fks       = table.getDasChildren(ObjectKind.FOREIGN_KEY).filterIsInstance<DasForeignKey>().toList()
        val checks    = table.getDasChildren(ObjectKind.CHECK).toList()

        sb.append("CREATE TABLE ${schemaName.uppercase()}.${tableName.uppercase()} (\n")

        val parts = mutableListOf<String>()

        // 컬럼 정의
        for (col in columns) {
            val dt = col.getDataType()
            val typePart = buildString {
                append(dt.typeName)
                val precision = validSize(dt.getPrecision())
                val scale     = validSize(dt.getScale())
                val size      = validSize(dt.size)
                when {
                    precision != null && scale != null -> append("($precision,$scale)")
                    precision != null                  -> append("($precision)")
                    size != null                       -> append("($size)")
                }
            }
            val notNull  = if (col.isNotNull) " NOT NULL" else ""
            val default  = col.default?.let { " DEFAULT $it" } ?: ""
            parts += "    ${col.name.padEnd(30)} $typePart$default$notNull"
        }

        // PK / UK 제약
        for (key in keys) {
            val constraint = key as? com.intellij.database.model.DasConstraint ?: continue
            val cols = constraint.columnsRef.names().joinToString(", ")
            parts += "    CONSTRAINT ${key.name} PRIMARY KEY ($cols)"
        }

        // FK 제약
        for (fk in fks) {
            val cols    = fk.columnsRef.names().joinToString(", ")
            val refSchema = fk.refTableSchema?.let { "$it." } ?: ""
            val refCols = fk.refColumns.names().joinToString(", ")
            val onDelete = fk.deleteRule?.name?.let { if (it != "NO_ACTION") " ON DELETE $it" else "" } ?: ""
            parts += "    CONSTRAINT ${fk.name} FOREIGN KEY ($cols)\n        REFERENCES $refSchema${fk.refTableName} ($refCols)$onDelete"
        }

        // CHECK 제약
        for (chk in checks) {
            val constraint = chk as? com.intellij.database.model.DasConstraint ?: continue
            val cols = constraint.columnsRef.names().joinToString(", ")
            if (cols.isNotBlank()) {
                parts += "    CONSTRAINT ${chk.name} CHECK ($cols)"
            }
        }

        sb.append(parts.joinToString(",\n"))
        sb.append("\n);")

        // 테이블 코멘트
        table.comment?.takeIf { it.isNotBlank() }?.let {
            sb.append("\n\nCOMMENT ON TABLE ${schemaName.uppercase()}.${tableName.uppercase()} IS '$it';")
        }

        // 컬럼 코멘트
        for (col in columns) {
            col.comment?.takeIf { it.isNotBlank() }?.let {
                sb.append("\nCOMMENT ON COLUMN ${schemaName.uppercase()}.${tableName.uppercase()}.${col.name} IS '$it';")
            }
        }

        return sb.toString()
    }

    // ── SELECT 쿼리 생성 ─────────────────────────────────────────────────────
    fun buildSelectQuery(table: DbTable, schemaName: String, tableName: String): String {
        val columns = DasUtil.getColumns(table).map { it.name }.toList()
        val colLines = columns.mapIndexed { i, name ->
            val comma = if (i < columns.lastIndex) "," else " "
            "      $name$comma"
        }.joinToString("\n")
        return "SELECT\n$colLines\nFROM ${schemaName.uppercase()}.${tableName.uppercase()};"
    }

    // ── Oracle 전용 SQL (JDBC 연동 Phase 2) ──────────────────────────────────

    fun commentsQuery(owner: String, tableName: String) = """
        SELECT COLUMN_NAME, COMMENTS
        FROM ALL_COL_COMMENTS
        WHERE OWNER = '${owner.uppercase()}'
          AND TABLE_NAME = '${tableName.uppercase()}'
          AND COMMENTS IS NOT NULL
        ORDER BY COLUMN_NAME
    """.trimIndent()

    fun triggersQuery(owner: String, tableName: String) = """
        SELECT TRIGGER_NAME, TRIGGER_TYPE, TRIGGERING_EVENT, STATUS, ACTION_TYPE
        FROM ALL_TRIGGERS
        WHERE OWNER = '${owner.uppercase()}'
          AND TABLE_NAME = '${tableName.uppercase()}'
        ORDER BY TRIGGER_NAME
    """.trimIndent()
}
