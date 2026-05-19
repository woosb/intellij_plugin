package com.github.wooju.oracleinspector.repository

import com.github.wooju.oracleinspector.model.PackageInfo

/**
 * IntelliJ DAS 캐시는 PL/SQL 패키지의 소스/Body/오류 정보를 일반적으로 가지고 있지 않다.
 * → 빈 PackageInfo만 반환하여 다이얼로그가 자동으로 JDBC 폴백하도록 한다.
 */
class DasPackageRepository(
    private val schemaName: String,
    private val packageName: String,
) : PackageMetadataRepository {
    override fun loadPackage(): PackageInfo =
        PackageInfo(
            schema = schemaName,
            name = packageName,
            specSource = "",
            bodySource = null,
            routines = emptyList(),
            errors = emptyList(),
        )
}
