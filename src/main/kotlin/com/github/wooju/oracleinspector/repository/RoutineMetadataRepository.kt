package com.github.wooju.oracleinspector.repository

import com.github.wooju.oracleinspector.model.RoutineInfo

interface RoutineMetadataRepository {
    fun loadRoutine(): RoutineInfo
}
