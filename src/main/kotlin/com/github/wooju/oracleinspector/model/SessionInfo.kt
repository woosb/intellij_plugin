package com.github.wooju.oracleinspector.model

/** V$SESSION_LONGOPS 한 행 — 장시간 실행 작업의 진행률. */
data class LongOpInfo(
    val sid: Int,
    val serial: Long,
    val username: String?,
    val opname: String?,
    val target: String?,
    val sofar: Long,
    val totalwork: Long,
    val units: String?,
    val elapsedSec: Long?,
    val timeRemainingSec: Long?,
    val message: String?,
) {
    /** 0..100 percent, 또는 totalwork이 0이면 null. */
    fun progressPercent(): Int? =
        if (totalwork <= 0) null
        else ((sofar.toDouble() / totalwork) * 100).toInt().coerceIn(0, 100)
}

/** V$SESSION_WAIT_HISTORY 한 행 — 선택 세션의 최근 wait event. */
data class WaitEvent(
    val seq: Int,
    val event: String?,
    val waitTime: Long?,    // WAIT_TIME (centiseconds)
    val p1: String?,
    val p2: String?,
    val p3: String?,
)

/** V$SESSTAT 한 행 — 통계 이름 + 누적 값. */
data class SessionStat(
    val name: String,
    val value: Long,
)

/** V$SQL_PLAN 한 행 — 실행 계획 트리의 한 노드. */
data class PlanRow(
    val id: Int,
    val parentId: Int?,
    val depth: Int,
    val operation: String?,
    val options: String?,
    val objectOwner: String?,
    val objectName: String?,
    val cardinality: Long?,
    val bytes: Long?,
    val cost: Long?,
    val cpuCost: Long?,
    val timeSec: Long?,
)

/**
 * Oracle V$SESSION 한 행을 표시용으로 추린 DTO.
 * SQL 텍스트는 별도 V$SQLAREA 조인이 비싸므로 SQL_ID만 가지고 있다가
 * 사용자가 행을 선택하면 그때 풀어 조회한다.
 */
data class SessionInfo(
    val sid: Int,
    val serial: Long,
    val username: String?,
    val schemaName: String?,
    val machine: String?,
    val program: String?,
    val module: String?,
    val osUser: String?,
    val status: String?,        // ACTIVE / INACTIVE / KILLED ...
    val lastCallEt: Long?,      // 초
    val waitClass: String?,
    val event: String?,
    val sqlId: String?,
    val blockingSession: Int?,  // 차단 중인 SID (있으면)
)
