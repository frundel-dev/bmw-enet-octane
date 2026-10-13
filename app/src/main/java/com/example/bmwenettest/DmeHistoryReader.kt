package com.example.bmwenettest

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import java.io.File
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * One-shot parked-vehicle diagnostic capture. A separate socket is opened
 * ONLY while the continuous logger is stopped. Never read its primary socket
 * or initiate a second VXSCAN TCP session.
 *
 * All requests are read-only UDS 0x19 and 0x22. No session switch, security
 * access, clearing faults, writes, routines, or reset.
 */
internal class DmeHistoryReader(private val context:Context) {
    data class Outcome(val file:File,val summary:String,val successful:Boolean)
    private data class Target(val network:Network,val transport:String,val host:String)
    private data class Reply(val status:String,val payload:ByteArray)
    private class Cancelled:IOException("Сбор истории отменён")

    private fun verifyAllowed(cancel:()->Boolean) {
        if(cancel())throw Cancelled()
        if(EnetLoggerService.isLoggerActive)
            throw IOException("Останови LOGGER перед чтением истории: второй TCP запрещён")
    }

    private fun broadcasts(lp:LinkProperties?):List<String> =
        lp?.linkAddresses?.mapNotNull { addr ->
            val ipv4=addr.address as? Inet4Address ?: return@mapNotNull null
            val prefix=addr.prefixLength
            if(prefix !in 1..32)return@mapNotNull null
            var ip=0L
            for(byte in ipv4.address)ip=(ip shl 8) or (byte.toInt() and 255).toLong()
            val mask=(0xFFFFFFFFL shl (32-prefix)) and 0xFFFFFFFFL
            val broadcast=(ip and mask) or (mask.inv() and 0xFFFFFFFFL)
            (listOf(24,16,8,0).map{(broadcast shr it) and 255}).joinToString(".")
        }?.distinct() ?: emptyList()

    private fun discover(network:Network,link:LinkProperties?):String? {
        val addresses=(broadcasts(link)+listOf("169.254.255.255","255.255.255.255")).distinct()
        val socket=DatagramSocket(null)
        return try {
            network.bindSocket(socket)
            socket.broadcast=true
            socket.soTimeout=1400
            socket.bind(InetSocketAddress(0))
            val request=byteArrayOf(0,0,0,0,0,0x11)
            for(ip in addresses)try {
                socket.send(DatagramPacket(request,request.size,InetAddress.getByName(ip),6811))
            }catch(_:Exception){}
            val packet=DatagramPacket(ByteArray(512),512)
            socket.receive(packet)
            packet.address.hostAddress
        }catch(_:java.net.SocketTimeoutException){null}
        finally { socket.close() }
    }

    private fun validPrivateIp(ip:String?):String? {
        if(ip==null || !ip.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}")))return null
        val numbers=ip.split(".").map { it.toIntOrNull() ?: return null }
        if(numbers.any { it !in 0..255 })return null
        val privateRange=numbers[0]==10 ||
            (numbers[0]==172 && numbers[1] in 16..31) ||
            (numbers[0]==192 && numbers[1]==168) ||
            (numbers[0]==169 && numbers[1]==254)
        return if(privateRange)ip else null
    }

