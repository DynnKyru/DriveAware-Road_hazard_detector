package com.DynnKyru.driveAware

data class DetectionRecord(
    val anomalyType: String,
    val imagePath: String,
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long,
    var isReported: Boolean = false,
    val address: String = "" // ✅ NEW: stores human-readable location
)

