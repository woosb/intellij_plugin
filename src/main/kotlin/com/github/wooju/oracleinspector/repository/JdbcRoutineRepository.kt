package com.github.wooju.oracleinspector.repository

import com.github.wooju.oracleinspector.OracleInspectorBundle
import com.github.wooju.oracleinspector.model.ArgumentInfo
import com.github.wooju.oracleinspector.model.RoutineError
import com.github.wooju.oracleinspector.model.RoutineInfo
import com.github.wooju.oracleinspector.model.RoutineKind
import com.intellij.database.dataSource.DatabaseConnectionManager
import com.intellij.database.psi.DbDataSource
import com.intellij.database.util.DbImplUtil
import com.intellij.database.remote.jdbc.RemoteConnection
import com.intellij.database.remote.jdbc.RemotePreparedStatement
import com.intellij.database.remote.jdbc.RemoteResultSet
import com.intellij.openapi.project.Project

/**
 * Standalone PROCEDURE / FUNCTION 의 source / arguments / errors 를
 * ALL_SOURCE / ALL_ARGUMENTS / ALL_ERRORS 에서 직접 조회한다.
 * 패키지 내부 루틴은 별도 지원 필요 (현 단계 비포함).
 */
class JdbcRoutineRepository(
    private val project: Project,
    private val dataSource: DbDataSource,
    private val schemaName: String,
    private val routineName: String,
) : RoutineMetadataRepository {

    override fun loadRoutine(): RoutineInfo {
        val local = DbImplUtil.getMaybeLocalDataSource(dataSource)
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

    private fun queryAll(conn: RemoteConnection): RoutineInfo {
        val owner = schemaName.uppercase()
        val name = routineName.uppercase()

        val (kind, source) = querySource(conn, owner, name)
        val arguments = queryArguments(conn, owner, name)
        val errors = queryErrors(conn, owner, name)

        return RoutineInfo(
            schema = schemaName,
            name = routineName,
            kind = kind,
            source = source,
            arguments = arguments,
            errors = errors,
        )
    }

    private fun querySource(conn: RemoteConnection, owner: String, name: String): Pair<RoutineKind, String> {
        val sql = """
            SELECT TYPE, LINE, TEXT
            FROM ALL_SOURCE
            WHERE OWNER = ? AND NAME = ? AND TYPE IN ('PROCEDURE', 'FUNCTION')
            ORDER BY TYPE, LINE
        """.trimIndent()
        return executeQuery(conn, sql, owner, name) { rs ->
            val sb = StringBuilder()
            var typeFound: String? = null
            while (rs.next()) {
                if (typeFound == null) typeFound = rs.getString(1)
                sb.append(rs.getString(3) ?: "")
            }
            val kind = when (typeFound) {
                "FUNCTION" -> RoutineKind.FUNCTION
                "PROCEDURE" -> RoutineKind.PROCEDURE
                else -> RoutineKind.UNKNOWN
            }
            kind to sb.toString()
        }
    }

    private fun queryArguments(conn: RemoteConnection, owner: String, name: String): List<ArgumentInfo> {
        val sql = """
            SELECT POSITION, ARGUMENT_NAME, IN_OUT, DATA_TYPE, DEFAULTED
            FROM ALL_ARGUMENTS
            WHERE OWNER = ? AND OBJECT_NAME = ? AND PACKAGE_NAME IS NULL
            ORDER BY POSITION
        """.trimIndent()
        return executeQuery(conn, sql, owner, name) { rs ->
            val list = ArrayList<ArgumentInfo>()
            while (rs.next()) {
                val pos = rs.getInt(1)
                val argName = rs.getString(2)
                val inOut = rs.getString(3) ?: "IN"
                val dataType = rs.getString(4) ?: ""
                val defaulted = rs.getString(5)
                list.add(
                    ArgumentInfo(
                        position = pos,
                        name = argName?.takeIf { it.isNotBlank() },
                        direction = if (pos == 0) "RETURN" else inOut,
                        dataType = dataType,
                        defaultValue = if (defaulted == "Y") "DEFAULT" else null,
                    )
                )
            }
            list
        }
    }

    private fun queryErrors(conn: RemoteConnection, owner: String, name: String): List<RoutineError> {
        val sql = """
            SELECT LINE, POSITION, TEXT
            FROM ALL_ERRORS
            WHERE OWNER = ? AND NAME = ? AND TYPE IN ('PROCEDURE', 'FUNCTION')
            ORDER BY SEQUENCE
        """.trimIndent()
        return executeQuery(conn, sql, owner, name) { rs ->
            val list = ArrayList<RoutineError>()
            while (rs.next()) {
                list.add(
                    RoutineError(
                        line = rs.getInt(1),
                        position = rs.getInt(2),
                        text = rs.getString(3) ?: "",
                    )
                )
            }
            list
        }
    }

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
            for ((i, p) in params.withIndex()) stmt.setString(i + 1, p)
            rs = stmt.executeQuery()
            return mapper(rs)
        } finally {
            try { rs?.close() } catch (_: Exception) {}
            try { stmt?.close() } catch (_: Exception) {}
        }
    }
}
