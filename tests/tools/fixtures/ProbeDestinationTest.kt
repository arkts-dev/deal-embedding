package dev.deal.embedding

// Executed evidence for the probe transport's own destination rule: the same public-address policy the
// shipped isolate binding refuses with is applied here to a parsed address, never to its spelling. The
// verdicts come from tests/tools/fixtures/probe-destinations.txt, the one list this item also drives the
// shipped binding with. The refused blocks are the ones a public JSON probe cannot reach (documentation,
// benchmarking, Teredo, the ORCHID and DET blocks, the reserved IPv4 ranges); space the transport only
// fails to reach at connect time is admitted, which is the distinction docs/spec-v0.8.md:480 draws.
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path

object ProbeDestinationTest {
    /** Every listed address with the verdict it must receive, in file order. */
    fun destinations(path: Path): List<Pair<String, Boolean>> = Files.readAllLines(path)
        .map { it.substringBefore('#').trim() }
        .filter { it.isNotEmpty() }
        .map { line ->
            val parts = line.split(Regex("\\s+"))
            require(parts.size == 2 && parts[1] in setOf("public", "private")) { "unreadable destination line: $line" }
            parts[0] to (parts[1] == "public")
        }

    /** The smallest list that still proves both families and both directions, so an emptied list fails. */
    private const val FLOOR = 40

    fun run(path: Path): String {
        var checked = 0
        val listed = destinations(path)
        require(listed.size >= FLOOR) { "the destination list carries ${listed.size} entries, not the ${FLOOR} required" }
        require(listed.any { it.second } && listed.any { !it.second }) { "the destination list must carry both verdicts" }
        require(listed.any { it.first.contains(":") } && listed.any { !it.first.contains(":") }) {
            "the destination list must carry both address families"
        }
        for ((text, expected) in listed) {
            val actual = PublicProbeTransport.publicAddress(InetAddress.getByName(text))
            if (actual != expected) {
                throw IllegalStateException("probe destination $text: expected public=$expected, got $actual")
            }
            checked++
        }
        // A destination is public only when every address the name resolved to is, and no empty resolution
        // counts as public.
        val public = InetAddress.getByName("8.8.8.8")
        val private = InetAddress.getByName("10.0.0.1")
        val public6 = InetAddress.getByName("2606:4700:4700::1111")
        check(PublicProbeTransport.publicDestination(listOf(public))) { "a public address is a public destination" }
        check(PublicProbeTransport.publicDestination(listOf(public6))) { "a public IPv6 address is a public destination" }
        check(!PublicProbeTransport.publicDestination(listOf(public, private))) { "one nonpublic address refuses the destination" }
        check(!PublicProbeTransport.publicDestination(emptyList())) { "an empty resolution is not a public destination" }
        checked += 4
        return "probe destination: $checked addresses and destinations classified"
    }
}

fun main(args: Array<String>) {
    println(ProbeDestinationTest.run(Path.of(args[0])))
}
