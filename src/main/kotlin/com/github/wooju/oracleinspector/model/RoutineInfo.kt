package com.github.wooju.oracleinspector.model

enum class RoutineKind { PROCEDURE, FUNCTION, UNKNOWN }

data class ArgumentInfo(
    val position: Int,
    val name: String?,
    val direction: String,
    val dataType: String,
    val defaultValue: String?,
)

data class RoutineError(
    val line: Int,
    val position: Int,
    val text: String,
)

data class RoutineInfo(
    val schema: String,
    val name: String,
    val kind: RoutineKind,
    val source: String,
    val arguments: List<ArgumentInfo>,
    val errors: List<RoutineError>,
) {
    /**
     * 캐시 데이터가 불완전한지 판정.
     * 소스가 비어있으면 캐시에 없는 것 → JDBC 폴백 필요.
     */
    fun isIncomplete(): Boolean = source.isBlank()
}
