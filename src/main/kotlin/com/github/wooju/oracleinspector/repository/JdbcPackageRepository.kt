package com.github.wooju.oracleinspector.repository

import com.github.wooju.oracleinspector.OracleInspectorBundle
import com.github.wooju.oracleinspector.model.PackageError
import com.github.wooju.oracleinspector.model.PackageInfo
import com.github.wooju.oracleinspector.model.PackageRoutine
import com.intellij.database.dataSource.DatabaseConnectionManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.psi.DbDataSource
import com.intellij.database.remote.jdbc.RemoteConnection
import com.intellij.database.remote.jdbc.RemotePreparedStatement
import com.intellij.database.remote.jdbc.RemoteResultSet
import com.intellij.openapi.project.Project

/**
 * PACKAGE 한 건의 Spec / Body / Routines / Errors 를 ALL_SOURCE / ALL_PROCEDURES /
 * ALL_ERRORS 에서 조회한다.
 *  - Spec  : ALL_SOURCE TYPE='PACKAGE'      (LINE 순)
 *  - Body  : ALL_SOURCE TYPE='PACKAGE BODY' (LINE 순) — 없을 수 있음
 *  - Routines: ALL_PROCEDURES (PROCEDURE_NAME IS NOT NULL)
 *  - Errors: ALL_ERRORS TYPE IN ('PACKAGE','PACKAGE BODY')
 */
class JdbcPackageRepository(
    private val project: Project,
    private val dataSource: DbDataSource,
    private val schemaName: String,
    private val packageName: String,
) : PackageMetadataRepository {

    override fun loadPackage(): PackageInfo {
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

    private fun queryAll(conn: RemoteConnection): PackageInfo {
        val owner = schemaName.uppercase()
        val name = packageName.uppercase()
        return PackageInfo(
            schema = schemaName,
            name = packageName,
            specSource = querySource(conn, owner, name, "PACKAGE"),
            bodySource = querySource(conn, owner, name, "PACKAGE BODY").takeIf { it.isNotBlank() },
            routines = queryRoutines(conn, owner, name),
            errors = queryErrors(conn, owner, name),
        )
    }

    private fun querySource(conn: RemoteConnection, owner: String, name: String, type: String): String {
        val sql = """
            SELECT TEXT FROM ALL_SOURCE
            WHERE OWNER = ? AND NAME = ? AND TYPE = ?
            ORDER BY LINE
        """.trimIndent()
        return executeQuery(conn, sql, owner, name, type) { rs ->
            val sb = StringBuilder()
            while (rs.next()) sb.append(rs.getString(1) ?: "")
            sb.toString()
        }
    }

    private fun queryRoutines(conn: RemoteConnection, owner: String, name: String): List<PackageRoutine> {
        // OBJECT_TYPE = 'PROCEDURE'/'FUNCTION' 컬럼이 11g+ 부터 존재.
        // 안전을 위해 ALL_ARGUMENTS 의 POSITION=0 (RETURN) 여부로 FUNCTION 판별 폴백 가능하지만,
        // 우선 ALL_PROCEDURES 의 OBJECT_TYPE 우선, 없으면 PROCEDURE 로 표기.
        val sql = """
            SELECT
              p.PROCEDURE_NAME,
              p.OVERLOAD,
              NVL(p.OBJECT_TYPE, 'PROCEDURE') AS KIND
            FROM ALL_PROCEDURES p
            WHERE p.OWNER = ? AND p.OBJECT_NAME = ?
              AND p.PROCEDURE_NAME IS NOT NULL
            ORDER BY p.PROCEDURE_NAME, NVL(p.OVERLOAD, '0')
        """.trimIndent()
        return executeQuery(conn, sql, owner, name) { rs ->
            val out = ArrayList<PackageRoutine>()
            while (rs.next()) {
                out += PackageRoutine(
                    name = rs.getString(1) ?: "",
                    overload = rs.getString(2),
                    kind = rs.getString(3) ?: "PROCEDURE",
                )
            }
            out
        }
    }

    private fun queryErrors(conn: RemoteConnection, owner: String, name: String): List<PackageError> {
        val sql = """
            SELECT TYPE, LINE, POSITION, TEXT
            FROM ALL_ERRORS
            WHERE OWNER = ? AND NAME = ? AND TYPE IN ('PACKAGE', 'PACKAGE BODY')
            ORDER BY TYPE, SEQUENCE
        """.trimIndent()
        return executeQuery(conn, sql, owner, name) { rs ->
            val out = ArrayList<PackageError>()
            while (rs.next()) {
                out += PackageError(
                    sourceType = rs.getString(1) ?: "PACKAGE",
                    line = rs.getInt(2),
                    position = rs.getInt(3),
                    text = rs.getString(4) ?: "",
                )
            }
            out
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
