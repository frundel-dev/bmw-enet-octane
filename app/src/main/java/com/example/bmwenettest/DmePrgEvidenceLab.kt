package com.example.bmwenettest

import java.util.Locale

/**
 * Offline PRG interpretation only. This file cannot open a socket or send ECU
 * commands. DME8FF_R.prg is a DME8xx family description, not SWFL-specific proof.
 */
internal object DmePrgEvidenceLab {
    enum class VehicleEvidence { NO_RAW, APP_RAW_ONLY, DERIVED_CSV_ONLY }
    enum class Status { PREVIEW_ONLY, NEGATIVE_NRC, INVALID, STRUCTURE_LOCKED }
    data class Spec(
        val did:Int, val label:String, val group:String, val width:Int?,
        val unit:String, val evidence:VehicleEvidence=VehicleEvidence.NO_RAW
    ) {
        val didHex:String get()=String.format(Locale.US,"%04X",did)
    }
    data class Interpretation(
        val status:Status, val did:Int, val rawHex:String,
        val value:String?=null, val detail:String
    )

    // Primary PRG: 7,458,486 bytes; SHA-256 f227991a...7b0f20.
    // NONE of these definitions is yet SWFL 00005D55-070.060.007 validated.
    val specs:List<Spec> = listOf(
        Spec(0x554C,"Среднее запаздывание KR","KNOCK_RETARD",1,"°KW"),
        Spec(0x5776,"Адаптированный фактор качества DME","FUEL_ADAPTATION",1,"безразмерный"),
        Spec(0x4035,"История KQE QB/KM","DME_FUEL_HISTORY",null,"структура не проверена"),
        Spec(0x5883,"Knock-value цилиндр 1","KNOCK_VALUE",4,"V·ms"),
        Spec(0x5885,"Knock-value цилиндр 2","KNOCK_VALUE",4,"V·ms"),
        Spec(0x5886,"Knock-value цилиндр 3","KNOCK_VALUE",4,"V·ms"),
        Spec(0x5888,"Knock-value цилиндр 4","KNOCK_VALUE",4,"V·ms"),
        Spec(0x4A36,"Статус обнаружения knock","KNOCK_STATUS",1,"код",VehicleEvidence.APP_RAW_ONLY),
        Spec(0x554A,"Адаптация угла KR","KNOCK_ADAPTATION",2,"единица не подтверждена",VehicleEvidence.APP_RAW_ONLY),
        Spec(0x4A37,"Опорный knock-уровень цилиндр 1","REFERENCE_KNOCK",4,"V·ms",VehicleEvidence.DERIVED_CSV_ONLY),
        Spec(0x4A38,"Опорный knock-уровень цилиндр 2","REFERENCE_KNOCK",4,"V·ms",VehicleEvidence.DERIVED_CSV_ONLY),
        Spec(0x4A39,"Опорный knock-уровень цилиндр 3","REFERENCE_KNOCK",4,"V·ms",VehicleEvidence.DERIVED_CSV_ONLY),
        Spec(0x4A3A,"Опорный knock-уровень цилиндр 4","REFERENCE_KNOCK",4,"V·ms",VehicleEvidence.DERIVED_CSV_ONLY),
        Spec(0x407F,"История superknock","SUPERKNOCK_HISTORY",null,"структура не проверена"),
        Spec(0x5728,"Статус superknock","SUPERKNOCK_STATUS",1,"код"),
        Spec(0x5819,"RPM за цилиндровый сегмент","ENGINE_SPEED",2,"об/мин"),
        Spec(0x5804,"Относительная воздушная масса","RELATIVE_AIR_MASS",1,"%"),
        Spec(0x4A2D,"Абсолютное давление MAP","MANIFOLD_PRESSURE",2,"кПа"),
        Spec(0x4A49,"Угол зажигания цилиндр 1","IGNITION",2,"°KW",VehicleEvidence.DERIVED_CSV_ONLY),
        Spec(0x4A4A,"Угол зажигания цилиндр 2","IGNITION",2,"°KW",VehicleEvidence.DERIVED_CSV_ONLY),
        Spec(0x4A4C,"Угол зажигания цилиндр 3","IGNITION",2,"°KW",VehicleEvidence.DERIVED_CSV_ONLY),
        Spec(0x4A4D,"Угол зажигания цилиндр 4","IGNITION",2,"°KW",VehicleEvidence.DERIVED_CSV_ONLY)
    )
    private val byDid=specs.associateBy { it.did }
    fun spec(did:Int):Spec?=byDid[did]

