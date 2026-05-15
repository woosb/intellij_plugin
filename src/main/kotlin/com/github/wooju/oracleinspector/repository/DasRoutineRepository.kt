package com.github.wooju.oracleinspector.repository

import com.github.wooju.oracleinspector.model.ArgumentInfo
import com.github.wooju.oracleinspector.model.RoutineInfo
import com.github.wooju.oracleinspector.model.RoutineKind
import com.intellij.database.model.DasRoutine
import com.intellij.database.psi.DbRoutine

/**
 * DAS 캐시 기반 루틴 메타데이터.
 * - Arguments: 캐시에 있음
 * - Source / Errors: 캐시는 보장되지 않으므로 비워서 반환 (호출 측에서 isIncomplete 보고 JDBC 폴백)
 */
class DasRoutineRepository(
    private val routine: DbRoutine,
    private val schemaName: String,
    private val routineName: String,
) : RoutineMetadataRepository {

    override fun loadRoutine(): RoutineInfo {
        val kind = when (routine.routineKind) {
            DasRoutine.Kind.FUNCTION -> RoutineKind.FUNCTION
            DasRoutine.Kind.PROCEDURE -> RoutineKind.PROCEDURE
            else -> RoutineKind.UNKNOWN
        }

        val arguments = routine.arguments.mapIndexedNotNull { idx, arg ->
            ArgumentInfo(
                position = idx + 1,
                name = arg.name.ifBlank { null },
                direction = arg.argumentDirection?.name ?: "IN",
                dataType = arg.dataType?.typeName ?: "",
                defaultValue = arg.default,
            )
        }

        return RoutineInfo(
            schema = schemaName,
            name = routineName,
            kind = kind,
            source = "",   // 캐시에는 신뢰할 만한 source가 없음
            arguments = arguments,
            errors = emptyList(),
        )
    }
}
