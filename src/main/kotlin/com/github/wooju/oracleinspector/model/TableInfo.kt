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

data class TableInfo(
    val schema: String,
    val name: String,
    val comment: String?,
    val columns: List<ColumnInfo>,
    val keys: List<KeyInfo>,
    val foreignKeys: List<ForeignKeyInfo>,
    val indexes: List<IndexInfo>,
    val checks: List<CheckInfo>,
)
