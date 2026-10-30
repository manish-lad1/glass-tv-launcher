package com.manishlad.glasslauncher.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class F1ParseTest {
    private val next = JSONObject("""
        {"MRData":{"RaceTable":{"season":"2026","Races":[{"season":"2026","round":"19",
         "raceName":"Mexico City Grand Prix",
         "Circuit":{"circuitName":"Autódromo Hermanos Rodríguez","Location":{"locality":"Mexico City","country":"Mexico"}},
         "date":"2026-11-01","time":"20:00:00Z",
         "FirstPractice":{"date":"2026-10-30","time":"18:30:00Z"},
         "SecondPractice":{"date":"2026-10-30","time":"22:00:00Z"},
         "ThirdPractice":{"date":"2026-10-31","time":"17:30:00Z"},
         "Qualifying":{"date":"2026-10-31","time":"21:00:00Z"}}]}}}
    """.trimIndent())

    @Test fun parsesSessionsInOrderWithRaceLast() {
        val race = F1Parse.race(next.getJSONObject("MRData").getJSONObject("RaceTable").getJSONArray("Races").getJSONObject(0))
        assertEquals(19, race.round)
        assertEquals(listOf("Practice 1", "Practice 2", "Practice 3", "Qualifying", "Race"), race.sessions.map { it.name })
        assertEquals(Instant.parse("2026-11-01T20:00:00Z"), race.race.start)
    }

    @Test fun nextSessionIncludesOneInProgress() {
        val race = F1Parse.race(next.getJSONObject("MRData").getJSONObject("RaceTable").getJSONArray("Races").getJSONObject(0))
        // 30 min into Qualifying: still "next" (in progress).
        assertEquals("Qualifying", race.nextSession(Instant.parse("2026-10-31T21:30:00Z"))?.name)
        // 3 h after Qualifying started: the Race is next.
        assertEquals("Race", race.nextSession(Instant.parse("2026-11-01T00:00:00Z"))?.name)
    }

    @Test fun parsesDriverStandings() {
        val o = JSONObject("""
            {"MRData":{"StandingsTable":{"StandingsLists":[{"round":"18","DriverStandings":[
             {"position":"1","points":"365","Driver":{"givenName":"Andrea Kimi","familyName":"Antonelli","code":"ANT"},
              "Constructors":[{"name":"Mercedes"}]}]}]}}}
        """.trimIndent())
        val (round, list) = F1Parse.drivers(o)
        assertEquals(18, round)
        assertEquals(F1Standing(1, "Andrea Kimi Antonelli", "ANT", "Mercedes", "365"), list.single())
    }
}
