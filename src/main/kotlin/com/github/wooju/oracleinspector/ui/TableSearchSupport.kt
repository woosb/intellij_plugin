package com.github.wooju.oracleinspector.ui

import com.intellij.ui.TableSpeedSearch
import com.intellij.ui.table.JBTable

/**
 * 우리 모든 JBTable에 인라인 검색을 활성화하기 위한 한 줄 헬퍼.
 *
 * 동작:
 *  - 테이블에 포커스가 있을 때 글자를 타이핑하면 매칭 행으로 점프하고 강조됨 (IntelliJ 관례).
 *  - 모든 보이는 셀의 toString() 값 대상으로 contains 매칭 (대소문자 무시).
 *  - 키보드 단축키 없이 그냥 타이핑만 시작하면 됨 — IDE 기본 동작과 동일.
 *
 * 사용:
 *   val tbl = JBTable(model).apply { ... }
 *   TableSearchSupport.install(tbl)
 */
object TableSearchSupport {
    fun install(table: JBTable) {
        TableSpeedSearch.installOn(table)
    }
}
