package com.example.bmwenettest

/**
 * OFFLINE research catalog for candidate UDS 0x22 multi-DID decoding.
 *
 * No network transport, no scanner, no ECU activation, no learned DID widths.
 * In particular, the three new candidate IDs are NOT defined by the
 * previously verified DME8FF_R family width inventory.
 *
 * Never use an offline schema as a live diagnostic request authorization.
 */
object GroupedUds22Research {
    enum class Evidence { CATALOG_CANDIDATE, PRG_FAMILY_WIDTH, EXISTING_B_PLUS }
    enum class Approval { NOT_VERIFIED, PRG_ONLY, EXISTING_READ_ONLY }
    data class Candidate(
        val did:Int, val candidateMeaning:String, val familyWidth:Int?,
        val evidence:Evidence, val approval:Approval
    )
    val catalog:List<Candidate> = listOf(
        Candidate(0x4807,"RPM candidate",null,Evidence.CATALOG_CANDIDATE,Approval.NOT_VERIFIED),
        Candidate(0x5813,"LOAD candidate; not necessarily OBD 0x04",null,
            Evidence.CATALOG_CANDIDATE,Approval.NOT_VERIFIED),
        Candidate(0x580B,"MAP candidate",null,Evidence.CATALOG_CANDIDATE,Approval.NOT_VERIFIED),
        Candidate(0x4A36,"knock status code",1,Evidence.EXISTING_B_PLUS,Approval.EXISTING_READ_ONLY),
        Candidate(0x4A37,"knock reference 1",4,Evidence.EXISTING_B_PLUS,Approval.EXISTING_READ_ONLY),
        Candidate(0x4A38,"knock reference 2",4,Evidence.EXISTING_B_PLUS,Approval.EXISTING_READ_ONLY),
        Candidate(0x4A39,"knock reference 3",4,Evidence.EXISTING_B_PLUS,Approval.EXISTING_READ_ONLY),
        Candidate(0x4A3A,"knock reference 4",4,Evidence.EXISTING_B_PLUS,Approval.EXISTING_READ_ONLY),
        Candidate(0x4A49,"ignition 1",2,Evidence.EXISTING_B_PLUS,Approval.EXISTING_READ_ONLY),
        Candidate(0x4A4A,"ignition 2",2,Evidence.EXISTING_B_PLUS,Approval.EXISTING_READ_ONLY),
        Candidate(0x4A4C,"ignition 3",2,Evidence.EXISTING_B_PLUS,Approval.EXISTING_READ_ONLY),
        Candidate(0x4A4D,"ignition 4",2,Evidence.EXISTING_B_PLUS,Approval.EXISTING_READ_ONLY)
    )
    private val ids=catalog.map { it.did }.toSet()

    /** A working single DID or eight-DID B+ does not prove a new 12-DID group. */
    fun activeGroupAllowed():Boolean=false
    fun liveCandidateAllowed(did:Int):Boolean=false

    data class Schema(val widths:List<Pair<Int,Int>>) {
        init {
            require(widths.isNotEmpty() && widths.size<=12)
            require(widths.map { it.first }.distinct().size==widths.size)
            require(widths.all { it.first in ids && it.second in 1..32 })
        }
        val expectedPositiveBytes:Int get()=1+widths.sumOf { 2+it.second }
    }

    /**
     * Assemble test vector only. Does NOT authorize transmitting it on ENET.
     * Callers cannot generate a default new 12-DID request from the catalog
     * because missing widths cannot be supplied by this registry.
     */
    fun encodeOffline(schema:Schema):ByteArray {
        val result=ByteArray(1+schema.widths.size*2)
        result[0]=0x22
        for((index,pair) in schema.widths.withIndex()) {
            val did=pair.first
            result[1+index*2]=(did ushr 8).toByte()
            result[2+index*2]=did.toByte()
        }
        return result
    }

    /** Fully correlated SID, DID order, exact data widths and exact end. */
    fun decodeOffline(payload:ByteArray,schema:Schema):FastDidValidation.RawReply {
        if(payload.size>schema.expectedPositiveBytes)
            return FastDidValidation.RawReply.Invalid("EXTRA_OR_OVERSIZED")
        return FastDidValidation.parseExact(payload,schema.widths)
    }

    /** Only the nine already-read B+ family widths are known locally. */
    fun knownSchema():Schema=Schema(catalog.filter { it.familyWidth!=null }
        .map { it.did to it.familyWidth!! })
    fun unresolved():List<Candidate> = catalog.filter { it.familyWidth==null }

    /** No guesses of signedness, physical units or equivalence to OBD. */
    fun valueOfCandidate(did:Int,bytes:ByteArray):Double?=null
}
