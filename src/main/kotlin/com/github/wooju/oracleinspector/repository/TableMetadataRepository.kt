package com.github.wooju.oracleinspector.repository

import com.github.wooju.oracleinspector.model.TableInfo

/**
 * 테이블 메타데이터의 출처를 추상화한다.
 * 동일한 TableInfo를 반환하므로 호출자는 출처(캐시/JDBC)를 신경 쓰지 않는다.
 */
interface TableMetadataRepository {
    fun loadTable(): TableInfo
}
