package com.github.wooju.oracleinspector.model

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
