package com.phonetts.core.compare

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TournamentTest {
    private fun contender(id: String): Contender<String> = Contender(id, payload = "payload-$id")

    private fun contenders(vararg ids: String): List<Contender<String>> = ids.map(::contender)

    @Test
    fun rejectsAnEmptyContenderList() {
        assertFailsWith<IllegalArgumentException> { Tournament(emptyList<Contender<String>>()) }
    }

    @Test
    fun rejectsDuplicateContenderIds() {
        assertFailsWith<IllegalArgumentException> { Tournament(contenders("a", "b", "a")) }
    }

    @Test
    fun aSingleContenderIsImmediatelyTheUndefeatedChampion() {
        val tournament = Tournament(contenders("solo"))

        assertTrue(tournament.isComplete())
        assertNull(tournament.nextPairing())
        assertEquals(listOf(RankedEntry(contender("solo"), place = 1, winsRecorded = 0)), tournament.ranking())
    }

    @Test
    fun twoContendersPlayExactlyOneMatch() {
        val tournament = Tournament(contenders("a", "b"))

        val pairing = tournament.nextPairing()
        assertEquals(Pairing(round = 1, a = contender("a"), b = contender("b")), pairing)

        tournament.recordWinner("a")

        assertTrue(tournament.isComplete())
        assertEquals(
            listOf(
                RankedEntry(contender("a"), place = 1, winsRecorded = 1),
                RankedEntry(contender("b"), place = 2, winsRecorded = 0),
            ),
            tournament.ranking(),
        )
    }

    @Test
    fun recordWinnerRejectsAnIdThatIsNotInTheCurrentPairing() {
        val tournament = Tournament(contenders("a", "b"))

        assertFailsWith<IllegalArgumentException> { tournament.recordWinner("nope") }
    }

    @Test
    fun recordWinnerFailsWhenNoPairingIsAwaitingAJudgment() {
        val tournament = Tournament(contenders("a", "b"))
        tournament.recordWinner("a")

        assertFailsWith<IllegalStateException> { tournament.recordWinner("a") }
    }

    @Test
    fun rankingFailsBeforeTheBracketIsComplete() {
        val tournament = Tournament(contenders("a", "b"))

        assertFailsWith<IllegalStateException> { tournament.ranking() }
    }

    @Test
    fun eightEntryBracketProgressesRoundByRoundToASingleChampion() {
        val tournament = Tournament(contenders("e1", "e2", "e3", "e4", "e5", "e6", "e7", "e8"))

        // Round 1: four matches, all pairing adjacent seeds.
        assertEquals(Pairing(1, contender("e1"), contender("e2")), tournament.nextPairing())
        tournament.recordWinner("e1")
        assertEquals(Pairing(1, contender("e3"), contender("e4")), tournament.nextPairing())
        tournament.recordWinner("e3")
        assertEquals(Pairing(1, contender("e5"), contender("e6")), tournament.nextPairing())
        tournament.recordWinner("e5")
        assertEquals(Pairing(1, contender("e7"), contender("e8")), tournament.nextPairing())
        tournament.recordWinner("e7")
        assertTrue(!tournament.isComplete())

        // Round 2 (semifinals): winners of round 1, still in seed order.
        assertEquals(Pairing(2, contender("e1"), contender("e3")), tournament.nextPairing())
        tournament.recordWinner("e1")
        assertEquals(Pairing(2, contender("e5"), contender("e7")), tournament.nextPairing())
        tournament.recordWinner("e7")
        assertTrue(!tournament.isComplete())

        // Round 3 (final): the two semifinal winners.
        assertEquals(Pairing(3, contender("e1"), contender("e7")), tournament.nextPairing())
        tournament.recordWinner("e1")

        assertTrue(tournament.isComplete())
        assertNull(tournament.nextPairing())
        assertEquals(
            listOf(
                RankedEntry(contender("e1"), place = 1, winsRecorded = 3),
                RankedEntry(contender("e7"), place = 2, winsRecorded = 2),
                RankedEntry(contender("e3"), place = 3, winsRecorded = 1),
                RankedEntry(contender("e5"), place = 3, winsRecorded = 1),
                RankedEntry(contender("e2"), place = 5, winsRecorded = 0),
                RankedEntry(contender("e4"), place = 5, winsRecorded = 0),
                RankedEntry(contender("e6"), place = 5, winsRecorded = 0),
                RankedEntry(contender("e8"), place = 5, winsRecorded = 0),
            ),
            tournament.ranking(),
        )
    }

    @Test
    fun oddCountsGetGracefulByesThatAdvanceWithoutBeingJudged() {
        // 5 entries: round 1 has one bye (e5); the resulting 3 survivors give another bye next round.
        val tournament = Tournament(contenders("e1", "e2", "e3", "e4", "e5"))

        // e5 never appears in a pairing during round 1 - it advances on the bye.
        assertEquals(Pairing(1, contender("e1"), contender("e2")), tournament.nextPairing())
        tournament.recordWinner("e1")
        assertEquals(Pairing(1, contender("e3"), contender("e4")), tournament.nextPairing())
        tournament.recordWinner("e3")
        assertTrue(!tournament.isComplete())

        // Round 2: [e1, e3, e5] survive round 1 (e5 via bye) -> e1 vs e3, e5 byes again.
        assertEquals(Pairing(2, contender("e1"), contender("e3")), tournament.nextPairing())
        tournament.recordWinner("e3")
        assertTrue(!tournament.isComplete())

        // Round 3 (final): the round-2 winner (e3) vs the twice-byed e5.
        assertEquals(Pairing(3, contender("e3"), contender("e5")), tournament.nextPairing())
        tournament.recordWinner("e5")

        assertTrue(tournament.isComplete())
        val ranking = tournament.ranking()
        // e5 is champion but was never actually judged in a match until the final -> exactly 1 win,
        // proving byes are NOT counted as wins.
        assertEquals(RankedEntry(contender("e5"), place = 1, winsRecorded = 1), ranking[0])
        assertEquals(
            listOf(
                RankedEntry(contender("e5"), place = 1, winsRecorded = 1),
                RankedEntry(contender("e3"), place = 2, winsRecorded = 2),
                RankedEntry(contender("e1"), place = 3, winsRecorded = 1),
                RankedEntry(contender("e2"), place = 4, winsRecorded = 0),
                RankedEntry(contender("e4"), place = 4, winsRecorded = 0),
            ),
            ranking,
        )
    }

    @Test
    fun threeEntryBracketHandlesASingleByeCorrectly() {
        val tournament = Tournament(contenders("a", "b", "c"))

        // c byes straight to round 2.
        assertEquals(Pairing(1, contender("a"), contender("b")), tournament.nextPairing())
        tournament.recordWinner("a")
        assertEquals(Pairing(2, contender("a"), contender("c")), tournament.nextPairing())
        tournament.recordWinner("c")

        assertTrue(tournament.isComplete())
        assertEquals(
            listOf(
                RankedEntry(contender("c"), place = 1, winsRecorded = 1),
                RankedEntry(contender("a"), place = 2, winsRecorded = 1),
                RankedEntry(contender("b"), place = 3, winsRecorded = 0),
            ),
            tournament.ranking(),
        )
    }

    @Test
    fun pairingOrderIsFullyDeterminedBySeedOrderNotByAnyHiddenRandomness() {
        val runOnce = { winner: String ->
            val t = Tournament(contenders("x", "y", "z", "w"))
            val first = t.nextPairing()
            t.recordWinner(winner)
            first
        }

        assertEquals(runOnce("x"), runOnce("x"))
        assertEquals(runOnce("x"), runOnce("y")) // same first pairing regardless of who wins it
    }
}
