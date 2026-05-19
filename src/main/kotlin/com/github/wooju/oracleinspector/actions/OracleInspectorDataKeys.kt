package com.github.wooju.oracleinspector.actions

import com.intellij.database.psi.DbDataSource
import com.intellij.openapi.actionSystem.DataKey

/**
 * 다이얼로그(Source Editor 포함) 안에서 액션이 트리거될 때 자기 컨텍스트를 노출하기 위한 키.
 * Action 측에서 e.getData(CURRENT_OWNER) 등으로 받을 수 있다.
 */
object OracleInspectorDataKeys {
    val CURRENT_OWNER: DataKey<String> = DataKey.create("OracleInspector.currentOwner")
    val CURRENT_DATA_SOURCE: DataKey<DbDataSource> = DataKey.create("OracleInspector.currentDataSource")
}
