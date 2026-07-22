package com.phonetts.core.compare

/**
 * One entry in a tournament: an opaque, caller-chosen [id] the engine uses to identify contenders
 * across rounds, plus [payload] — data of the caller's choice (e.g. a model+voice selection) that
 * the bracket engine itself never inspects. Keeping the payload generic is what keeps this class a
 * pure ranking algorithm rather than something coupled to `ModelDescriptor`/`Voice`.
 */
data class Contender<T>(
    val id: String,
    val payload: T,
)

/** One head-to-head matchup the caller must resolve by calling [Tournament.recordWinner]. */
data class Pairing<T>(
    val round: Int,
    val a: Contender<T>,
    val b: Contender<T>,
)

/**
 * One contender's place in the final ranking once the bracket completes. [place] uses standard
 * competition ranking (1, 2, 3, 3, 5, ...): contenders eliminated in the same round tie for the
 * same place, and the next distinct place skips past the tied slots. [winsRecorded] counts actual
 * judged wins only — a bye is an automatic advance, not a win, so it is never counted here.
 */
data class RankedEntry<T>(
    val contender: Contender<T>,
    val place: Int,
    val winsRecorded: Int,
)

/**
 * Deterministic single-elimination bracket engine (issue #11).
 *
 * Given a list of contenders, walks round by round pairing adjacent entries (0v1, 2v3, ...). A
 * round with an odd contender out gives that contender an automatic bye — it advances without a
 * match, exactly like a real single-elimination bracket — so any entry count (not just a power of
 * two) is handled gracefully. The caller drains matches by repeatedly calling [nextPairing] and
 * [recordWinner] until [isComplete] is true, then reads [ranking] for the final standings.
 *
 * Pure and fully deterministic: the only thing that decides pairing order is the order of
 * [entries] as constructed. This class never calls `Math.random()`/`System.currentTimeMillis()`/
 * any other non-injected source of entropy (forbidden in `:core`, see CLAUDE.md) — a caller that
 * wants a BLIND, randomized bracket (so early rounds aren't predictable from input order) shuffles
 * or permutes [entries] itself, with its own seeded `Random`, before construction. That keeps the
 * randomness at the edge (`:app`) and this class exhaustively testable.
 */
class Tournament<T>(entries: List<Contender<T>>) {
    init {
        require(entries.isNotEmpty()) { "Tournament needs at least one contender" }
        require(entries.map { it.id }.toSet().size == entries.size) { "contender ids must be unique" }
    }

    // The order contenders were seeded in, preserved for stable tie-breaking in ranking().
    private val seedOrder: List<Contender<T>> = entries.toList()

    // Round number the loser of a pairing was knocked out in. A contender that reaches the end
    // without ever losing (the champion) is absent from this map.
    private val eliminatedInRound = mutableMapOf<String, Int>()

    // Actual judged wins per contender id (byes don't count, see RankedEntry docs).
    private val winsById = mutableMapOf<String, Int>()

    private var roundNumber = 1
    private var roundQueue: ArrayDeque<QueuedPairing<T>> = ArrayDeque()
    private var nextRoundSlots: MutableList<Contender<T>?> = mutableListOf()
    private var championEntry: Contender<T>? = null

    init {
        startRound(seedOrder)
    }

    /** Ties a queued [pairing] to the slot its winner will occupy in the next round. */
    private data class QueuedPairing<T>(val pairing: Pairing<T>, val slotIndex: Int)

    /** The next unresolved matchup, or `null` once the bracket is [isComplete]. */
    fun nextPairing(): Pairing<T>? = roundQueue.firstOrNull()

    /** True once a single undefeated contender remains. */
    fun isComplete(): Boolean = championEntry != null

    /**
     * Record [winnerId] — which must be one of [nextPairing]'s two contenders — as the winner of
     * the current matchup, and advance the bracket. Once every matchup in the current round is
     * resolved, the next round is built automatically (skipping straight to a champion when only
     * one contender remains).
     */
    fun recordWinner(winnerId: String) {
        val queued =
            roundQueue.removeFirstOrNull() ?: error("no pairing awaiting a winner — call nextPairing() first")
        val pairing = queued.pairing
        require(winnerId == pairing.a.id || winnerId == pairing.b.id) {
            "winnerId '$winnerId' is not a contender in the current pairing"
        }
        val winner = if (winnerId == pairing.a.id) pairing.a else pairing.b
        val loser = if (winner === pairing.a) pairing.b else pairing.a

        eliminatedInRound[loser.id] = roundNumber
        winsById[winner.id] = (winsById[winner.id] ?: 0) + 1
        nextRoundSlots[queued.slotIndex] = winner

        if (roundQueue.isNotEmpty()) return
        val survivors = nextRoundSlots.map { checkNotNull(it) { "unresolved slot at end of round" } }
        roundNumber++
        startRound(survivors)
    }

    /**
     * The final standings, best to worst. Only callable once [isComplete]. The champion is place
     * 1; everyone else is placed by how late they were eliminated, with same-round eliminations
     * tied at the same place (standard competition ranking) and seed order breaking ties within a
     * tier, since the bracket invents no further signal to separate them.
     */
    fun ranking(): List<RankedEntry<T>> {
        val champion = checkNotNull(championEntry) { "tournament is not complete yet" }
        val seedIndex = seedOrder.withIndex().associate { (index, contender) -> contender.id to index }
        val eliminated =
            seedOrder
                .filter { it.id != champion.id }
                .sortedWith(
                    compareByDescending<Contender<T>> { eliminatedInRound.getValue(it.id) }
                        .thenBy { seedIndex.getValue(it.id) },
                )

        val ranked = mutableListOf(rankedEntry(champion, place = 1))
        var place = 2
        var index = 0
        while (index < eliminated.size) {
            val roundHere = eliminatedInRound.getValue(eliminated[index].id)
            val tier = eliminated.drop(index).takeWhile { eliminatedInRound.getValue(it.id) == roundHere }
            tier.forEach { ranked += rankedEntry(it, place) }
            place += tier.size
            index += tier.size
        }
        return ranked
    }

    private fun rankedEntry(
        contender: Contender<T>,
        place: Int,
    ): RankedEntry<T> = RankedEntry(contender, place, winsById[contender.id] ?: 0)

    // Build the pairings for a fresh round out of [alive] contenders, in their current order.
    // Adjacent contenders are paired (0v1, 2v3, ...); a trailing unpaired contender gets a bye and
    // is placed directly into next round's slots without a match. A single survivor crowns the
    // champion instead of starting another round.
    private fun startRound(alive: List<Contender<T>>) {
        if (alive.size == 1) {
            championEntry = alive.single()
            return
        }

        val slotCount = (alive.size + 1) / 2
        val slots = MutableList<Contender<T>?>(slotCount) { null }
        val queue = ArrayDeque<QueuedPairing<T>>()

        var slot = 0
        var i = 0
        while (i < alive.size) {
            val a = alive[i]
            val b = alive.getOrNull(i + 1)
            if (b == null) {
                slots[slot] = a
            } else {
                queue.addLast(QueuedPairing(Pairing(roundNumber, a, b), slot))
            }
            slot++
            i += 2
        }

        roundQueue = queue
        nextRoundSlots = slots
    }
}
