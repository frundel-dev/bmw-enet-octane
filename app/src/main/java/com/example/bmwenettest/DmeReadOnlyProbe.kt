package com.example.bmwenettest

import java.util.Locale

/**
 * Read-only UDS $22 candidate identifiers from the DME8FF_R diagnostic
 * description. The compiled PRG is not executed: raw DID responses and
 * tentative interpretations are logged for later verification on the car.
 *
 * The 0x554A reading describes knock-control *adaptation*, not instantaneous
 * per-cylinder knock retard. The 0x4530..33 readings are injection MODES,
 * never injector pulse width. No result is used in Fuel Score.
 */
object DmeReadOnlyProbe {
    data class Spec(val did:Int,val title:String,val description:String)
    data class Result(
        val spec:Spec,
        val status:String,
        val rawHex:String="",
        val negativeCode:String="",
        val decodedCandidate:String="",
        val note:String=""
    ) {
        fun shortText():String = when(status) {
            "OK" -> "${spec.title}: "+(decodedCandidate.ifEmpty { "RAW $rawHex" })
            "UNSUPPORTED" -> "${spec.title}: не поддерживается (NRC $negativeCode)"
            "NEGATIVE" -> "${spec.title}: NRC $negativeCode"
            "NO_RESPONSE" -> "${spec.title}: нет ответа"
            else -> "${spec.title}: $status"
        }
    }

    val specs=listOf(
        Spec(0x554A,"KR адапт.","ZW_AEND_ADAP_KR: адаптация угла детонационного регулирования"),
        Spec(0x4A85,"Мультипл. B1","ADAPTION_MULTIPLIKATIV_BANK1: топливная адаптация"),
        Spec(0x4A9D,"Мультипл. alt","KORR_MULT_GEMISCHADAP: альтернатива топливной адаптации"),
        Spec(0x4A36,"Knock статус","STATUS_KLOPFEN: статус детонационного регулирования"),
        Spec(0x4530,"Впрыск ц1","AKT_EINSPRITZMODUS_ZYL1: режим впрыска, не длительность"),
        Spec(0x4531,"Впрыск ц2","AKT_EINSPRITZMODUS_ZYL2: режим впрыска, не длительность"),
        Spec(0x4532,"Впрыск ц3","AKT_EINSPRITZMODUS_ZYL3: режим впрыска, не длительность"),
        Spec(0x4533,"Впрыск ц4","AKT_EINSPRITZMODUS_ZYL4: режим впрыска, не длительность")
    )

    fun didHex(did:Int):String=String.format(Locale.US,"%04X",did)
    fun hex(data:ByteArray):String=data.joinToString("") {
        String.format(Locale.US,"%02X",it.toInt() and 255)
    }

    /** Parse the unmodified positive or negative UDS payload (without HSFZ). */
    fun parse(spec:Spec,payloads:List<ByteArray>):Result {
        val hi=(spec.did ushr 8) and 255
        val lo=spec.did and 255
        val positive=payloads.firstOrNull { p ->
            p.size>=3 && (p[0].toInt() and 255)==0x62 &&
                (p[1].toInt() and 255)==hi && (p[2].toInt() and 255)==lo
        }
        if(positive!=null) {
            val raw=positive.copyOfRange(3,positive.size)
            val decoded=decode(spec.did,raw)
            return if(decoded==null) Result(spec,"INVALID_LENGTH",hex(raw),
                note="Ответ 0x62 есть, но формат не подтверждён")
            else Result(spec,"OK",hex(raw),decodedCandidate=decoded,
                note="Предварительная интерпретация; обязательно сверить с ISTA/PRG")
        }
        val negative=payloads.firstOrNull { p ->
            p.size>=3 && (p[0].toInt() and 255)==0x7F &&
                (p[1].toInt() and 255)==0x22
        }
        if(negative!=null) {
            val nrc=negative[2].toInt() and 255
            return Result(spec,if(nrc==0x31 || nrc==0x11 || nrc==0x12)
                "UNSUPPORTED" else "NEGATIVE",hex(negative),didHex(nrc).takeLast(2),
                note=when(nrc) {
                    0x31 -> "Request out of range; запрос не поддержан текущим DME"
                    0x22 -> "Условия выполнения не выполнены"
                    0x33 -> "Доступ ограничен; security access НЕ запрашиваем"
                    else -> "Отрицательный ответ UDS"
                })
        }
        return if(payloads.isEmpty()) Result(spec,"NO_RESPONSE",note="Нет ответа в интервал ожидания")
            else Result(spec,"UNEXPECTED",hex(payloads[0]),
                note="Ответ не соответствует запрошенному DID; декодирование отменено")
    }

    private fun decode(did:Int,b:ByteArray):String? {
        fun u16()=((b[0].toInt() and 255) shl 8) or (b[1].toInt() and 255)
        return when(did) {
            0x554A -> {
                if(b.size!=2)return null
                val unsigned=u16()
                val signed=if(unsigned>=32768)unsigned-65536 else unsigned
                String.format(Locale.US,"%.1f°* (адаптация)",signed*0.1)
            }
            0x4A85,0x4A9D -> {
                if(b.size!=2)return null
                val factor=u16()*2.0/65536.0
                String.format(Locale.US,"%.5f×* (из сырых байт)",factor)
            }
            0x4A36,0x4530,0x4531,0x4532,0x4533 -> {
                if(b.size!=1)return null
                "${b[0].toInt() and 255} (код)"
            }
            else -> null
        }
    }

    fun overview(results:Map<Int,Result>,tested:Int):String {
        val items=listOf(0x554A,0x4A85,0x4A9D,0x4A36)
        val rows=items.map { did ->
            results[did]?.shortText() ?: "${specs.first { it.did==did }.title}: ожидаем"
        }
        val inject=specs.filter { it.did in 0x4530..0x4533 }
            .joinToString(" · ") { s ->
                val result=results[s.did]
                "ц${s.did-0x452F}:${when(result?.status) {
                    "OK"->result?.decodedCandidate?.substringBefore(' ') ?: "?"
                    "UNSUPPORTED"->"—"
                    null->"?"
                    else->result.status
                }}"
            }
        return "DME UDS 0x22 • проверено $tested/${specs.size}\n"+
            rows.joinToString("\n")+"\n"+
            "Впрыск (режим, не время): $inject"
    }
}
