package com.example.bmwenettest

/**
 * PRG-family *byte lengths* needed by the production offline RAW evidence gate.
 * They are NOT MG1CS201 SWFL-validated semantics, ECU sample times or an
 * allowlist of DIDs permitted for new UDS requests.
 *
 * Decoding/scaling, manual PRG previews and candidate descriptions live only
 * in the offline JVM test sources (DmePrgEvidenceLab).
 */
object DmePrgFamilyWidths {
    private val expectedWidths: Map<Int, Int> = mapOf(
        0x554C to 1,
        0x5776 to 1,
        0x5883 to 4,
        0x5885 to 4,
        0x5886 to 4,
        0x5888 to 4,
        0x4A36 to 1,
        0x554A to 2,
        0x4A37 to 4,
        0x4A38 to 4,
        0x4A39 to 4,
        0x4A3A to 4,
        0x5728 to 1,
        0x5819 to 2,
        0x5804 to 1,
        0x4A2D to 2,
        0x4A49 to 2,
        0x4A4A to 2,
        0x4A4C to 2,
        0x4A4D to 2
    )

    fun width(did: Int): Int? = expectedWidths[did]
}
