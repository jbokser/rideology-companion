package com.jbokser.rideology_companion.data

/** Optional address components returned for the nearest mapped location. */
data class LocationDetails(
    val street: String? = null,
    val houseNumber: String? = null,
    val neighborhood: String? = null,
    val city: String? = null,
    val state: String? = null
) {
    fun lines(): List<String> = buildList {
        street?.let { add("Address: " + listOfNotNull(it, houseNumber).joinToString(" ")) }
        neighborhood?.let { add("Neighborhood: $it") }
        city?.let { add("City: $it") }
        state?.let { add("State: $it") }
    }
}
