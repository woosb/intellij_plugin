package com.github.wooju.oracleinspector.service

import com.github.wooju.oracleinspector.model.ArgumentInfo
import com.github.wooju.oracleinspector.model.ColumnInfo
import com.github.wooju.oracleinspector.model.RoutineInfo
import com.github.wooju.oracleinspector.model.RoutineKind
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

    // ── Triggers ─────────────────────────────────────────────────────────────
    fun buildTriggersModel(info: TableInfo): DictionaryTableModel {
        val headers = listOf("Trigger Name", "Type", "Event", "Status", "Action Type")
        val rows = info.triggers.map { t ->
            listOf(t.name, t.type, t.event, t.status, t.actionType)
        }
        return DictionaryTableModel(headers, rows)
    }

    // ── DDL ──────────────────────────────────────────────────────────────────
    fun buildDdl(info: TableInfo): String {
        val schema = info.schema.uppercase()
        val table = info.name.uppercase()

        // VIEW면 ALL_VIEWS.TEXT 본문 그대로 CREATE OR REPLACE VIEW 로 감싸 반환.
        // 본문이 비어있으면 (DAS 캐시만 있어 viewDefinition 미수집) 안내 텍스트.
        if (info.isView) {
            val sb = StringBuilder()
            sb.append("CREATE OR REPLACE VIEW $schema.$table AS\n")
            val body = info.viewDefinition?.takeIf { it.isNotBlank() }
            sb.append(body ?: "-- (VIEW 본문 미수집 — 새로고침 버튼으로 JDBC 조회를 실행하세요)")
            if (body != null && !body.trimEnd().endsWith(";")) sb.append(';')
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

    // ── Routine 실행 템플릿 ──────────────────────────────────────────────────
    /**
     * DECLARE / BEGIN / END 블록을 생성한다.
     * 사용자가 SQL 콘솔에 복사해 IN 파라미터 값을 채워 넣고 실행하면 OUT 값을 DBMS_OUTPUT 으로 확인 가능.
     */
    fun buildExecuteBlock(info: RoutineInfo): String {
        val schema = info.schema.uppercase()
        val name = info.name.uppercase()
        val isFunc = info.kind == RoutineKind.FUNCTION
        val params = info.arguments.filter { !it.direction.equals("RETURN", ignoreCase = true) }
        val returnArg = info.arguments.firstOrNull { it.direction.equals("RETURN", ignoreCase = true) }

        val varNames = params.associateWith { localVarName(it) }
        val declColumn = (varNames.values + (if (isFunc) listOf("l_result") else emptyList()))
            .maxOfOrNull { it.length } ?: 0

        val sb = StringBuilder()
        sb.append("SET SERVEROUTPUT ON;\n\n")
        sb.append("DECLARE\n")
        for (p in params) {
            val v = varNames.getValue(p)
            val type = plsqlVarType(p.dataType)
            val init = when (p.direction.uppercase()) {
                "IN", "INOUT", "IN OUT" -> " := NULL"
                else -> ""
            }
            sb.append("    ").append(v.padEnd(declColumn + 2)).append(type).append(init).append(";\n")
        }
        if (isFunc) {
            val type = plsqlVarType(returnArg?.dataType ?: "VARCHAR2")
            sb.append("    ").append("l_result".padEnd(declColumn + 2)).append(type).append(";\n")
        }
        sb.append("BEGIN\n")

        // call
        val callPrefix = if (isFunc) "    l_result := $schema.$name" else "    $schema.$name"
        if (params.isEmpty()) {
            sb.append(callPrefix).append("();\n")
        } else {
            sb.append(callPrefix).append("(\n")
            val argColumn = params.maxOf { paramName(it).length }
            for ((i, p) in params.withIndex()) {
                val pName = paramName(p)
                val comma = if (i < params.lastIndex) "," else ""
                sb.append("        ").append(pName.padEnd(argColumn))
                    .append(" => ").append(varNames.getValue(p)).append(comma).append("\n")
            }
            sb.append("    );\n")
        }

        // outputs
        if (isFunc) {
            sb.append("    DBMS_OUTPUT.PUT_LINE('RETURN: ' || l_result);\n")
        }
        for (p in params) {
            val dir = p.direction.uppercase()
            if (dir == "OUT" || dir == "INOUT" || dir == "IN OUT") {
                val v = varNames.getValue(p)
                sb.append("    DBMS_OUTPUT.PUT_LINE('${paramName(p)}: ' || ").append(v).append(");\n")
            }
        }

        sb.append("END;\n/\n")
        return sb.toString()
    }

    private fun paramName(a: ArgumentInfo): String =
        a.name?.takeIf { it.isNotBlank() } ?: "p${a.position}"

    private fun localVarName(a: ArgumentInfo): String =
        "l_" + paramName(a).lowercase().trimStart('_')

    /**
     * PL/SQL 변수 선언에 쓸 타입 표기.
     * VARCHAR2/CHAR/RAW 등 길이 필수 타입은 안전한 기본값을 채워준다.
     * 그 외는 그대로 사용.
     */
    private fun plsqlVarType(dataType: String): String {
        val t = dataType.trim().uppercase()
        return when (t) {
            "VARCHAR2", "VARCHAR", "STRING" -> "VARCHAR2(4000)"
            "NVARCHAR2"                     -> "NVARCHAR2(2000)"
            "CHAR"                          -> "CHAR(1)"
            "NCHAR"                         -> "NCHAR(1)"
            "RAW"                           -> "RAW(2000)"
            "LONG RAW"                      -> "LONG RAW"
            "PL/SQL BOOLEAN"                -> "BOOLEAN"
            ""                              -> "VARCHAR2(4000)"
            else                            -> t
        }
    }
}
