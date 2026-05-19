package com.github.wooju.oracleinspector.repository

import com.github.wooju.oracleinspector.OracleInspectorBundle
import com.github.wooju.oracleinspector.model.LockInfo
import com.github.wooju.oracleinspector.model.LongOpInfo
import com.github.wooju.oracleinspector.model.SessionInfo
import com.github.wooju.oracleinspector.model.WaitEvent
import com.intellij.database.dataSource.DatabaseConnectionManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.psi.DbDataSource
import com.intellij.database.remote.jdbc.RemoteConnection
import com.intellij.database.remote.jdbc.RemotePreparedStatement
import com.intellij.database.remote.jdbc.RemoteResultSet
import com.intellij.openapi.project.Project

/**
 * V$SESSION 기반 세션 목록 + V$SQLAREA에서 단일 세션의 현재 SQL 조회.
 * SYS 백그라운드 세션(TYPE='BACKGROUND')은 기본 제외.
 *
 * 필요한 권한:
 *   GRANT SELECT ON V_$SESSION TO <user>;
 *   GRANT SELECT ON V_$SQLAREA TO <user>;
 *   (또는 SELECT_CATALOG_ROLE / DBA 권한)
 */
class JdbcSessionRepository(
    private val project: Project,
    private val dataSource: DbDataSource,
) {

    fun loadSessions(includeBackground: Boolean = false): List<SessionInfo> {
        return withConnection { conn ->
            val sql = buildString {
                append(
                    """
                    SELECT
                      s.SID,
                      s.SERIAL#,
                      s.USERNAME,
                      s.SCHEMANAME,
                      s.MACHINE,
                      s.PROGRAM,
                      s.MODULE,
                      s.OSUSER,
                      s.STATUS,
                      s.LAST_CALL_ET,
                      s.WAIT_CLASS,
                      s.EVENT,
                      s.SQL_ID,
                      s.BLOCKING_SESSION
                    FROM V${'$'}SESSION s
                    """.trimIndent()
                )
                if (!includeBackground) append("\nWHERE s.TYPE = 'USER'")
                append("\nORDER BY s.STATUS, s.LAST_CALL_ET")
            }
            executeQuery(conn, sql) { rs ->
                val out = ArrayList<SessionInfo>()
                while (rs.next()) {
                    out += SessionInfo(
                        sid = rs.getInt(1),
                        serial = rs.getLong(2),
                        username = rs.getString(3),
                        schemaName = rs.getString(4),
                        machine = rs.getString(5),
                        program = rs.getString(6),
                        module = rs.getString(7),
                        osUser = rs.getString(8),
                        status = rs.getString(9),
                        lastCallEt = nullableLong(rs, 10),
                        waitClass = rs.getString(11),
                        event = rs.getString(12),
                        sqlId = rs.getString(13),
                        blockingSession = nullableInt(rs, 14),
                    )
                }
                out
            }
        }
    }

    /** 선택한 세션의 현재 SQL_FULLTEXT (SQL_ID 기준). 없으면 null. */
    fun loadCurrentSqlText(sqlId: String): String? {
        if (sqlId.isBlank()) return null
        return withConnection { conn ->
            val sql = "SELECT SQL_FULLTEXT FROM V${'$'}SQLAREA WHERE SQL_ID = ?"
            executePrepared(conn, sql, listOf(sqlId)) { rs ->
                if (rs.next()) rs.getString(1) else null
            }
        }
    }

    /**
     * V$LOCKED_OBJECT 기반 락 목록. 객체 이름은 ALL_OBJECTS 조인으로 풀어 반환.
     * LOCKED_MODE 숫자는 Oracle 표준 텍스트로 변환:
     *   0 None / 1 Null / 2 Row-S / 3 Row-X / 4 Share / 5 S/Row-X / 6 Exclusive
     */
    fun loadLocks(): List<LockInfo> {
        return withConnection { conn ->
            val sql = """
                SELECT
                  l.SESSION_ID,
                  s.SERIAL#,
                  s.USERNAME,
                  s.SCHEMANAME,
                  s.OSUSER,
                  s.MACHINE,
                  s.PROGRAM,
                  s.MODULE,
                  s.STATUS,
                  s.SECONDS_IN_WAIT,
                  o.OWNER       AS OBJ_OWNER,
                  o.OBJECT_NAME AS OBJ_NAME,
                  o.OBJECT_TYPE AS OBJ_TYPE,
                  DECODE(l.LOCKED_MODE,
                    0, 'None', 1, 'Null', 2, 'Row-S', 3, 'Row-X',
                    4, 'Share', 5, 'S/Row-X', 6, 'Exclusive',
                    TO_CHAR(l.LOCKED_MODE)) AS LOCK_MODE_TEXT,
                  s.BLOCKING_SESSION
                FROM V${'$'}LOCKED_OBJECT l
                JOIN V${'$'}SESSION s ON s.SID = l.SESSION_ID
                LEFT JOIN ALL_OBJECTS o ON o.OBJECT_ID = l.OBJECT_ID
                ORDER BY o.OBJECT_NAME, l.SESSION_ID
            """.trimIndent()
            executeQuery(conn, sql) { rs ->
                val out = ArrayList<LockInfo>()
                while (rs.next()) {
                    out += LockInfo(
                        sid = rs.getInt(1),
                        serial = rs.getLong(2),
                        username = rs.getString(3),
                        schemaName = rs.getString(4),
                        osUser = rs.getString(5),
                        machine = rs.getString(6),
                        program = rs.getString(7),
                        module = rs.getString(8),
                        status = rs.getString(9),
                        secondsInWait = nullableLong(rs, 10),
                        objectOwner = rs.getString(11),
                        objectName = rs.getString(12),
                        objectType = rs.getString(13),
                        lockMode = rs.getString(14),
                        blockingSession = nullableInt(rs, 15),
                    )
                }
                out
            }
        }
    }

    /**
     * V$SESSION_LONGOPS 기반 장시간 작업 목록.
     * 기본은 "아직 진행 중인 것"만 (SOFAR < TOTALWORK).
     */
    fun loadLongOps(onlyActive: Boolean = true): List<LongOpInfo> {
        return withConnection { conn ->
            val sql = buildString {
                append(
                    """
                    SELECT
                      SID, SERIAL#, USERNAME,
                      OPNAME, TARGET,
                      SOFAR, TOTALWORK, UNITS,
                      ELAPSED_SECONDS, TIME_REMAINING,
                      MESSAGE
                    FROM V${'$'}SESSION_LONGOPS
                    """.trimIndent()
                )
                if (onlyActive) append("\nWHERE TOTALWORK > 0 AND SOFAR < TOTALWORK")
                append("\nORDER BY START_TIME DESC")
            }
            executeQuery(conn, sql) { rs ->
                val out = ArrayList<LongOpInfo>()
                while (rs.next()) {
                    out += LongOpInfo(
                        sid = rs.getInt(1),
                        serial = rs.getLong(2),
                        username = rs.getString(3),
                        opname = rs.getString(4),
                        target = rs.getString(5),
                        sofar = rs.getLong(6),
                        totalwork = rs.getLong(7),
                        units = rs.getString(8),
                        elapsedSec = nullableLong(rs, 9),
                        timeRemainingSec = nullableLong(rs, 10),
                        message = rs.getString(11),
                    )
                }
                out
            }
        }
    }

    /** 선택 세션의 최근 wait event 10건 (V$SESSION_WAIT_HISTORY). 빈 결과면 emptyList. */
    fun loadWaitHistory(sid: Int): List<WaitEvent> {
        return withConnection { conn ->
            val sql = """
                SELECT SEQ#, EVENT, WAIT_TIME, P1, P2, P3
                FROM V${'$'}SESSION_WAIT_HISTORY
                WHERE SID = ?
                ORDER BY SEQ#
            """.trimIndent()
            executePrepared(conn, sql, listOf(sid.toString())) { rs ->
                val out = ArrayList<WaitEvent>()
                while (rs.next()) {
                    out += WaitEvent(
                        seq = rs.getInt(1),
                        event = rs.getString(2),
                        waitTime = nullableLong(rs, 3),
                        p1 = rs.getString(4),
                        p2 = rs.getString(5),
                        p3 = rs.getString(6),
                    )
                }
                out
            }
        }
    }

    /**
     * ALTER SYSTEM KILL SESSION 'sid,serial#' IMMEDIATE.
     * 실패하면 throw — UI 측에서 잡아 사용자에게 알린다.
     */
    fun killSession(sid: Int, serial: Long) {
        withConnection { conn ->
            val sql = "ALTER SYSTEM KILL SESSION '$sid,$serial' IMMEDIATE"
            var stmt: com.intellij.database.remote.jdbc.RemoteStatement? = null
            try {
                stmt = conn.createStatement()
                stmt.execute(sql)
            } finally {
                try { stmt?.close() } catch (_: Exception) {}
            }
        }
    }

    // ── 내부 ──────────────────────────────────────────────────────────────────
    private fun <T> withConnection(block: (RemoteConnection) -> T): T {
        val local = dataSource.delegate as? LocalDataSource
            ?: throw IllegalStateException(OracleInspectorBundle.message("common.error.no.local.datasource"))
        val ref = DatabaseConnectionManager.getInstance()
            .build(project, local)
            .createBlocking()
            ?: throw IllegalStateException(OracleInspectorBundle.message("common.error.cannot.create.connection"))
        ref.use { r -> return block(r.get().remoteConnection) }
    }

    private fun <T> executeQuery(
        conn: RemoteConnection,
        sql: String,
        mapper: (RemoteResultSet) -> T,
    ): T {
        var stmt: com.intellij.database.remote.jdbc.RemoteStatement? = null
        var rs: RemoteResultSet? = null
        try {
            stmt = conn.createStatement()
            rs = stmt.executeQuery(sql)
            return mapper(rs)
        } finally {
            try { rs?.close() } catch (_: Exception) {}
            try { stmt?.close() } catch (_: Exception) {}
        }
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

    private fun nullableInt(rs: RemoteResultSet, idx: Int): Int? {
        val v = rs.getInt(idx)
        return if (rs.wasNull()) null else v
    }

    private fun nullableLong(rs: RemoteResultSet, idx: Int): Long? {
        val v = rs.getLong(idx)
        return if (rs.wasNull()) null else v
    }
}
