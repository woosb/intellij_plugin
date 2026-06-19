package com.github.wooju.oracleinspector.cache

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * 프로젝트 단위 메모리 캐시 — JDBC 폴백으로 가져온 메타데이터 DTO를 기억해
 * 같은 객체를 다시 열 때 JDBC 재조회를 피한다.
 *
 * 설계 원칙:
 *  - **IntelliJ DAS 캐시를 대체하지 않는다.** DAS가 완전하면 다이얼로그는 그대로 DAS를
 *    1순위로 읽고 여기 들르지 않는다. 이 캐시는 DAS가 못 채우는(=JDBC로 가져온) 결과만 기억한다.
 *  - 캐시 대상은 **메타데이터 DTO 뿐**. 테이블 Data / V$ live 데이터는 절대 담지 않는다.
 *  - on-heap. 디스크 영속화 없음. 프로젝트/IDE 종료 시 소멸.
 *  - LRU 상한(200)으로 메모리 무한 증가를 원천 차단.
 *
 * 키는 (데이터소스 uniqueId, 스키마, 이름, 종류) 4-튜플 — 서로 다른 데이터소스/스키마/객체종류가
 * 절대 섞이지 않도록. uniqueId는 IntelliJ가 부여하는 안정적 식별자(이름 변경/중복에 영향 없음).
 */
@Service(Service.Level.PROJECT)
class OracleMetadataCache {

    data class Key(
        val dataSourceId: String,
        val schema: String,
        val name: String,
        val kind: String,
    )

    /** dto = 캐시된 메타 DTO(RoutineInfo/PackageInfo/…), lastDdlTime = 가져온 시점의 ALL_OBJECTS.LAST_DDL_TIME 기준선. */
    data class Entry(
        val dto: Any,
        val lastDdlTime: String?,
    )

    // accessOrder=true → 접근 순서 기반 LRU. removeEldestEntry로 상한 enforce.
    private val map = object : LinkedHashMap<Key, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<Key, Entry>): Boolean = size > MAX_ENTRIES
    }

    @Synchronized
    fun get(key: Key): Entry? = map[key]

    @Synchronized
    fun put(key: Key, dto: Any, lastDdlTime: String?) {
        map[key] = Entry(dto, lastDdlTime)
    }

    @Synchronized
    fun invalidate(key: Key) {
        map.remove(key)
    }

    companion object {
        private const val MAX_ENTRIES = 200

        fun getInstance(project: Project): OracleMetadataCache = project.service()

        fun key(dataSourceId: String?, schema: String, name: String, kind: String) = Key(
            dataSourceId = dataSourceId ?: "?",
            schema = schema.uppercase(),
            name = name.uppercase(),
            kind = kind,
        )
    }
}
