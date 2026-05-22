package com.github.wooju.oracleinspector.repository

import com.github.wooju.oracleinspector.OracleInspectorBundle
import com.intellij.database.dataSource.DatabaseConnectionManager
import com.intellij.database.psi.DbDataSource
import com.intellij.database.util.DbImplUtil
import com.intellij.database.remote.jdbc.RemoteConnection
import com.intellij.database.remote.jdbc.RemoteResultSet
import com.intellij.openapi.project.Project
import java.sql.Types

/**
 * 테이블 데이터를 페이지 단위로 조회한다.
 * Oracle 12c+ 표준 SQL인 OFFSET ... FETCH NEXT ... ROWS ONLY 사용.
 * 총 행수는 조회하지 않고, page_size + 1 만큼 가져와서 hasMore 여부만 판정한다.
 */
class JdbcTableDataRepository(
    private val project: Project,
    private val dataSource: DbDataSource,
    private val schemaName: String,
    private val tableName: String,
) {

    data class DataPage(
        val columns: List<String>,
        val rows: List<List<Any?>>,
        val pageIndex: Int,
        val pageSize: Int,
        val hasMore: Boolean,
    )

    fun loadPage(
        pageIndex: Int,
        pageSize: Int,
        where: String? = null,
        orderBy: String? = null,
    ): DataPage {
        require(pageIndex >= 0) { "pageIndex must be >= 0" }
        require(pageSize > 0) { "pageSize must be > 0" }

        val local = DbImplUtil.getMaybeLocalDataSource(dataSource)
            ?: throw IllegalStateException(OracleInspectorBundle.message("common.error.no.local.datasource"))

        val ref = DatabaseConnectionManager.getInstance()
            .build(project, local)
            .createBlocking()
            ?: throw IllegalStateException(OracleInspectorBundle.message("common.error.cannot.create.connection"))

        ref.use { r ->
            val conn = r.get().remoteConnection
            return queryPage(conn, pageIndex, pageSize, where, orderBy)
        }
    }

    private fun queryPage(
        conn: RemoteConnection,
        pageIndex: Int,
        pageSize: Int,
        where: String?,
        orderBy: String?,
    ): DataPage {
        val owner = quoteIdent(schemaName)
        val name = quoteIdent(tableName)
        val offset = pageIndex.toLong() * pageSize.toLong()
        val fetch = pageSize + 1  // hasMore 판정용 +1

        val sql = buildString {
            append("SELECT * FROM ").append(owner).append('.').append(name)
            where?.trim()?.takeIf { it.isNotEmpty() }?.let { append(" WHERE ").append(it) }
            orderBy?.trim()?.takeIf { it.isNotEmpty() }?.let { append(" ORDER BY ").append(it) }
            append(" OFFSET ? ROWS FETCH NEXT ? ROWS ONLY")
        }

        var stmt: com.intellij.database.remote.jdbc.RemotePreparedStatement? = null
        var rs: RemoteResultSet? = null
        try {
            stmt = conn.prepareStatement(sql)
            stmt.setLong(1, offset)
            stmt.setInt(2, fetch)
            rs = stmt.executeQuery()

            val meta = rs.metaData
            val colCount = meta.columnCount
            val cols = (1..colCount).map { meta.getColumnLabel(it) }
            val sqlTypes = IntArray(colCount) { meta.getColumnType(it + 1) }

            val rows = ArrayList<List<Any?>>(pageSize)
            var seen = 0
            var hasMore = false
            while (rs.next()) {
                if (seen >= pageSize) {
                    hasMore = true
                    break
                }
                val row = ArrayList<Any?>(colCount)
                for (i in 1..colCount) {
                    row.add(readValue(rs, i, sqlTypes[i - 1]))
                }
                rows.add(row)
                seen++
            }

            return DataPage(
                columns = cols,
                rows = rows,
                pageIndex = pageIndex,
                pageSize = pageSize,
                hasMore = hasMore,
            )
        } finally {
            try { rs?.close() } catch (_: Exception) {}
            try { stmt?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Oracle 전용 객체(oracle.sql.TIMESTAMPTZ 등)는 IntelliJ 원격 JDBC 클라이언트
     * 클래스로더에 없어서 getObject로 받으면 "<failed to load>"로 표시된다.
     * 표시 가능한 표준 타입(String/Number/Boolean/byte[])으로 우회 변환한다.
     */
    private fun readValue(rs: RemoteResultSet, idx: Int, sqlType: Int): Any? {
        val raw: Any? = when (sqlType) {
            Types.DATE,
            Types.TIME,
            Types.TIME_WITH_TIMEZONE,
            Types.TIMESTAMP,
            Types.TIMESTAMP_WITH_TIMEZONE,
            Types.CLOB,
            Types.NCLOB,
            Types.SQLXML,
            Types.ROWID,
            Types.STRUCT,
            Types.ARRAY,
            Types.REF,
            Types.OTHER,
            -> rs.getString(idx)

            Types.BLOB,
            Types.BINARY,
            Types.VARBINARY,
            Types.LONGVARBINARY,
            -> rs.getBytes(idx)?.let { "<BINARY ${it.size} bytes>" }

            else -> try {
                rs.getObject(idx)
            } catch (_: Throwable) {
                rs.getString(idx)
            }
        }
        return if (rs.wasNull()) null else raw
    }

    /** 식별자에 큰따옴표를 둘러 대소문자/특수문자를 안전하게 처리. 내부 따옴표는 두 번 escape. */
    private fun quoteIdent(ident: String): String = "\"" + ident.replace("\"", "\"\"") + "\""
}