    /**
     * User SWFL acknowledgement and a family PRG do not authorize a read.
     * Vehicle activation needs a separate, reviewed immutable allowlist.
     */
    fun canIssueNewDidRequest(did:Int,parked:Boolean,swflAcknowledged:Boolean):Boolean = false

    fun interpretHex(did:Int,rawUdsHex:String):Interpretation {
        val spec=byDid[did]
            ?: return Interpretation(Status.INVALID,did,"",detail="DID вне PRG реестра")
        if(spec.width==null)
            return Interpretation(Status.STRUCTURE_LOCKED,did,"",
                detail="Структурированный PRG job: полная схема не проверена. Offline preview отключён.")
        val compact=rawUdsHex.replace(Regex("[\\s:_-]"),"")
        if(compact.isEmpty() || compact.length>128 || compact.length%2!=0 ||
            !compact.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })
            return Interpretation(Status.INVALID,did,"",
                detail="Нужны чётные hex-байты UDS без HSFZ-заголовка; максимум 64 байта")
        val uds=ByteArray(compact.length/2) { index ->
            compact.substring(index*2,index*2+2).toInt(16).toByte()
        }
        val rawHex=compact.uppercase(Locale.US)
        return when(val parsed=FastDidValidation.parseExact(uds,listOf(did to spec.width))) {
            is FastDidValidation.RawReply.Negative ->
                Interpretation(Status.NEGATIVE_NRC,did,rawHex,
                    detail="UDS 0x7F 0x22; NRC=0x%02X; измеренного значения нет".format(
                        Locale.US,parsed.nrc))
            is FastDidValidation.RawReply.Invalid ->
                Interpretation(Status.INVALID,did,rawHex,
                    detail="Неверный SID/DID/размер: "+parsed.reason+"; декодирование запрещено")
            is FastDidValidation.RawReply.Positive ->
                Interpretation(Status.PREVIEW_ONLY,did,rawHex,
                    decodePrgPreview(did,parsed.values.getValue(did)),
                    "Только offline масштаб PRG. SWFL, источник RAW, свежесть и физическая семантика не подтверждены. Не RON и не Fuel Score.")
        }
    }

    private fun decodePrgPreview(did:Int,raw:ByteArray):String {
        val unsigned=raw.fold(0L) { value,byte ->
            (value shl 8) or (byte.toLong() and 255L)
        }
        val bits=raw.size*8
        val signed=if(unsigned and (1L shl (bits-1))!=0L)
            unsigned-(1L shl bits) else unsigned
        fun number(value:Double,digits:Int=5):String=
            String.format(Locale.US,"%."+digits+"f",value)
        return when(did) {
            0x554C -> number(signed*0.375,3)+" °KW (реакция KR, не событие knock)"
            0x5776 -> number(unsigned/256.0,6)+" (фактор DME, не лабораторный RON)"
            0x554A -> number(signed*0.1,2)+" (адаптация KR, единица спорна)"
            0x4A36,0x5728 -> "код "+unsigned+" (семантика флага не проверена)"
            0x4A37,0x4A38,0x4A39,0x4A3A,
            0x5883,0x5885,0x5886,0x5888 ->
                number(unsigned*0.05/65536.0,7)+" V·ms"+
                    if(did in 0x4A37..0x4A3A)" (reference, не knock event)" else
                        " (knock-value, не доказанное событие)"
            0x5819 -> number(signed/2.0,2)+" об/мин (цилиндровое окно)"
            0x5804 -> number(unsigned*100.0/256.0,3)+" % (масса, не произвольный LOAD)"
            0x4A2D -> number(unsigned*20.0/256.0/10.0,5)+" кПа ABS"
            0x4A49,0x4A4A,0x4A4C,0x4A4D ->
                number(signed/10.0,2)+" °KW (угол зажигания, не KR)"
            else -> "Нет формулы"
        }
    }
}
