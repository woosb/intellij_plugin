package com.github.wooju.oracleinspector.repository

import com.intellij.database.dataSource.DatabaseConnectionManager
import com.intellij.database.psi.DbDataSource
import com.intellij.database.remote.jdbc.RemoteConnection
import com.intellij.database.remote.jdbc.RemotePreparedStatement
import com.intellij.database.remote.jdbc.RemoteResultSet
import com.intellij.database.util.DbImplUtil
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project

private val LOG = logger<JdbcObjectChangeRepository>()

/**
 * 객체가 DB에서 변경됐는지 **싸게** 판정하기 위한 신호만 조회한다.
 * ALL_OBJECTS.LAST_DDL_TIME 단일-행 인덱스 조회 — 풀 메타 fetch보다 자릿수 단위로 가볍다.
 *
 * 캐시된 DTO와 함께 저장해 둔 기준선과 비교하는 용도. 실패(권한/네트워크)는 조용히 null —
 * 변경 감지는 "있으면 좋은" 부가 기능이라 절대 사용자에게 에러를 띄우지 않는다.
 */
class JdbcObjectChangeRepository(
    private val project: Project,
    private val dataSource: DbDataSource,
) {

    /**
     * @param objectTypes 예: ["PROCEDURE","FUNCTION"] 또는 ["PACKAGE","PACKAGE BODY"].
     *        여러 타입의 MAX(LAST_DDL_TIME)을 반환 → 패키지 spec/body 어느 쪽이 바뀌어도 감지.
     * @return "YYYY-MM-DD HH24:MI:SS" 문자열, 못 찾거나 실패 시 null.
     */
    fun loadLastDdlTime(schema: String, name: String, objectTypes: List<String>): String? {
        if (objectTypes.isEmpty()) return null
        return try {
            val local = DbImplUtil.getMaybeLocalDataSource(dataSource) ?: return null
            val ref = DatabaseConnectionManager.getInstance().build(project, local).createBlocking() ?: return null
            ref.use { r -> queryMaxDdl(r.get().remoteConnection, schema.uppercase(), name.uppercase(), objectTypes) }
        } catch (t: Throwable) {
            LOG.debug("LAST_DDL_TIME 조회 실패 — 무시", t)
            null
        }
    }

    private fun queryMaxDdl(
        conn: RemoteConnection,
        owner: String,
        name: String,
        objectTypes: List<String>,
    ): String? {
        val inClause = objectTypes.joinToString(", ") { "?" }
        val sql = """
            SELECT MAX(TO_CHAR(LAST_DDL_TIME, 'YYYY-MM-DD HH24:MI:SS'))
            FROM ALL_OBJECTS
            WHERE OWNER = ? AND OBJECT_NAME = ? AND OBJECT_TYPE IN ($inClause)
        """.trimIndent()
        var stmt: RemotePreparedStatement? = null
        var rs: RemoteResultSet? = null
        try {
            stmt = conn.prepareStatement(sql)
            stmt.setString(1, owner)
            stmt.setString(2, name)
            objectTypes.forEachIndexed { i, t -> stmt!!.setString(3 + i, t) }
            rs = stmt.executeQuery()
            return if (rs.next()) rs.getString(1) else null
        } finally {
            try { rs?.close() } catch (_: Exception) {}
            try { stmt?.close() } catch (_: Exception) {}
        }
    }
}