    private fun connect(cancel:()->Boolean):Pair<Target,Socket> {
        verifyAllowed(cancel)
        val manager=context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val prefs=context.getSharedPreferences("bmw_native",Context.MODE_PRIVATE)
        val remembered=validPrivateIp(prefs.getString("last_gateway_ip",null))
        val rememberedTransport=prefs.getString("last_gateway_transport",null)
        val networks=manager.allNetworks.mapNotNull { network ->
            val caps=manager.getNetworkCapabilities(network) ?: return@mapNotNull null
            if(caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))return@mapNotNull null
            val kind=when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                else -> return@mapNotNull null
            }
            val link=manager.getLinkProperties(network) ?: return@mapNotNull null
            if(link.linkAddresses.none { it.address is Inet4Address })
                return@mapNotNull null
            Triple(network,kind,link)
        }.sortedWith(compareBy<Triple<Network,String,LinkProperties>> {
            if(it.second==rememberedTransport)0 else 1
        }.thenBy { if(it.second=="ETHERNET")0 else 1 })
        if(networks.isEmpty())throw IOException("Нет физической Wi-Fi/Ethernet-сети с IPv4")
        var last:Exception?=null
        for((network,kind,link) in networks) {
            verifyAllowed(cancel)
            // Use the same last successful gateway as LOGGER before UDP discovery.
            val candidates=ArrayList<String>()
            if(remembered!=null && kind==rememberedTransport)candidates.add(remembered)
            try { discover(network,link)?.let { validPrivateIp(it)?.let(candidates::add) } }
            catch(e:Exception){last=e}
            for(host in candidates.distinct()) {
                verifyAllowed(cancel)
                val socket=network.socketFactory.createSocket()
                try {
                    socket.soTimeout=1800
                    socket.tcpNoDelay=true
                    socket.connect(InetSocketAddress(host,6801),1800)
                    verifyAllowed(cancel)
                    return Target(network,kind,host) to socket
                }catch(e:Exception) {
                    last=e
                    try{socket.close()}catch(_:Exception){}
                }
            }
        }
        throw IOException("DME недоступен по ENET/VXSCAN: ${last?.message ?: "нет ответа UDP 6811/TCP 6801"}")
    }

    private fun transact(socket:Socket,request:ByteArray,
                         expected:(ByteArray)->Boolean,cancel:()->Boolean):Reply {
        verifyAllowed(cancel)
        val service=request[0].toInt() and 255
        socket.getOutputStream().write(HsfzCodec.encode(request))
        socket.getOutputStream().flush()
        val deadline=SystemClock.elapsedRealtime()+5500L
        repeat(4) {
            verifyAllowed(cancel)
            if(SystemClock.elapsedRealtime()>deadline)
                throw IOException("UDS deadline exceeded (service %02X)".format(service))
            val frames=HsfzCodec.readFrames(socket.getInputStream(),maxFrames=3)
            val p=frames.mapNotNull(HsfzCodec::payload).firstOrNull()
                ?: throw IOException("UDS timeout / no reply (service %02X)".format(service))
            if(p.size>DmeHistoryProtocol.MAX_RESPONSE_BYTES)
                throw IOException("UDS response exceeds bounded history limit")
            val neg=DmeHistoryProtocol.classifyNrc(service,p)
            if(neg=="RESPONSE_PENDING")return@repeat
            if(neg!=null)return Reply(neg,p)
            if(!expected(p))
                throw IOException("Unmatched UDS response: ${DmeHistoryProtocol.hex(p.take(12).toByteArray())}; close socket")
            return Reply("OK",p)
        }
        throw IOException("UDS response pending without final reply")
    }


    /**
     * Parked-only RAM readiness survey. Queries ONLY fixed, read-only UDS 0x22
     * identifiers; it cannot detect arbitrary RAM access or configure a DDDI.
     * A partial CSV is preserved if transport fails or the user cancels.
     */

    /**
     * RAM LAB 4: parked-only SINGLE live memory read at immutable RPM address
     * tied to screenshot-confirmed SWFL/A2L. Caller must independently confirm
     * the ECU's software identity; F18x UDS identification was rejected.
     * On this socket: OBD speed, OBD RPM, one UDS 0x23, OBD RPM comparison.
     * Never sends session changes, security access or dynamic DID commands.
     */
    fun collectRamSingleRpm(cancel:()->Boolean,progress:(String)->Unit):Outcome {
        verifyAllowed(cancel)
        val dir=context.getExternalFilesDir(null) ?: context.filesDir
        val file=File(dir,"bmw_ram_live_v4_${System.currentTimeMillis()}.csv")
        var successful=false
        var problem:String?=null
        var acceptedRpm:Int?=null
        var firstRpm:Double?=null
        var lastRpm:Double?=null
        var host=""
        var transport=""
        file.bufferedWriter().use { writer ->
            writer.appendLine("wall_time_ms,stage,status,elapsed_ms,request_hex,response_hex,decoded,transport,peer,profile")
            fun record(stage:String,status:String,elapsed:Long,request:ByteArray,response:ByteArray,
                       decoded:Any="") {
                writer.appendLine(DmeHistoryProtocol.csv(
                    System.currentTimeMillis(),stage,status,elapsed,
                    DmeHistoryProtocol.hex(request),DmeHistoryProtocol.hex(response),
                    decoded,transport,host,
                    RamMemoryProtocol.APPROVED_A2L+"/"+RamMemoryProtocol.APPROVED_SWFL))
                writer.flush()
            }
            try {
                val ramRequest=RamMemoryProtocol.approvedVehicleRequest(
                    RamMemoryProtocol.APPROVED_A2L,RamMemoryProtocol.RPM_SIGNAL)
                    ?: throw IOException("No approved exact DME RAM location")
                if(!ramRequest.contentEquals(
                        byteArrayOf(0x23,0x14,0x60,0x00,0x3B,0x64,0x02)))
                    throw IOException("RAM LAB4 fixed request invariant violated")
                progress("RAM LAB 4: подключение к DME, только на стоянке…")
                val (target,socket)=connect(cancel)
                host=target.host
                transport=target.transport
                socket.use { conn ->
                    fun readObd(id:Int,stage:String):ByteArray {
                        verifyAllowed(cancel)
                        val request=byteArrayOf(0x01,id.toByte())
                        val start=SystemClock.elapsedRealtime()
                        val reply=transact(conn,request,
                            { DiagnosticResponse.obd(it,id)!=null },cancel)
                        val elapsed=SystemClock.elapsedRealtime()-start
                        val values=if(reply.status=="OK")
                            DiagnosticResponse.obd(reply.payload,id) else null
                        record(stage,reply.status,elapsed,request,reply.payload,
                            values?.let(DmeHistoryProtocol::hex) ?: "")
                        if(values==null)throw IOException(
                            "OBD PID %02X unavailable: %s".format(id,reply.status))
                        return values
                    }
                    progress("RAM LAB 4: подтверждение нулевой скорости…")
                    val speed=readObd(0x0D,"SPEED_BEFORE")
                    if(!RamMemoryProtocol.vehicleStationary(speed))
                        throw IOException("RAM read refused: vehicle speed is not zero")
                    progress("RAM LAB 4: эталон RPM по OBD…")
                    val rpmBytes=readObd(0x0C,"OBD_RPM_BEFORE")
                    if(rpmBytes.size!=2)throw IOException("Invalid OBD RPM size")
                    firstRpm=(((rpmBytes[0].toInt() and 255) shl 8) or
                        (rpmBytes[1].toInt() and 255))/4.0
                    if(!RamMemoryProtocol.parkedIdleRpm(firstRpm!!))
                        throw IOException("RAM test requires steady parked idle (450–1800 RPM)")
                    verifyAllowed(cancel)
                    progress("RAM LAB 4: единственное чтение RAM Nkw, 2 байта…")
                    val start=SystemClock.elapsedRealtime()
                    val reply=transact(conn,ramRequest,
                        { RamMemoryProtocol.decodePositive(it,2)!=null },cancel)
                    val elapsed=SystemClock.elapsedRealtime()-start
                    val bytes=if(reply.status=="OK")
                        RamMemoryProtocol.decodePositive(reply.payload,2) else null
                    acceptedRpm=bytes?.let(RamMemoryProtocol::decodeNkwRpm)
                    record("RAM_NKW",reply.status,elapsed,ramRequest,reply.payload,
                        acceptedRpm ?: "NOT_VALIDATED")
                    if(reply.status!="OK")
                        throw IOException("RAM 0x23 refused by ECU: ${reply.status}; no retry")
                    if(acceptedRpm==null)
                        throw IOException("RAM Nkw positive response but decoded RPM is invalid")
                    progress("RAM LAB 4: сопоставление с обычным OBD RPM…")
                    val afterBytes=readObd(0x0C,"OBD_RPM_AFTER")
                    if(afterBytes.size!=2)throw IOException("Invalid comparison RPM size")
                    lastRpm=(((afterBytes[0].toInt() and 255) shl 8) or
                        (afterBytes[1].toInt() and 255))/4.0
                    successful=RamMemoryProtocol.matchesObdRpm(
                        firstRpm!!,lastRpm!!,acceptedRpm!!) 
                    record("CROSS_CHECK",if(successful)"MATCH" else "MISMATCH",
                        0L,byteArrayOf(),byteArrayOf(),
                        "obd_before=${firstRpm} ram=${acceptedRpm} obd_after=${lastRpm}")
                }
            }catch(e:Exception) {
                problem="${e.javaClass.simpleName}: ${e.message ?: "unknown"}"
                writer.appendLine(DmeHistoryProtocol.csv(
                    System.currentTimeMillis(),"STOP","ERROR","", "","",
                    problem ?: "unknown",transport,host,
                    RamMemoryProtocol.APPROVED_A2L+"/"+RamMemoryProtocol.APPROVED_SWFL))
                writer.flush()
            }
        }
        val summary=when {
            successful -> "RAM LAB 4: единичный RAM 0x23 подтвердил Nkw=${acceptedRpm} RPM; "+
                "OBD до/после=${firstRpm}/${lastRpm}. Только чтение, 0x2C не проверен."
            problem!=null -> "RAM LAB 4 остановлен: ${problem}. "+
                "Повтор RAM-запроса не выполнялся; смотри CSV."
            else -> "RAM LAB 4: ответ получен, но RPM не совпал с OBD. "+
                "Nkw=${acceptedRpm}; OBD=${firstRpm}/${lastRpm}. Результат НЕ подтверждён."
        }
        progress(summary)
        return Outcome(file,summary,successful)
    }


    /**
     * RAM LAB 6: bounded on-car performance comparison of the single RPM
     * location already validated in RAM LAB 4 and normal OBD RPM.
     * ONLY 0x23 and OBD 0x01, with one owned socket, strict parsing,
     * 150 ms minimum pause between successive RPM reads in each block.
     */
    fun collectRamDirectBenchmark(cancel:()->Boolean,progress:(String)->Unit):Outcome {
        verifyAllowed(cancel)
        val dir=context.getExternalFilesDir(null) ?: context.filesDir
        val file=File(dir,"bmw_ram_direct_v6_${System.currentTimeMillis()}.csv")
        val ramTimes=ArrayList<Long>()
        val obdTimes=ArrayList<Long>()
        val ramRates=ArrayList<Double>()
        val obdRates=ArrayList<Double>()
        var problem:String?=null
        var completed=false
        var referenceRpm:Double?=null
        var finalRpm:Double?=null
        var transport=""
        var peer=""
        var activeStage="CONNECT"
        var activeRequest=byteArrayOf()
        file.bufferedWriter().use { writer ->
            writer.appendLine(
                "wall_time_ms,stage,round,block,sample,status,elapsed_ms,rpm,"+
                "request_hex,response_hex,transport,peer,profile,detail")
            fun row(stage:String,round:Int,block:Int,sample:Int,status:String,
                    elapsed:Long?,rpm:Double?,request:ByteArray,response:ByteArray,
                    detail:String="") {
                writer.appendLine(DmeHistoryProtocol.csv(
                    System.currentTimeMillis(),stage,round,block,sample,status,
                    elapsed ?: "",rpm ?: "",DmeHistoryProtocol.hex(request),
                    DmeHistoryProtocol.hex(response),transport,peer,
                    RamMemoryProtocol.APPROVED_A2L+"/"+RamMemoryProtocol.APPROVED_SWFL,
                    detail))
                writer.flush()
            }
            try {
                val ramReq=RamDirectBenchmarkProtocol.rpmRequest(
                    RamDirectBenchmarkProtocol.Source.DIRECT_RAM)
                val obdReq=RamDirectBenchmarkProtocol.rpmRequest(
                    RamDirectBenchmarkProtocol.Source.OBD)
                val speedReq=RamDirectBenchmarkProtocol.speedRequest()
                progress("RAM LAB 6: поиск VXSCAN / ENET…")
                val (target,socket)=connect(cancel)
                transport=target.transport
                peer=target.host
                socket.use { conn ->
                    val deadline=SystemClock.elapsedRealtime()+
                        RamDirectBenchmarkProtocol.MAX_RUN_MS

                    fun transfer(request:ByteArray,stage:String,round:Int,
                                 block:Int,sample:Int,expected:(ByteArray)->Boolean):Pair<Reply,Long> {
                        verifyAllowed(cancel)
                        if(SystemClock.elapsedRealtime()>=deadline)
                            throw IOException("RAM LAB 6 overall time limit reached")
                        activeStage=stage
                        activeRequest=request
                        val started=SystemClock.elapsedRealtime()
                        val response=transact(conn,request,expected,cancel)
                        val elapsed=SystemClock.elapsedRealtime()-started
                        val rpm=if(stage=="RAM_SAMPLE")
                            RamDirectBenchmarkProtocol.decodeRpm(
                                RamDirectBenchmarkProtocol.Source.DIRECT_RAM,response.payload)
                            else if(stage=="OBD_SAMPLE"||stage=="OBD_BASELINE"||
                                stage=="OBD_FINAL")
                                RamDirectBenchmarkProtocol.decodeRpm(
                                    RamDirectBenchmarkProtocol.Source.OBD,response.payload)
                            else null
                        row(stage,round,block,sample,response.status,elapsed,
                            rpm,request,response.payload)
                        activeStage=""
                        activeRequest=byteArrayOf()
                        if(response.status!="OK")
                            throw IOException("$stage: ECU refused request: ${response.status}")
                        return response to elapsed
                    }

                    fun stationaryGate(round:Int) {
                        val r=transfer(speedReq,"SPEED_GATE",round,0,0,
                            { DiagnosticResponse.obd(it,0x0D)!=null }).first
                        val speed=DiagnosticResponse.obd(r.payload,0x0D)
                        if(speed==null || !RamMemoryProtocol.vehicleStationary(speed))
                            throw IOException("Vehicle not confirmed stationary")
                    }

                    stationaryGate(0)
                    progress("RAM LAB 6: проверка стабильных оборотов холостого хода…")
                    val initial=transfer(obdReq,"OBD_BASELINE",0,0,0,
                        { DiagnosticResponse.obd(it,0x0C)!=null }).first
                    referenceRpm=RamDirectBenchmarkProtocol.decodeRpm(
                        RamDirectBenchmarkProtocol.Source.OBD,initial.payload)
                    if(referenceRpm==null ||
                        !RamMemoryProtocol.parkedIdleRpm(referenceRpm!!))
                        throw IOException("Stable engine idle (450–1800 RPM) required")

                    for(round in 0 until RamDirectBenchmarkProtocol.ROUNDS) {
                        stationaryGate(round+1)
                        for((blockNo,source) in RamDirectBenchmarkProtocol.order(round)
                            .withIndex()) {
                            progress("RAM LAB 6: серия ${round+1}/"+
                                "${RamDirectBenchmarkProtocol.ROUNDS}, "+
                                if(source==RamDirectBenchmarkProtocol.Source.DIRECT_RAM)
                                    "прямой RAM 0x23…" else "OBD RPM…")
                            val request=if(source==RamDirectBenchmarkProtocol.Source.DIRECT_RAM)
                                ramReq else obdReq
                            val blockStart=SystemClock.elapsedRealtime()
                            for(index in 0 until RamDirectBenchmarkProtocol.SAMPLES_PER_BLOCK) {
                                verifyAllowed(cancel)
                                val stage=if(source==RamDirectBenchmarkProtocol.Source.DIRECT_RAM)
                                    "RAM_SAMPLE" else "OBD_SAMPLE"
                                val (r,duration)=transfer(request,stage,round+1,blockNo+1,index+1,
                                    { bytes ->
                                        RamDirectBenchmarkProtocol.decodeRpm(source,bytes)!=null
                                    })
                                val rpm=RamDirectBenchmarkProtocol.decodeRpm(source,r.payload)
                                if(!RamDirectBenchmarkProtocol.isValidIdleSample(
                                        rpm,referenceRpm!!))
                                    throw IOException(
                                        "RPM sample not plausible at idle: $rpm")
                                if(source==RamDirectBenchmarkProtocol.Source.DIRECT_RAM)
                                    ramTimes.add(duration)
                                else obdTimes.add(duration)
                                if(index<RamDirectBenchmarkProtocol.SAMPLES_PER_BLOCK-1) {
                                    // Deliberate cap on ECU polling pressure. This
                                    // pause is INCLUDED in observed block rate.
                                    Thread.sleep(RamDirectBenchmarkProtocol.PACE_MS)
                                }
                            }
                            val blockElapsed=SystemClock.elapsedRealtime()-blockStart
                            val hz=RamDirectBenchmarkProtocol.observedRateHz(
                                RamDirectBenchmarkProtocol.SAMPLES_PER_BLOCK,blockElapsed)
                                ?: throw IOException("Invalid block duration")
                            if(source==RamDirectBenchmarkProtocol.Source.DIRECT_RAM)
                                ramRates.add(hz) else obdRates.add(hz)
                            row("BLOCK_RATE",round+1,blockNo+1,0,"OK",blockElapsed,
                                null,byteArrayOf(),byteArrayOf(),
                                "source=${source.name};samples="+
                                "${RamDirectBenchmarkProtocol.SAMPLES_PER_BLOCK};"+
                                "observed_hz=${"%.3f".format(java.util.Locale.US,hz)};"+
                                "pace_ms=${RamDirectBenchmarkProtocol.PACE_MS}")
                        }
                    }
                    stationaryGate(RamDirectBenchmarkProtocol.ROUNDS+1)
                    val final=transfer(obdReq,"OBD_FINAL",
                        RamDirectBenchmarkProtocol.ROUNDS+1,0,0,
                        { DiagnosticResponse.obd(it,0x0C)!=null }).first
                    finalRpm=RamDirectBenchmarkProtocol.decodeRpm(
                        RamDirectBenchmarkProtocol.Source.OBD,final.payload)
                    if(!RamDirectBenchmarkProtocol.isValidIdleSample(
                            finalRpm,referenceRpm!!))
                        throw IOException("Engine RPM drifted outside idle window")
                    completed=true
                }
            }catch(e:Exception) {
                problem="${e.javaClass.simpleName}: ${e.message ?: "unknown"}"
                row("ERROR",0,0,0,"STOP",null,null,
                    activeRequest,byteArrayOf(),"$activeStage: $problem")
            }
            fun report(source:String,durations:List<Long>,rates:List<Double>):String {
                val stats=RamBatchBenchmarkProtocol.stats(durations)
                return if(stats==null)"$source: no valid samples" else
                    "$source: n=${stats.count}, med=${stats.medianMs}ms, "+
                    "p95=${stats.p95Ms}ms, paced_block_hz="+
                    if(rates.isEmpty())"n/a" else
                        "%.2f".format(java.util.Locale.US,rates.average())
            }
            row("SUMMARY",0,0,0,if(completed)"COMPLETE" else "PARTIAL",
                null,null,byteArrayOf(),byteArrayOf(),
                report("RAM",ramTimes,ramRates)+" | "+
                report("OBD",obdTimes,obdRates)+"; "+
                "rpm_baseline=$referenceRpm; rpm_final=$finalRpm; "+
                "error=${problem ?: ""}")
        }
        val ram=RamBatchBenchmarkProtocol.stats(ramTimes)
        val obd=RamBatchBenchmarkProtocol.stats(obdTimes)
        val summary="RAM LAB 6: RAM ${ram?.count ?: 0}/"+
            "${RamDirectBenchmarkProtocol.TOTAL_PER_SOURCE}, OBD "+
            "${obd?.count ?: 0}/${RamDirectBenchmarkProtocol.TOTAL_PER_SOURCE}. "+
            "RAM медиана/p95 ${ram?.medianMs ?: "—"}/"+
            "${ram?.p95Ms ?: "—"} мс; "+
            "OBD ${obd?.medianMs ?: "—"}/${obd?.p95Ms ?: "—"} мс. "+
            if(completed)"Четыре коротких блока каждого типа завершены; "+
                "оценка устойчивой частоты длинного LOGGER не проводилась."
            else "Частичный результат: ${problem ?: "unknown"}. "+
                "Чтение остановлено; CSV сохранён."
        progress(summary)
        return Outcome(file,summary,completed)
    }


    /**
     * RAM LAB Suite 1.0: six independent on-car tests with locked progression.
     * Read-only standard OBD, known 4A49 DID, verified Nkw RAM and ONE
     * software-exact candidate Dzw_tot_kr. No UDS 0x2C, session/security,
     * flashing, arbitrary addresses or parallel sockets.
     */
    fun collectRamSuiteStage(stage:RamSuiteProtocol.Stage,
                             cancel:()->Boolean,progress:(String)->Unit):Outcome {
        verifyAllowed(cancel)
        val prefs=context.getSharedPreferences("bmw_ram_suite",Context.MODE_PRIVATE)
        fun key(number:Int)=RamSuiteProtocol.VERSION+"_"+number
        val prior=RamSuiteProtocol.Stage.entries
            .filter { prefs.getBoolean(key(it.number),false) }
            .map { it.number }.toSet()
        if(!RamSuiteProtocol.allowed(stage,prior))
            throw IOException("Этап ${stage.number} заблокирован до успешного предыдущего теста")
        val dir=context.getExternalFilesDir(null) ?: context.filesDir
        val file=File(dir,"bmw_ram_suite_${stage.number}_${System.currentTimeMillis()}.csv")
        val times=ArrayList<Long>()
        val rpmValues=ArrayList<Double>()
        val retardValues=ArrayList<Double>()
        var completed=false
        var failure:String?=null
        var peer=""
        var transport=""
        var activeStage="CONNECT"
        var activeRequest=byteArrayOf()
        var initialRpm=0.0
        var lastRpm=0.0
        var samples=0
        val started=SystemClock.elapsedRealtime()
        file.bufferedWriter().use { writer ->
            writer.appendLine("wall_time_ms,elapsed_ms,test,stage,index,status,"+
                "latency_ms,rpm,retard_deg,request_hex,response_hex,transport,"+
                "peer,a2l,swfl,detail")
            fun row(event:String,index:Int,status:String,latency:Long?,
                    rpm:Double?,retard:Double?,req:ByteArray,res:ByteArray,
                    detail:String="") {
                writer.appendLine(DmeHistoryProtocol.csv(
                    System.currentTimeMillis(),SystemClock.elapsedRealtime()-started,
                    stage.number,event,index,status,latency ?: "",rpm ?: "",
                    retard ?: "",DmeHistoryProtocol.hex(req),
                    DmeHistoryProtocol.hex(res),transport,peer,
                    RamMemoryProtocol.APPROVED_A2L,RamMemoryProtocol.APPROVED_SWFL,
                    detail))
                writer.flush()
            }
            fun limit() {
                verifyAllowed(cancel)
                if(SystemClock.elapsedRealtime()-started >
                    stage.seconds*1000L) throw IOException("Stage time limit exceeded")
            }
            fun read(conn:Socket,event:String,idx:Int,req:ByteArray,
                     accept:(ByteArray)->Boolean,
                     decodeRpm:(ByteArray)->Double?={ null },
                     decodeRetard:(ByteArray)->Double?={ null }):ByteArray {
                limit()
                activeStage=event
                activeRequest=req
                val t0=SystemClock.elapsedRealtime()
                val response=try {
                    transact(conn,req,accept,cancel)
                }catch(e:Exception) {
                    row(event,idx,"TRANSPORT_ERROR",
                        SystemClock.elapsedRealtime()-t0,null,null,req,byteArrayOf(),
                        "${e.javaClass.simpleName}: ${e.message}")
                    throw e
                }
                val elapsed=SystemClock.elapsedRealtime()-t0
                val rpm=if(response.status=="OK")decodeRpm(response.payload) else null
                val retard=if(response.status=="OK")decodeRetard(response.payload) else null
                val nrc=if(response.payload.size==3 &&
                    response.payload[0]==0x7F.toByte()) {
                    "%02X".format(java.util.Locale.US,response.payload[2].toInt() and 255)
                }else ""
                row(event,idx,response.status,elapsed,rpm,retard,req,
                    response.payload,if(nrc.isNotBlank())"NRC_0x$nrc" else "")
                if(response.status!="OK")
                    throw IOException("$event refused ${response.status} NRC=0x$nrc")
                times.add(elapsed)
                if(rpm!=null)rpmValues.add(rpm)
                if(retard!=null)retardValues.add(retard)
                activeStage=""
                activeRequest=byteArrayOf()
                return response.payload
            }

            val speedReq=byteArrayOf(0x01,0x0D)
            val obdReq=byteArrayOf(0x01,0x0C)
            val rpmReq=RamSuiteProtocol.memoryRequest("Nkw")
            val retardReq=RamSuiteProtocol.memoryRequest("Dzw_tot_kr")
            val ignitionReq=byteArrayOf(0x22,0x4A,0x49)

            fun gate(conn:Socket,index:Int) {
                val result=read(conn,"SPEED_GATE",index,speedReq,
                    { DiagnosticResponse.obd(it,0x0D)!=null })
                if(!RamSuiteProtocol.speedStationary(result))
                    throw IOException("Speed not confirmed 0 km/h; STOP")
            }
            fun reference(conn:Socket,index:Int):Double {
                val payload=read(conn,"OBD_REFERENCE",index,obdReq,
                    { DiagnosticResponse.obd(it,0x0C)!=null },
                    { RamSuiteProtocol.obdRpm(it) })
                val rpm=RamSuiteProtocol.obdRpm(payload)
                if(rpm==null || !RamMemoryProtocol.parkedIdleRpm(rpm))
                    throw IOException("Engine not at stable idle")
                if(initialRpm==0.0)initialRpm=rpm
                if(!RamSuiteProtocol.plausible(rpm,initialRpm))
                    throw IOException("Reference idle changed by >150 RPM")
                lastRpm=rpm
                return rpm
            }
            fun measureRam(conn:Socket,index:Int,signal:String):Double {
                val req=if(signal=="Nkw")rpmReq else retardReq
                val payload=read(conn,"RAM_$signal",index,req,
                    { RamMemoryProtocol.decodePositive(it,2)!=null },
                    if(signal=="Nkw")({ RamSuiteProtocol.rpm(it) }) else ({ null }),
                    if(signal=="Dzw_tot_kr")({ RamSuiteProtocol.retardDegrees(it) })
                    else ({ null }))
                val data=if(signal=="Nkw")RamSuiteProtocol.rpm(payload)
                    else RamSuiteProtocol.retardDegrees(payload)
                if(data==null)throw IOException("$signal invalid decoded value")
                if(signal=="Nkw" && !RamSuiteProtocol.plausible(data,initialRpm))
                    throw IOException("Unexpected RPM from RAM ($data)")
                return data
            }
            fun paced(ms:Long=RamSuiteProtocol.PACE_MS) {
                limit()
                Thread.sleep(ms)
            }
            try {
                progress("RAM LAB Suite: этап ${stage.number}, подключение к DME…")
                when(stage) {
                    RamSuiteProtocol.Stage.RECONNECT -> {
                        // Intentional graceful close/reopen only AFTER completed
                        // transfers. Never corrupt an in-flight HSFZ response.
                        for(attempt in 1..RamSuiteProtocol.count(stage)) {
                            limit()
                            val (target,conn)=connect(cancel)
                            peer=target.host
                            transport=target.transport
                            conn.use { socket ->
                                gate(socket,attempt)
                                reference(socket,attempt)
                                measureRam(socket,attempt,"Nkw")
                                samples++
                                row("DISCONNECT",attempt,"GRACEFUL",null,
                                    null,null,byteArrayOf(),byteArrayOf(),
                                    "Socket closed after complete response")
                            }
                            if(attempt<2)paced(800)
                        }
                    }
                    else -> {
                        val (target,conn)=connect(cancel)
                        peer=target.host
                        transport=target.transport
                        conn.use { socket ->
                            gate(socket,0)
                            reference(socket,0)
                            when(stage) {
                                RamSuiteProtocol.Stage.SIGNAL -> {
                                    // Candidate decoded via A2L, but not yet
                                    // established as cylinder knock correction.
                                    repeat(RamSuiteProtocol.count(stage)) { i ->
                                        measureRam(socket,i+1,"Dzw_tot_kr")
                                        read(socket,"IGNITION_DID_4A49",i+1,ignitionReq,
                                            { RamSuiteProtocol.packedIgnitionReply(it) })
                                        samples++
                                        if(i+1<RamSuiteProtocol.count(stage))paced()
                                    }
                                }
                                RamSuiteProtocol.Stage.RPM_DYNAMICS -> {
                                    // Parked idle fluctuation test, never ask
                                    // driver to rev or test while driving.
                                    repeat(RamSuiteProtocol.count(stage)) { i ->
                                        val first=if(i%2==0)"Nkw" else "OBD"
                                        val a=if(first=="Nkw")
                                            measureRam(socket,i+1,"Nkw")
                                        else reference(socket,i+1)
                                        paced()
                                        val b=if(first=="Nkw")
                                            reference(socket,i+1)
                                        else measureRam(socket,i+1,"Nkw")
                                        val delta=kotlin.math.abs(a-b)
                                        row("PAIR_DELTA",i+1,"OK",null,
                                            delta,null,byteArrayOf(),byteArrayOf(),
                                            "absolute_rpm_difference")
                                        if(delta>100.0)
                                            throw IOException("RAM/OBD disagreement $delta rpm")
                                        samples++
                                        if(i+1<RamSuiteProtocol.count(stage))paced()
                                    }
                                }
                                RamSuiteProtocol.Stage.SEQUENTIAL -> {
                                    repeat(RamSuiteProtocol.count(stage)) { i ->
                                        measureRam(socket,i+1,"Nkw")
                                        measureRam(socket,i+1,"Dzw_tot_kr")
                                        samples++
                                        if(i+1<RamSuiteProtocol.count(stage))paced()
                                    }
                                }
                                RamSuiteProtocol.Stage.REPEATABILITY -> {
                                    repeat(2) { pass ->
                                        gate(socket,pass+1)
                                        reference(socket,pass+1)
                                        repeat(6) { i ->
                                            val index=pass*6+i+1
                                            measureRam(socket,index,"Nkw")
                                            measureRam(socket,index,"Dzw_tot_kr")
                                            samples++
                                            if(i!=5)paced()
                                        }
                                        if(pass==0)paced(1800)
                                    }
                                }
                                RamSuiteProtocol.Stage.SOAK -> {
                                    repeat(RamSuiteProtocol.count(stage)) { i ->
                                        val cycleStart=SystemClock.elapsedRealtime()
                                        if(i%10==0) {
                                            gate(socket,i+1)
                                            reference(socket,i+1)
                                        }
                                        measureRam(socket,i+1,"Nkw")
                                        measureRam(socket,i+1,"Dzw_tot_kr")
                                        samples++
                                        if((i+1)%10==0 || i==0)
                                            progress("RAM LAB Suite • 12: ${i+1}/"+
                                                "${RamSuiteProtocol.count(stage)} образцов")
                                        val elapsed=SystemClock.elapsedRealtime()-cycleStart
                                        if(i+1<RamSuiteProtocol.count(stage))
                                            paced((1000L-elapsed).coerceAtLeast(0L))
                                    }
                                }
                                RamSuiteProtocol.Stage.RECONNECT -> error("handled above")
                            }
                            gate(socket,99)
                            reference(socket,99)
                        }
                    }
                }
                completed=samples==RamSuiteProtocol.count(stage)
            }catch(e:Exception) {
                failure="${e.javaClass.simpleName}: ${e.message ?: "unknown"}"
                row("STOP",samples,"ERROR",null,null,null,activeRequest,
                    byteArrayOf(),"at $activeStage: $failure")
            }
            val stat=RamBatchBenchmarkProtocol.stats(times)
            row("SUMMARY",samples,if(completed)"TECHNICAL_PASS" else "PARTIAL",
                null,null,null,byteArrayOf(),byteArrayOf(),
                "count=$samples;roundtrips=${stat?.count ?: 0};"+
                "median_ms=${stat?.medianMs ?: "n/a"};p95_ms=${stat?.p95Ms ?: "n/a"};"+
                "ram_rpm_n=${rpmValues.size};candidate_retard_n=${retardValues.size};"+
                "obd_start=$initialRpm;obd_last=$lastRpm;"+
                "error=${failure ?: "none"};"+
                "signal_semantics_NOT_validated")
        }
        if(completed)prefs.edit().putBoolean(key(stage.number),true).apply()
        val summary=if(completed)
            "RAM LAB Suite ${stage.number}: технический этап выполнен, "+
                "$samples/${RamSuiteProtocol.count(stage)} циклов. "+
                "Это НЕ подтверждает физический смысл нового RAM-показателя "+
                "и не доказывает качество топлива."
        else "RAM LAB Suite ${stage.number}: остановлено на $samples циклах. "+
            "${failure ?: "unknown"}; CSV сохранён. Перед повтором изучи отказ."
        progress(summary)
        return Outcome(file,summary,completed)
    }

    fun collectRamReadiness(cancel:()->Boolean,progress:(String)->Unit):Outcome {
        verifyAllowed(cancel)
        val dir=context.getExternalFilesDir(null) ?: context.filesDir
        val output=File(dir,"bmw_ram_readiness_v1_${System.currentTimeMillis()}.csv")
        var ok=0
        var rejected=0
        var completed=false
        var problem:String?=null
        output.bufferedWriter().use { writer ->
            writer.appendLine("wall_time_ms,did,description,elapsed_ms,status,request_hex,response_hex,transport,peer")
            try {
                progress("RAM LAB: поиск сети ENET/VXSCAN…")
                val (target,connected)=connect(cancel)
                connected.use { conn ->
                    for((index,probe) in RamReadinessProtocol.probes.withIndex()) {
                        verifyAllowed(cancel)
                        progress("RAM LAB: UDS 0x22 ${index+1}/${RamReadinessProtocol.probes.size}…")
                        val request=RamReadinessProtocol.request(probe.did)
                        val start=SystemClock.elapsedRealtime()
                        val reply=transact(conn,request,
                            { RamReadinessProtocol.positiveMatches(probe.did,it) },cancel)
                        val elapsed=SystemClock.elapsedRealtime()-start
                        if(reply.status=="OK")ok++ else rejected++
                        writer.appendLine(DmeHistoryProtocol.csv(
                            System.currentTimeMillis(),
                            "%04X".format(java.util.Locale.US,probe.did),
                            probe.title,elapsed,reply.status,DmeHistoryProtocol.hex(request),
                            DmeHistoryProtocol.hex(reply.payload),target.transport,target.host))
                        writer.flush()
                    }
                }
                completed=true
            }catch(e:Exception) {
                problem="${e.javaClass.simpleName}: ${e.message ?: "unknown"}"
                writer.appendLine(DmeHistoryProtocol.csv(System.currentTimeMillis(),
                    "","","","ERROR","","", "", problem))
                writer.flush()
            }
        }
        val summary="RAM LAB (только UDS 0x22): ответов OK $ok, отказов $rejected. "+
            if(completed) "Опрос завершён. RAM/0x2C НЕ проверялись."
            else "Частичный журнал: ${problem ?: "unknown"}. RAM/0x2C НЕ проверялись."
        progress(summary)
        return Outcome(output,summary,completed && ok>0)
    }


    /**
     * RAM LAB 2: bounded, parked-only comparison of existing DME DIDs.
     * Single requests and a combined 0x22 request share ONE TCP connection.
     * Any rejected or mismatched batch ends the experiment immediately.
     */
    fun collectRamBatchBenchmark(cancel:()->Boolean,progress:(String)->Unit):Outcome {
        verifyAllowed(cancel)
        val dir=context.getExternalFilesDir(null) ?: context.filesDir
        val output=File(dir,"bmw_ram_batch_v2_${System.currentTimeMillis()}.csv")
        val singlePairs=ArrayList<Long>(RamBatchBenchmarkProtocol.ITERATIONS)
        val batches=ArrayList<Long>(RamBatchBenchmarkProtocol.ITERATIONS)
        var completed=false
        var error:String?=null
        var pendingIteration=0
        var pendingKind:String?=null
        var pendingRequest:ByteArray?=null
        var pendingStart=0L
        var usedTransport=""
        var usedPeer=""
        output.bufferedWriter().use { writer ->
            writer.appendLine("wall_time_ms,iteration,sequence,kind,dids,elapsed_ms,status,request_hex,response_hex,transport,peer")
            try {
                progress("RAM LAB 2: поиск сети ENET/VXSCAN…")
                val (target,connected)=connect(cancel)
                usedTransport=target.transport
                usedPeer=target.host
                connected.use { conn ->
                    val deadline=SystemClock.elapsedRealtime()+RamBatchBenchmarkProtocol.MAX_RUN_MS
                    for(index in 0 until RamBatchBenchmarkProtocol.ITERATIONS) {
                        verifyAllowed(cancel)
                        if(SystemClock.elapsedRealtime()>=deadline)
                            throw IOException("Общий лимит RAM LAB 2: 30 секунд")
                        progress("RAM LAB 2: серия ${index+1}/${RamBatchBenchmarkProtocol.ITERATIONS}")
                        var knockMs:Long?=null
                        var ignitionMs:Long?=null
                        var batchMs:Long?=null
                        for((sequence,kind) in RamBatchBenchmarkProtocol.order(index).withIndex()) {
                            verifyAllowed(cancel)
                            if(SystemClock.elapsedRealtime()>=deadline)
                                throw IOException("Общий лимит RAM LAB 2: 30 секунд")
                            val req=RamBatchBenchmarkProtocol.request(kind)
                            pendingKind=kind.name
                            pendingIteration=index+1
                            pendingRequest=req
                            pendingStart=SystemClock.elapsedRealtime()
                            val reply=transact(conn,req,
                                { RamBatchBenchmarkProtocol.matches(kind,it) },cancel)
                            val elapsed=SystemClock.elapsedRealtime()-pendingStart
                            writer.appendLine(DmeHistoryProtocol.csv(
                                System.currentTimeMillis(),index+1,sequence+1,kind.name,
                                if(kind==RamBatchBenchmarkProtocol.Kind.BATCH)"4A37+4A49"
                                else if(kind==RamBatchBenchmarkProtocol.Kind.KNOCK)"4A37" else "4A49",
                                elapsed,reply.status,DmeHistoryProtocol.hex(req),
                                DmeHistoryProtocol.hex(reply.payload),
                                usedTransport,usedPeer))
                            writer.flush()
                            pendingRequest=null
                            pendingKind=null
                            pendingStart=0L
                            if(reply.status!="OK")
                                throw IOException("${kind.name}: отрицательный ответ ${reply.status}; тест остановлен")
                            when(kind) {
                                RamBatchBenchmarkProtocol.Kind.KNOCK -> knockMs=elapsed
                                RamBatchBenchmarkProtocol.Kind.IGNITION -> ignitionMs=elapsed
                                RamBatchBenchmarkProtocol.Kind.BATCH -> batchMs=elapsed
                            }
                        }
                        singlePairs.add((knockMs ?: throw IOException("Нет ответа KNOCK"))+
                            (ignitionMs ?: throw IOException("Нет ответа IGNITION")))
                        batches.add(batchMs ?: throw IOException("Нет ответа BATCH"))
                    }
                }
                completed=true
            } catch(e:Exception) {
                error="${e.javaClass.simpleName}: ${e.message ?: "unknown"}"
                writer.appendLine(DmeHistoryProtocol.csv(
                    System.currentTimeMillis(),pendingIteration,"",pendingKind ?: "SESSION","",
                    if(pendingStart>0L)(SystemClock.elapsedRealtime()-pendingStart) else "",
                    "ERROR: ${error}",pendingRequest?.let(DmeHistoryProtocol::hex) ?: "",
                    "",usedTransport,usedPeer))
                writer.flush()
            }
        }
        val single=RamBatchBenchmarkProtocol.stats(singlePairs)
        val batch=RamBatchBenchmarkProtocol.stats(batches)
        fun statsText(value:RamBatchBenchmarkProtocol.Stats?):String =
            if(value==null)"нет данных" else
                String.format(java.util.Locale.US,"медиана %.1f мс; p95 %d мс",
                    value.medianMs,value.p95Ms)
        val summary="RAM LAB 2: завершено пар ${singlePairs.size}/${RamBatchBenchmarkProtocol.ITERATIONS}; "+
            "два одиночных DID: ${statsText(single)}; "+
            "один пакет: ${statsText(batch)}. "+
            if(completed)"Тест завершён; только UDS 0x22."
            else "Частичный результат: ${error ?: "unknown"}. "+
                "После отказа пакет не повторяется."
        progress(summary)
        return Outcome(output,summary,completed)
    }

    fun collect(cancel:()->Boolean,progress:(String)->Unit):Outcome {
        verifyAllowed(cancel)
        val directory=context.getExternalFilesDir(null) ?: context.filesDir
        val output=File(directory,"bmw_dme_history_v1727_${System.currentTimeMillis()}.csv")
        var successful=false
        var totalDtc=0
        var details=0
        var adaptations=0
        var problem:String?=null
        output.bufferedWriter().use { writer ->
            writer.appendLine("wall_time_ms,group,request_hex,response_hex,status,dtc_code,dtc_status,description,transport,peer")
            fun row(group:String,request:ByteArray,reply:Reply,description:String,
                    transport:String,peer:String,dtc:String="",dtcStatus:String="") {
                writer.appendLine(DmeHistoryProtocol.csv(
                    System.currentTimeMillis(),group,DmeHistoryProtocol.hex(request),
                    DmeHistoryProtocol.hex(reply.payload),reply.status,dtc,dtcStatus,
                    description,transport,peer))
                writer.flush() // preserve partial diagnostic history on failure
            }
            try {
                verifyAllowed(cancel)
                progress("Поиск сети BMW / VXSCAN…")
                val (target,socket)=connect(cancel)
                socket.use { conn ->
                    progress("DME подключён, чтение адаптаций…")
                    val specs=DmeReadOnlyProbe.specs.filter { it.did in
                        setOf(0x554A,0x4A85,0x4A9D) }
                    for(spec in specs) {
                        verifyAllowed(cancel)
                        val req=byteArrayOf(0x22,(spec.did ushr 8).toByte(),spec.did.toByte())
                        val result=transact(conn,req,{ p->
                            p.size>=3 && (p[0].toInt() and 255)==0x62 &&
                                (p[1].toInt() and 255)==(spec.did ushr 8) &&
                                (p[2].toInt() and 255)==(spec.did and 255)
                        },cancel)
                        val parsed=DmeReadOnlyProbe.parse(spec,listOf(result.payload))
                        if(result.status=="OK" && parsed.status=="OK")adaptations++
                        row("ADAPTATION",req,result,
                            spec.title+" / "+parsed.shortText()+"; предварительная расшифровка",
                            target.transport,target.host)
                    }
                    progress("Чтение сохранённых DTC (UDS 0x19)…")
                    val listReq=byteArrayOf(0x19,0x02,0xFF.toByte())
                    val list=transact(conn,listReq,{p->
                        p.size>=3 && (p[0].toInt() and 255)==0x59 &&
                            (p[1].toInt() and 255)==0x02
                    },cancel)
                    val dtcs=if(list.status=="OK")
                        DmeHistoryProtocol.parseDtcList(list.payload)
                        else emptyList()
                    row("DTC_LIST",listReq,list,
                        when {
                            list.status!="OK" -> "DTC listing refused by ECU"
                            dtcs==null -> "INVALID_DTC_LIST_FORMAT; no record expansion"
                            else -> "DTC count=${dtcs.size}; inspect DTC status mask; no ECU memory modified"
                        },target.transport,target.host)
                    if(dtcs==null)throw IOException("Malformed DTC status record list")
                    totalDtc=dtcs.size
                    for(dtc in dtcs.take(DmeHistoryProtocol.MAX_DTCS)) {
                        verifyAllowed(cancel)
                        for(sub in listOf(0x04,0x06)) {
                            progress("DTC ${dtc.hex}: запись 0x${sub.toString(16)}…")
                            val request=DmeHistoryProtocol.byDtcRequest(sub,dtc.code)
                            val reply=transact(conn,request,{
                                DmeHistoryProtocol.dtcResponseMatches(sub,dtc.code,it)
                            },cancel)
                            row(if(sub==0x04)"DTC_SNAPSHOT" else "DTC_EXTENDED",
                                request,reply,
                                if(reply.status=="OK")"Raw ECU record; manufacturer format unverified"
                                else "ECU returned ${reply.status}; no data inferred",
                                target.transport,target.host,dtc.hex,dtc.statusHex)
                            details++
                        }
                    }
                    if(dtcs.size>DmeHistoryProtocol.MAX_DTCS) {
                        writer.appendLine(DmeHistoryProtocol.csv(
                            System.currentTimeMillis(),"LIMIT","","","TRUNCATED","","",
                            "Only first ${DmeHistoryProtocol.MAX_DTCS} DTCs expanded of ${dtcs.size}",
                            target.transport,target.host))
                        writer.flush()
                    }
                }
                successful=true
            }catch(e:Exception) {
                problem="${e.javaClass.simpleName}: ${e.message ?: "unknown"}"
                writer.appendLine(DmeHistoryProtocol.csv(System.currentTimeMillis(),
                    "SESSION","","","ERROR","","",problem!!,"",""))
                writer.flush()
            }
        }
        val summary="Адаптации OK: $adaptations/3; DTC: $totalDtc; доп. записей: $details. "+
            if(successful)"Чтение завершено." else "Остановка: ${problem ?: "unknown"}."
        progress(summary)
        return Outcome(output,summary,successful)
    }
}
