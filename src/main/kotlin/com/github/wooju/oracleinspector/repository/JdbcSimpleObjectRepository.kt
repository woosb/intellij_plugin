package com.github.wooju.oracleinspector.repository

import com.github.wooju.oracleinspector.OracleInspectorBundle
import com.github.wooju.oracleinspector.model.SequenceInfo
import com.github.wooju.oracleinspector.model.SynonymInfo
import com.intellij.database.dataSource.DatabaseConnectionManager
import com.intellij.database.psi.DbDataSource
import com.intellij.database.util.DbImplUtil
import com.intellij.database.remote.jdbc.RemoteConnection
import com.intellij.database.remote.jdbc.RemotePreparedStatement
import com.intellij.database.remote.jdbc.RemoteResultSet
import com.intellij.openapi.project.Project

/**
 * 단순한 단일-행 객체 (SEQUENCE / SYNONYM) 조회용.
 * 각 객체 종류마다 별도 Repository를 만드는 것보다 한 파일에 묶어두는 게 가볍다.
 */
class JdbcSimpleObjectRepository(
    private val project: Project,
    private val dataSource: DbDataSource,
) {

    fun loadSequence(schemaName: String, sequenceName: String): SequenceInfo? = withConnection { conn ->
        val sql = """
            SELECT MIN_VALUE, MAX_VALUE, INCREMENT_BY, CYCLE_FLAG, ORDER_FLAG,
                   CACHE_SIZE, LAST_NUMBER
            FROM ALL_SEQUENCES
            WHERE SEQUENCE_OWNER = ? AND SEQUENCE_NAME = ?
        """.trimIndent()
        executePrepared(conn, sql, listOf(schemaName.uppercase(), sequenceName.uppercase())) { rs ->
            if (rs.next()) {
                SequenceInfo(
                    schema = schemaName,
                    name = sequenceName,
                    minValue = rs.getString(1),
                    maxValue = rs.getString(2),
                    incrementBy = rs.getString(3),
                    cycle = "Y".equals(rs.getString(4), ignoreCase = true),
                    ordered = "Y".equals(rs.getString(5), ignoreCase = true),
                    cacheSize = rs.getLong(6).takeUnless { rs.wasNull() },
                    lastNumber = rs.getString(7),
                )
            } else null
        }
    }

    fun loadSynonym(schemaName: String, synonymName: String): SynonymInfo? = withConnection { conn ->
        val sql = """
            SELECT TABLE_OWNER, TABLE_NAME, DB_LINK
            FROM ALL_SYNONYMS
            WHERE OWNER = ? AND SYNONYM_NAME = ?
        """.trimIndent()
        executePrepared(conn, sql, listOf(schemaName.uppercase(), synonymName.uppercase())) { rs ->
            if (rs.next()) {
                SynonymInfo(
                    schema = schemaName,
                    name = synonymName,
                    refOwner = rs.getString(1),
                    refName = rs.getString(2),
                    dbLink = rs.getString(3),
                )
            } else null
        }
    }

    // ── 내부 ──────────────────────────────────────────────────────────────────
    private fun <T> withConnection(block: (RemoteConnection) -> T): T {
        val local = DbImplUtil.getMaybeLocalDataSource(dataSource)
            ?: throw IllegalStateException(OracleInspectorBundle.message("common.error.no.local.datasource"))
        val ref = DatabaseConnectionManager.getInstance()
            .build(project, local)
            .createBlocking()
            ?: throw IllegalStateException(OracleInspectorBundle.message("common.error.cannot.create.connection"))
        ref.use { r -> return block(r.get().remoteConnection) }
    }

    private fun <T> executePrepared(
        conn: RemoteConnection,
        sql: String,
        params: List<String>,
        mapper: (RemoteResultSet) -> T,
    ): T {
        var stmt: RemotePreparedStatement? = null
        var rs: RemoteResultSet? = null
        try {
            stmt = conn.prepareStatement(sql)
            for ((i, p) in params.withIndex()) stmt.setString(i + 1, p)
            rs = stmt.executeQuery()
            return mapper(rs)
        } finally {
            try { rs?.close() } catch (_: Exception) {}
            try { stmt?.close() } catch (_: Exception) {}
        }
    }
}
