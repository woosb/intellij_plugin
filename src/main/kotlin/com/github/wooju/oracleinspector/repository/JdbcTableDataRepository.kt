package com.github.wooju.oracleinspector.repository

import com.intellij.database.dataSource.DatabaseConnectionManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.psi.DbDataSource
import com.intellij.database.remote.jdbc.RemoteConnection
import com.intellij.database.remote.jdbc.RemoteResultSet
import com.intellij.openapi.project.Project

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

    fun loadPage(pageIndex: Int, pageSize: Int): DataPage {
        require(pageIndex >= 0) { "pageIndex must be >= 0" }
        require(pageSize > 0) { "pageSize must be > 0" }

        val local = dataSource.delegate as? LocalDataSource
            ?: throw IllegalStateException("LocalDataSource가 아니어서 JDBC 조회 불가")

        val ref = DatabaseConnectionManager.getInstance()
            .build(project, local)
            .createBlocking()
            ?: throw IllegalStateException("DB 연결을 만들 수 없습니다.")

        ref.use { r ->
            val conn = r.get().remoteConnection
            return queryPage(conn, pageIndex, pageSize)
        }
    }

    private fun queryPage(conn: RemoteConnection, pageIndex: Int, pageSize: Int): DataPage {
        val owner = quoteIdent(schemaName)
        val name = quoteIdent(tableName)
        val offset = pageIndex.toLong() * pageSize.toLong()
        val fetch = pageSize + 1  // hasMore 판정용 +1

        val sql = "SELECT * FROM $owner.$name OFFSET ? ROWS FETCH NEXT ? ROWS ONLY"

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
                    row.add(rs.getObject(i))
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

    /** 식별자에 큰따옴표를 둘러 대소문자/특수문자를 안전하게 처리. 내부 따옴표는 두 번 escape. */
    private fun quoteIdent(ident: String): String = "\"" + ident.replace("\"", "\"\"") + "\""
}
