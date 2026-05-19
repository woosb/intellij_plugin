package com.github.wooju.oracleinspector.model

/**
 * V$LOCKED_OBJECT + V$SESSION + ALL_OBJECTS 한 행.
 * "holder" = 잠금을 보유한 세션, "blocker" = 그 세션이 대기하게 만든 다른 세션(있으면).
 */
data class LockInfo(
    val sid: Int,
    val serial: Long,
    val username: String?,
    val schemaName: String?,
    val osUser: String?,
    val machine: String?,
    val program: String?,
    val module: String?,
    val status: String?,
    val secondsInWait: Long?,
    val objectOwner: String?,
    val objectName: String?,
    val objectType: String?,
    val lockMode: String?,        // Row-X / Exclusive 등 텍스트
    val blockingSession: Int?,
)
