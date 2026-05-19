package com.github.wooju.oracleinspector.model

/** ALL_SEQUENCES 한 행. 큰 수치는 String으로 보관 (NUMBER(27,0) 범위). */
data class SequenceInfo(
    val schema: String,
    val name: String,
    val minValue: String?,
    val maxValue: String?,
    val incrementBy: String?,
    val cycle: Boolean,
    val ordered: Boolean,
    val cacheSize: Long?,
    val lastNumber: String?,
)

/** ALL_SYNONYMS 한 행. */
data class SynonymInfo(
    val schema: String,
    val name: String,
    val refOwner: String?,
    val refName: String?,
    val dbLink: String?,
)
