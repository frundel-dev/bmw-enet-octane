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
