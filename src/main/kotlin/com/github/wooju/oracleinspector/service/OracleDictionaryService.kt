package com.github.wooju.oracleinspector.service

import com.github.wooju.oracleinspector.model.ColumnInfo
import com.github.wooju.oracleinspector.model.TableInfo
import com.github.wooju.oracleinspector.ui.DictionaryTableModel
import com.intellij.database.psi.DbTable

/**
 * TableInfo DTO를 받아 UI에 바인딩할 모델(DictionaryTableModel)과 SQL 문자열을 만든다.
 * 데이터 출처(캐시/JDBC)는 Repository 계층에서 결정되며 이 서비스는 포맷팅만 담당한다.
 */
object OracleDictionaryService {

    fun isOracleDataSource(table: DbTable): Boolean {
        val dbms = table.dataSource?.databaseVersion?.name ?: return false
        return dbms.contains("Oracle", ignoreCase = true)
    }

    // ── Columns ──────────────────────────────────────────────────────────────
    fun buildColumnsModel(info: TableInfo): DictionaryTableModel {
        val headers = listOf("#", "Column Name", "Comment", "PK", "Index", "Data Type", "Size", "Precision", "Scale", "Nullable", "Default")
        val rows = info.columns.map { col ->
            listOf(
                col.position,
                col.name,
                col.comment,
                col.pkPosition,
                if (col.isIndexed) "●" else null,
                col.dataType,
                col.size,
                col.precision,
                col.scale,
                if (col.isNotNull) "N" else "Y",
                col.default,
            )
        }
        return DictionaryTableModel(headers, rows)
    }

    // ── Primary / Unique Keys ────────────────────────────────────────────────
    fun buildKeysModel(info: TableInfo): DictionaryTableModel {
        val headers = listOf("Key Name", "Type", "Columns")
        val rows = info.keys.map { key ->
            listOf(key.name, key.type, key.columns.joinToString(", "))
        }
        return DictionaryTableModel(headers, rows)
    }

    // ── Foreign Keys ─────────────────────────────────────────────────────────
    fun buildForeignKeysModel(info: TableInfo): DictionaryTableModel {
        val headers = listOf("FK Name", "Columns", "Ref Table", "Ref Schema", "Delete Rule", "Update Rule")
        val rows = info.foreignKeys.map { fk ->
            listOf(
                fk.name,
                fk.columns.joinToString(", "),
                fk.refTable,
                fk.refSchema,
                fk.deleteRule,
                fk.updateRule,
            )
        }
        return DictionaryTableModel(headers, rows)
    }

    // ── Indexes ──────────────────────────────────────────────────────────────
    fun buildIndexesModel(info: TableInfo): DictionaryTableModel {
        val headers = listOf("Index Name", "Unique", "Columns")
        val rows = info.indexes.map { idx ->
            listOf(idx.name, if (idx.isUnique) "YES" else "NO", idx.columns.joinToString(", "))
        }
        return DictionaryTableModel(headers, rows)
    }

    // ── Check Constraints ────────────────────────────────────────────────────
    fun buildChecksModel(info: TableInfo): DictionaryTableModel {
        val headers = listOf("Check Name", "Columns")
        val rows = info.checks.map { chk ->
            listOf(chk.name, chk.columns.joinToString(", "))
        }
        return DictionaryTableModel(headers, rows)
    }

    // ── DDL ──────────────────────────────────────────────────────────────────
    fun buildDdl(info: TableInfo): String {
        val schema = info.schema.uppercase()
        val table = info.name.uppercase()
        val sb = StringBuilder()
        sb.append("CREATE TABLE $schema.$table (\n")

        val parts = mutableListOf<String>()

        for (col in info.columns) {
            val typePart = buildString {
                append(col.dataType)
                when {
                    col.precision != null && col.scale != null -> append("(${col.precision},${col.scale})")
                    col.precision != null                      -> append("(${col.precision})")
                    col.size != null                           -> append("(${col.size})")
                }
            }
            val notNull = if (col.isNotNull) " NOT NULL" else ""
            val default = col.default?.let { " DEFAULT $it" } ?: ""
            parts += "    ${col.name.padEnd(30)} $typePart$default$notNull"
        }

        for (key in info.keys) {
            val cols = key.columns.joinToString(", ")
            parts += "    CONSTRAINT ${key.name} PRIMARY KEY ($cols)"
        }

        for (fk in info.foreignKeys) {
            val cols = fk.columns.joinToString(", ")
            val refSchema = fk.refSchema?.let { "$it." } ?: ""
            val refCols = fk.refColumns.joinToString(", ")
            val onDelete = fk.deleteRule?.let { if (it != "NO_ACTION") " ON DELETE $it" else "" } ?: ""
            parts += "    CONSTRAINT ${fk.name} FOREIGN KEY ($cols)\n        REFERENCES $refSchema${fk.refTable} ($refCols)$onDelete"
        }

        for (chk in info.checks) {
            val cols = chk.columns.joinToString(", ")
            if (cols.isNotBlank()) {
                parts += "    CONSTRAINT ${chk.name} CHECK ($cols)"
            }
        }

        sb.append(parts.joinToString(",\n"))
        sb.append("\n);")

        info.comment?.takeIf { it.isNotBlank() }?.let {
            sb.append("\n\nCOMMENT ON TABLE $schema.$table IS '$it';")
        }

        for (col in info.columns) {
            col.comment?.takeIf { it.isNotBlank() }?.let {
                sb.append("\nCOMMENT ON COLUMN $schema.$table.${col.name} IS '$it';")
            }
        }

        return sb.toString()
    }

    // ── SELECT ───────────────────────────────────────────────────────────────
    fun buildSelectQuery(info: TableInfo): String {
        val cols: List<ColumnInfo> = info.columns
        val colLines = cols.mapIndexed { i, c ->
            val comma = if (i < cols.lastIndex) "," else " "
            "      ${c.name}$comma"
        }.joinToString("\n")
        return "SELECT\n$colLines\nFROM ${info.schema.uppercase()}.${info.name.uppercase()};"
    }

    // ── Oracle 전용 SQL 스니펫 (사용자에게 보여주는 텍스트) ──────────────────
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
