package com.github.wooju.oracleinspector.model

/** 패키지 내부의 서브프로그램 한 건 (ALL_PROCEDURES). */
data class PackageRoutine(
    val name: String,
    val overload: String?,
    val kind: String,   // PROCEDURE / FUNCTION
)

/** 패키지 컴파일 오류 한 건. Spec/Body 어느 쪽인지도 함께 보관. */
data class PackageError(
    val sourceType: String, // "PACKAGE" or "PACKAGE BODY"
    val line: Int,
    val position: Int,
    val text: String,
)

/**
 * PL/SQL PACKAGE 정보 DTO.
 * - specSource: ALL_SOURCE TYPE='PACKAGE'
 * - bodySource: ALL_SOURCE TYPE='PACKAGE BODY' (없으면 null)
 * - routines:   ALL_PROCEDURES WHERE OBJECT_NAME=<package> AND PROCEDURE_NAME IS NOT NULL
 * - errors:     ALL_ERRORS WHERE NAME=<package> AND TYPE IN ('PACKAGE','PACKAGE BODY')
 */
data class PackageInfo(
    val schema: String,
    val name: String,
    val specSource: String,
    val bodySource: String?,
    val routines: List<PackageRoutine>,
    val errors: List<PackageError>,
) {
    /** 모든 정보가 비어있으면 캐시 폴백을 트리거. */
    fun isIncomplete(): Boolean =
        specSource.isBlank() && bodySource.isNullOrBlank() && routines.isEmpty()
}
