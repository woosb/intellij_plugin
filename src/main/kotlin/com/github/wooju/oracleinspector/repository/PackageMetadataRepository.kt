package com.github.wooju.oracleinspector.repository

import com.github.wooju.oracleinspector.model.PackageInfo

/** PACKAGE 메타데이터 출처(캐시/JDBC)를 추상화한 인터페이스. */
interface PackageMetadataRepository {
    fun loadPackage(): PackageInfo
}
