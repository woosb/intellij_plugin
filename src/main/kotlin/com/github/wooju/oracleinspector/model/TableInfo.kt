package com.github.wooju.oracleinspector.model

data class ColumnInfo(
    val position: Int,
    val name: String,
    val comment: String?,
    val dataType: String,
    val size: Int?,
    val precision: Int?,
    val scale: Int?,
    val isNotNull: Boolean,
    val default: String?,
    val pkPosition: Int?,
    val isIndexed: Boolean,
)

data class KeyInfo(
    val name: String,
    val type: String,
    val columns: List<String>,
)

data class ForeignKeyInfo(
    val name: String,
    val columns: List<String>,
    val refSchema: String?,
    val refTable: String?,
    val refColumns: List<String>,
    val deleteRule: String?,
    val updateRule: String?,
)

data class IndexInfo(
    val name: String,
    val isUnique: Boolean,
    val columns: List<String>,
)

data class CheckInfo(
    val name: String,
    val columns: List<String>,
)

data class TriggerInfo(
    val name: String,
    val type: String?,       // 예: BEFORE EACH ROW / AFTER STATEMENT
    val event: String?,      // 예: INSERT OR UPDATE OR DELETE
    val status: String?,     // ENABLED / DISABLED
    val actionType: String?, // PL/SQL / CALL
)

data class TableInfo(
    val schema: String,
    val name: String,
    val comment: String?,
    val columns: List<ColumnInfo>,
    val keys: List<KeyInfo>,
    val foreignKeys: List<ForeignKeyInfo>,
    val indexes: List<IndexInfo>,
    val checks: List<CheckInfo>,
    val triggers: List<TriggerInfo> = emptyList(),
    /** ObjectKind == VIEW 면 true. DDL/탭 표시 분기에 사용. */
    val isView: Boolean = false,
    /** VIEW 일 때 ALL_VIEWS.TEXT 본문. JDBC 폴백에서만 채워짐. */
    val viewDefinition: String? = null,
) {
    /**
     * 캐시 데이터가 불완전한지 판정한다.
     * - 컬럼이 하나도 없거나
     * - 데이터 타입이 비어있거나 'UNKNOWN'인 컬럼이 있으면 불완전으로 본다.
     */
    fun isIncomplete(): Boolean {
        if (columns.isEmpty()) return true
        return columns.any { c ->
            c.dataType.isBlank() || c.dataType.equals("UNKNOWN", ignoreCase = true)
        }
    }
}
