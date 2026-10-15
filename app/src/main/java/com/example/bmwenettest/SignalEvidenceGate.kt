package com.example.bmwenettest

import java.util.Locale

/**
 * OFFLINE evidence gate for the existing passive FAST DID audit CSV.
 * Neither this module nor its callers can schedule new ECU requests.
 *
 * Observed RAW, a PRG-family width match, and variation across rows do NOT
 * validate signal semantics for a specific SWFL, or prove fuel octane.
 */
internal object SignalEvidenceGate {
    const val CONTEXT_FRESH_MAX_MS=2_000L
    enum class Level {
        NO_RAW, FAMILY_WIDTH_CONFLICT, RAW_OBSERVED_WIDTH_UNKNOWN,
        FAMILY_WIDTH_MATCH_ONLY
    }
    data class DidEvidence(
        val did:Int,val rawRows:Int,val noRawRows:Int,val duplicates:Int,
        val widthConflicts:Int,val uniqueRaw:Int,val contextualFreshRows:Int,
        val tripCount:Int,val fuelSessionCount:Int,
        val rpmBinCount:Int,val mapBinCount:Int,val level:Level,
        val semantics:String="NOT_SWFL_VALIDATED"
    ) {
        val didHex:String get()=String.format(Locale.US,"%04X",did)
    }
    data class Report(val totalRows:Int,val validRows:Int,val invalidRows:Int,
                      val dids:List<DidEvidence>)
    private class Counter(val did:Int) {
        var rawRows=0;var noRawRows=0;var duplicates=0;var widthConflicts=0
        var contextualFreshRows=0
        val rawValues=mutableSetOf<String>()
        val keys=mutableSetOf<String>()
        val trips=mutableSetOf<String>()
        val fuelSessions=mutableSetOf<String>()
        val rpmBins=mutableSetOf<Int>()
        val mapBins=mutableSetOf<Int>()

        fun add(c:List<String>) {
            val raw=c[13]
            if(raw.isEmpty()) { noRawRows++;return }
            val key=c[1]+"|"+c[3]+"|"+c[21]+"|"+c[22]+"|"+c[10]+
                "|"+c[0]+"|"+raw
            if(!keys.add(key)) { duplicates++;return }
            rawRows++
            rawValues.add(raw)
            trips.add(c[1])
            fuelSessions.add(c[2])
            val familyWidth=DmePrgEvidenceLab.spec(did)?.width
            if(familyWidth!=null && raw.length/2!=familyWidth)widthConflicts++
            val age=c[20].toLongOrNull()
            val rpm=c[17].toDoubleOrNull()
            val load=c[18].toDoubleOrNull()
            val map=c[19].toDoubleOrNull()
            if(age!=null && age in 0..CONTEXT_FRESH_MAX_MS &&
                rpm?.isFinite()==true && load?.isFinite()==true &&
                map?.isFinite()==true) {
                contextualFreshRows++
                rpmBins.add((rpm/500.0).toInt())
                mapBins.add((map/20.0).toInt())
            }
        }

        fun finish():DidEvidence {
            val width=DmePrgEvidenceLab.spec(did)?.width
            val level=when {
                rawRows==0 -> Level.NO_RAW
                widthConflicts>0 -> Level.FAMILY_WIDTH_CONFLICT
                width==null -> Level.RAW_OBSERVED_WIDTH_UNKNOWN
                else -> Level.FAMILY_WIDTH_MATCH_ONLY
            }
            return DidEvidence(did,rawRows,noRawRows,duplicates,widthConflicts,
                rawValues.size,contextualFreshRows,trips.size,fuelSessions.size,
                rpmBins.size,mapBins.size,level)
        }
    }

    fun assess(lines:Sequence<String>):Report {
        val data=linkedMapOf<Int,Counter>()
        var total=0;var valid=0;var invalid=0
        for(line in lines) {
            if(line.isBlank() || line.startsWith("#") ||
                line==FastDidAuditCsv.HEADER)continue
            total++
            if(!FastDidAuditCsv.validateLine(line)) { invalid++;continue }
            val c=line.split(',')
            val did=c[10].toIntOrNull(16)
            if(did==null || did !in 0..0xFFFF) {
                invalid++;continue
            }
            valid++
            data.getOrPut(did) { Counter(did) }.add(c)
        }
        return Report(total,valid,invalid,
            data.values.map{it.finish()}.sortedBy{it.did})
    }

    fun offlineSummary(report:Report):String {
        val header="RAW evidence: valid="+report.validRows+
            "; invalid="+report.invalidRows+
            ". Width is PRG-family only; SWFL/meaning NOT validated."
        if(report.dids.isEmpty())return header+" No captured DID evidence."
        return header+"\n"+report.dids.joinToString("\n") { r ->
            "0x"+r.didHex+" "+r.level.name+
            " raw="+r.rawRows+" missing="+r.noRawRows+
            " duplicates="+r.duplicates+" width_conflicts="+r.widthConflicts+
            " distinct_raw="+r.uniqueRaw+" fresh_context="+r.contextualFreshRows+
            " trips="+r.tripCount+" fuel_sessions="+r.fuelSessionCount+
            " rpm_bins="+r.rpmBinCount+" map_bins="+r.mapBinCount
        }
    }
}
