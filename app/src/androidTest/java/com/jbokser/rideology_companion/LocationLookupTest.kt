package com.jbokser.rideology_companion

import com.jbokser.rideology_companion.data.LocationDetails
import com.jbokser.rideology_companion.data.LocationLookup
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LocationLookupTest {
    @Test
    fun addressComponentsPreserveUnicodeAndSurviveCaching() {
        val details = LocationLookup.detailsFromResponse(
            """{"features":[{"properties":{"street":"Avenida Núñez","housenumber":123,"district":"Núñez","city":"Buenos Aires","state":"CABA","postcode":"C1429","country":"Argentina"}}]}"""
        )!!
        assertEquals("Avenida Núñez", details.street)
        assertEquals("123", details.houseNumber)
        assertEquals("Núñez", details.neighborhood)
        assertEquals("Address: Avenida Núñez 123", details.lines().first())
        assertEquals(details, LocationLookup.detailsFromProperties(JSONObject(LocationLookup.cacheJson(details))))
    }

    @Test
    fun partialAddressesOmitMissingFieldsWithoutSubstitutingNeighborhoods() {
        val details = LocationLookup.detailsFromResponse(
            """{"features":[{"properties":{"city":"Buenos Aires","street":"Cabildo","district":null,"postcode":"  "}}]}"""
        )!!
        assertNull(details.neighborhood)
        assertEquals(listOf("Address: Cabildo", "City: Buenos Aires"), details.lines())
        listOf(
            """{"features":[]}""",
            """{"features":[{"properties":{"district":"  "}}]}""",
            """{"features":[{"properties":{"district":null,"street":false,"city":{}}}]}"""
        ).forEach { assertNull(LocationLookup.detailsFromResponse(it)) }
        assertTrue(LocationDetails(houseNumber = "123").lines().isEmpty())
    }
}
