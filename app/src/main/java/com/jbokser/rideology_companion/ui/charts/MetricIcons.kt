package com.jbokser.rideology_companion.ui.charts

import com.jbokser.rideology_companion.R
import com.jbokser.rideology_companion.data.ReportMetric

internal fun metricIcon(metric: ReportMetric): Int = when {
    metric.label.contains("temperature") -> R.drawable.ic_metric_temperature
    metric.label.contains("time", ignoreCase = true) -> R.drawable.ic_metric_time
    metric.label == "Course" -> R.drawable.ic_metric_compass
    metric.label == "Straight-line distance" -> R.drawable.ic_metric_straight_distance
    metric.label.contains("distance", ignoreCase = true) -> R.drawable.ic_metric_distance
    metric.label.contains("engine") -> R.drawable.ic_metric_engine
    metric.label.contains("acceleration") -> R.drawable.ic_metric_acceleration
    metric.label.contains("braking") -> R.drawable.ic_metric_brake
    else -> R.drawable.ic_metric_speed
}
