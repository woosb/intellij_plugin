package com.github.wooju.oracleinspector.repository

import com.github.wooju.oracleinspector.OracleInspectorBundle
import com.github.wooju.oracleinspector.model.CheckInfo
import com.github.wooju.oracleinspector.model.TriggerInfo
import com.github.wooju.oracleinspector.model.ColumnInfo
import com.github.wooju.oracleinspector.model.ForeignKeyInfo
import com.github.wooju.oracleinspector.model.IndexInfo
import com.github.wooju.oracleinspector.model.KeyInfo
import com.github.wooju.oracleinspector.model.TableInfo
import com.intellij.database.dataSource.DatabaseConnectionManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.psi.DbDataSource
import com.intellij.database.remote.jdbc.RemoteConnection
import com.intellij.database.remote.jdbc.RemotePreparedStatement
import com.intellij.database.remote.jdbc.RemoteResultSet
import com.intellij.openapi.project.Project

/**
 * Oracle ALL_* 데이터딕셔너리 뷰를 직접 조회해 TableInfo를 빌드한다.
 * 모든 쿼리는 SELECT 만이며, 바인드 파라미터로 OWNER/TABLE_NAME을 전달한다.
 */
class JdbcTableMetadataRepository(
    private val project: Project,
    private val dataSource: DbDataSource,
    private val schemaName: String,
    private val tableName: String,
) : TableMetadataRepository {

    override fun loadTable(): TableInfo {
        val local = dataSource.delegate as? LocalDataSource
            ?: throw IllegalStateException(OracleInspectorBundle.message("common.error.no.local.datasource"))

        val ref = DatabaseConnectionManager.getInstance()
            .build(project, local)
            .createBlocking()
            ?: throw IllegalStateException(OracleInspectorBundle.message("common.error.cannot.create.connection"))
        ref.use { r ->
            val conn = r.get().remoteConnection
            return queryAll(conn)
        }
    }

    private fun queryAll(conn: RemoteConnection): TableInfo {
        val owner = schemaName.uppercase()
        val name = tableName.uppercase()

        // ALL_OBJECTS로 TABLE/VIEW 종류부터 판별 — ALL_VIEWS는 VIEW일 때만 의미
        val objectType = queryObjectType(conn, owner, name)
        val isView = objectType == "VIEW"
        val viewDefinition = if (isView) queryViewDefinition(conn, owner, name) else null

        val tableComment = queryTableComment(conn, owner, name)
        val columnComments = queryColumnComments(conn, owner, name)
        val pkPositions = queryPkPositions(conn, owner, name)
        val indexes = if (isView) emptyList() else queryIndexes(conn, owner, name)
        val indexedColumns = indexes.flatMap { it.columns.map { c -> c.uppercase() } }.toSet()
        val columns = queryColumns(conn, owner, name, columnComments, pkPositions, indexedColumns)
        val keys = if (isView) emptyList() else queryKeys(conn, owner, name)
        val foreignKeys = if (isView) emptyList() else queryForeignKeys(conn, owner, name)
        val checks = if (isView) emptyList() else queryChecks(conn, owner, name)
        val triggers = queryTriggers(conn, owner, name)  // VIEW에도 INSTEAD OF 트리거 가능

        return TableInfo(
            schema = schemaName,
            name = tableName,
            comment = tableComment,
            columns = columns,
            keys = keys,
            foreignKeys = foreignKeys,
            indexes = indexes,
            checks = checks,
            triggers = triggers,
            isView = isView,
            viewDefinition = viewDefinition,
        )
    }

    private fun queryTriggers(conn: RemoteConnection, owner: String, name: String): List<TriggerInfo> {
        val sql = """
            SELECT TRIGGER_NAME, TRIGGER_TYPE, TRIGGERING_EVENT, STATUS, ACTION_TYPE
            FROM ALL_TRIGGERS
            WHERE OWNER = ? AND TABLE_NAME = ?
            ORDER BY TRIGGER_NAME
        """.trimIndent()
        return executeQuery(conn, sql, owner, name) { rs ->
            val out = ArrayList<TriggerInfo>()
            while (rs.next()) {
                out += TriggerInfo(
                    name = rs.getString(1) ?: "",
                    type = rs.getString(2),
                    event = rs.getString(3),
                    status = rs.getString(4),
                    actionType = rs.getString(5),
                )
            }
            out
        }
    }

    /** OBJECT_TYPE 반환 ("TABLE" / "VIEW" / null). MAT VIEW 등은 TABLE로 취급. */
    private fun queryObjectType(conn: RemoteConnection, owner: String, name: String): String? {
        val sql = """
            SELECT OBJECT_TYPE FROM ALL_OBJECTS
            WHERE OWNER = ? AND OBJECT_NAME = ?
              AND OBJECT_TYPE IN ('TABLE', 'VIEW')
            ORDER BY DECODE(OBJECT_TYPE, 'TABLE', 1, 'VIEW', 2, 9)
            FETCH FIRST 1 ROWS ONLY
        """.trimIndent()
        return executeQuery(conn, sql, owner, name) { rs ->
            if (rs.next()) rs.getString(1) else null
        }
    }

    /** ALL_VIEWS.TEXT (CLOB) 본문. 권한 없으면 ORA-00942 → null로 폴백. */
    private fun queryViewDefinition(conn: RemoteConnection, owner: String, name: String): String? {
        val sql = "SELECT TEXT FROM ALL_VIEWS WHERE OWNER = ? AND VIEW_NAME = ?"
        return try {
            executeQuery(conn, sql, owner, name) { rs ->
                if (rs.next()) rs.getString(1)?.trim() else null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun queryTableComment(conn: RemoteConnection, owner: String, name: String): String? {
        val sql = "SELECT COMMENTS FROM ALL_TAB_COMMENTS WHERE OWNER = ? AND TABLE_NAME = ?"
        return executeQuery(conn, sql, owner, name) { rs ->
            if (rs.next()) rs.getString(1) else null
        }
    }

    private fun queryColumnComments(conn: RemoteConnection, owner: String, name: String): Map<String, String?> {
        val sql = "SELECT COLUMN_NAME, COMMENTS FROM ALL_COL_COMMENTS WHERE OWNER = ? AND TABLE_NAME = ?"
        return executeQuery(conn, sql, owner, name) { rs ->
            val map = HashMap<String, String?>()
            while (rs.next()) {
                map[rs.getString(1).uppercase()] = rs.getString(2)
            }
            map
        }
    }

    private fun queryPkPositions(conn: RemoteConnection, owner: String, name: String): Map<String, Int> {
        val sql = """
            SELECT cc.COLUMN_NAME, cc.POSITION
            FROM ALL_CONSTRAINTS c
            JOIN ALL_CONS_COLUMNS cc ON cc.OWNER = c.OWNER AND cc.CONSTRAINT_NAME = c.CONSTRAINT_NAME
            WHERE c.OWNER = ? AND c.TABLE_NAME = ? AND c.CONSTRAINT_TYPE = 'P'
        """.trimIndent()
        return executeQuery(conn, sql, owner, name) { rs ->
            val map = HashMap<String, Int>()
            while (rs.next()) {
                map[rs.getString(1).uppercase()] = rs.getInt(2)
            }
            map
        }
    }

    private fun queryColumns(
        conn: RemoteConnection,
        owner: String,
        name: String,
        columnComments: Map<String, String?>,
        pkPositions: Map<String, Int>,
        indexedColumns: Set<String>,
    ): List<ColumnInfo> {
        val sql = """
            SELECT COLUMN_ID, COLUMN_NAME, DATA_TYPE, DATA_LENGTH, DATA_PRECISION, DATA_SCALE, NULLABLE, DATA_DEFAULT, CHAR_LENGTH, CHAR_USED
            FROM ALL_TAB_COLUMNS
            WHERE OWNER = ? AND TABLE_NAME = ?
            ORDER BY COLUMN_ID
        """.trimIndent()
        return executeQuery(conn, sql, owner, name) { rs ->
            val list = ArrayList<ColumnInfo>()
            while (rs.next()) {
                val colName = rs.getString(2)
                val dataType = rs.getString(3) ?: ""
                val dataLength = nullableInt(rs, 4)
                val precision = nullableInt(rs, 5)
                val scale = nullableInt(rs, 6)
                val nullable = rs.getString(7)
                val default = rs.getString(8)?.trim()?.takeIf { it.isNotEmpty() }
                val charLength = nullableInt(rs, 9)
                val charUsed = rs.getString(10)

                // 문자형 컬럼은 CHAR_LENGTH가 더 유의미 (DATA_LENGTH는 바이트일 수 있음)
                val effectiveSize = when {
                    charUsed != null && charLength != null && charLength > 0 -> charLength
                    precision != null -> null   // 수치형은 size 미사용
                    else -> dataLength
                }

                val upper = colName.uppercase()
                list.add(
                    ColumnInfo(
                        position = rs.getInt(1),
                        name = colName,
                        comment = columnComments[upper],
                        dataType = dataType,
                        size = effectiveSize?.takeIf { it > 0 },
                        precision = precision?.takeIf { it > 0 },
                        scale = scale?.takeIf { it > 0 },
                        isNotNull = nullable == "N",
                        default = default,
                        pkPosition = pkPositions[upper],
                        isIndexed = upper in indexedColumns,
                    )
                )
            }
            list
        }
    }

    private fun queryKeys(conn: RemoteConnection, owner: String, name: String): List<KeyInfo> {
        // PK / Unique
        val sql = """
            SELECT c.CONSTRAINT_NAME, c.CONSTRAINT_TYPE, cc.COLUMN_NAME, cc.POSITION
            FROM ALL_CONSTRAINTS c
            JOIN ALL_CONS_COLUMNS cc ON cc.OWNER = c.OWNER AND cc.CONSTRAINT_NAME = c.CONSTRAINT_NAME
            WHERE c.OWNER = ? AND c.TABLE_NAME = ? AND c.CONSTRAINT_TYPE IN ('P', 'U')
            ORDER BY c.CONSTRAINT_NAME, cc.POSITION
        """.trimIndent()
        return executeQuery(conn, sql, owner, name) { rs ->
            data class Row(val keyName: String, val type: String, val col: String)
            val rows = ArrayList<Row>()
            while (rs.next()) {
                rows.add(Row(rs.getString(1), rs.getString(2), rs.getString(3)))
            }
            rows.groupBy { it.keyName }.map { (keyName, rs2) ->
                KeyInfo(
                    name = keyName,
                    type = if (rs2.first().type == "P") "PRIMARY" else "UNIQUE",
                    columns = rs2.map { it.col },
                )
            }
        }
    }

    private fun queryForeignKeys(conn: RemoteConnection, owner: String, name: String): List<ForeignKeyInfo> {
        val sql = """
            SELECT
              c.CONSTRAINT_NAME,
              cc.COLUMN_NAME,
              cc.POSITION,
              c.DELETE_RULE,
              rc.OWNER       AS REF_OWNER,
              rc.TABLE_NAME  AS REF_TABLE,
              rcc.COLUMN_NAME AS REF_COLUMN
            FROM ALL_CONSTRAINTS c
            JOIN ALL_CONS_COLUMNS cc  ON cc.OWNER = c.OWNER AND cc.CONSTRAINT_NAME = c.CONSTRAINT_NAME
            JOIN ALL_CONSTRAINTS rc   ON rc.OWNER = c.R_OWNER AND rc.CONSTRAINT_NAME = c.R_CONSTRAINT_NAME
            JOIN ALL_CONS_COLUMNS rcc ON rcc.OWNER = rc.OWNER AND rcc.CONSTRAINT_NAME = rc.CONSTRAINT_NAME AND rcc.POSITION = cc.POSITION
            WHERE c.OWNER = ? AND c.TABLE_NAME = ? AND c.CONSTRAINT_TYPE = 'R'
            ORDER BY c.CONSTRAINT_NAME, cc.POSITION
        """.trimIndent()
        return executeQuery(conn, sql, owner, name) { rs ->
            data class Row(
                val fk: String, val col: String, val pos: Int, val deleteRule: String?,
                val refOwner: String?, val refTable: String?, val refCol: String?,
            )
            val rows = ArrayList<Row>()
            while (rs.next()) {
                rows.add(
                    Row(
                        rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7),
                    )
                )
            }
            rows.groupBy { it.fk }.map { (fkName, rs2) ->
                val sorted = rs2.sortedBy { it.pos }
                ForeignKeyInfo(
                    name = fkName,
                    columns = sorted.map { it.col },
                    refSchema = sorted.first().refOwner,
                    refTable = sorted.first().refTable,
                    refColumns = sorted.mapNotNull { it.refCol },
                    deleteRule = sorted.first().deleteRule,
                    updateRule = null, // Oracle은 UPDATE rule을 지원하지 않음
                )
            }
        }
    }

    private fun queryIndexes(conn: RemoteConnection, owner: String, name: String): List<IndexInfo> {
        val sql = """
            SELECT i.INDEX_NAME, i.UNIQUENESS, ic.COLUMN_NAME, ic.COLUMN_POSITION
            FROM ALL_INDEXES i
            JOIN ALL_IND_COLUMNS ic ON ic.INDEX_OWNER = i.OWNER AND ic.INDEX_NAME = i.INDEX_NAME
            WHERE i.TABLE_OWNER = ? AND i.TABLE_NAME = ?
            ORDER BY i.INDEX_NAME, ic.COLUMN_POSITION
        """.trimIndent()
        return executeQuery(conn, sql, owner, name) { rs ->
            data class Row(val idx: String, val unique: String, val col: String)
            val rows = ArrayList<Row>()
            while (rs.next()) {
                rows.add(Row(rs.getString(1), rs.getString(2), rs.getString(3)))
            }
            rows.groupBy { it.idx }.map { (idxName, rs2) ->
                IndexInfo(
                    name = idxName,
                    isUnique = rs2.first().unique.equals("UNIQUE", ignoreCase = true),
                    columns = rs2.map { it.col },
                )
            }
        }
    }

    private fun queryChecks(conn: RemoteConnection, owner: String, name: String): List<CheckInfo> {
        val sql = """
            SELECT c.CONSTRAINT_NAME, cc.COLUMN_NAME
            FROM ALL_CONSTRAINTS c
            LEFT JOIN ALL_CONS_COLUMNS cc ON cc.OWNER = c.OWNER AND cc.CONSTRAINT_NAME = c.CONSTRAINT_NAME
            WHERE c.OWNER = ? AND c.TABLE_NAME = ? AND c.CONSTRAINT_TYPE = 'C'
              AND c.GENERATED <> 'GENERATED NAME'
            ORDER BY c.CONSTRAINT_NAME
        """.trimIndent()
        return executeQuery(conn, sql, owner, name) { rs ->
            data class Row(val name: String, val col: String?)
            val rows = ArrayList<Row>()
            while (rs.next()) {
                rows.add(Row(rs.getString(1), rs.getString(2)))
            }
            rows.groupBy { it.name }.map { (n, rs2) ->
                CheckInfo(name = n, columns = rs2.mapNotNull { it.col })
            }
        }
    }

    // ── 공통 헬퍼 ─────────────────────────────────────────────────────────────
    private fun <T> executeQuery(
        conn: RemoteConnection,
        sql: String,
        vararg params: String,
        mapper: (RemoteResultSet) -> T,
    ): T {
        var stmt: RemotePreparedStatement? = null
        var rs: RemoteResultSet? = null
        try {
            stmt = conn.prepareStatement(sql)
            for ((i, p) in params.withIndex()) {
                stmt.setString(i + 1, p)
            }
            rs = stmt.executeQuery()
            return mapper(rs)
        } finally {
            try { rs?.close() } catch (_: Exception) {}
            try { stmt?.close() } catch (_: Exception) {}
        }
    }

    private fun nullableInt(rs: RemoteResultSet, idx: Int): Int? {
        val v = rs.getInt(idx)
        return if (rs.wasNull()) null else v
    }
}
