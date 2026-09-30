package com.example.bmwenettest

import android.app.Activity
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.*
import java.io.InputStream
import java.net.*
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var status: TextView
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(36,40,36,36) }
        root.addView(TextView(this).apply { text="BMW ENET TEST v0.2"; textSize=25f; gravity=Gravity.CENTER_HORIZONTAL })
        root.addView(TextView(this).apply { text="G20 • HSFZ • read-only DME test"; textSize=14f; gravity=Gravity.CENTER_HORIZONTAL })
        root.addView(Button(this).apply { text="SCAN + TEST DME"; setOnClickListener { runTest() } })
        status = TextView(this).apply { text="Connect ENET → USB-C, ignition ON, then press the button."; textSize=15f; setPadding(0,24,0,0); setTextIsSelectable(true) }
        root.addView(ScrollView(this).apply { addView(status) })
        setContentView(root)
    }

    private fun runTest() {
        status.text = "Scanning…"
        executor.execute {
            val out = mutableListOf<String>()
            try {
                val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                val network = cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true }
                if (network == null) out += "Ethernet: NOT FOUND" else {
                    val lp = cm.getLinkProperties(network)
                    out += "Ethernet: CONNECTED"
                    out += "Interface: ${lp?.interfaceName ?: "?"}"
                    out += "Android IP: ${lp?.linkAddresses?.joinToString { it.address.hostAddress ?: "?" } ?: "?"}"
                    val d = discover(network)
                    if (d == null) out += "\nBMW HSFZ discovery: NO REPLY" else {
                        out += "\nBMW: FOUND"
                        out += "Gateway: ${d.ip}"
                        out += "Discovery VIN: ${d.vin ?: "not parsed"}"
                        out += "Discovery bytes: ${d.bytes}"
                        val socket = network.socketFactory.createSocket() as Socket
                        socket.soTimeout = 2500
                        socket.connect(InetSocketAddress(d.ip, 6801), 2500)
                        out += "TCP 6801: OPEN ✓"
                        val vinReq = hsfzDiag(0xF4, 0x12, byteArrayOf(0x22, 0xF1.toByte(), 0x90.toByte()))
                        out += "\nDME 0x12 → UDS 22 F190 (VIN)"
                        out += "TX: ${hex(vinReq)}"
                        socket.getOutputStream().write(vinReq); socket.getOutputStream().flush()
                        val vinFrames = readFrames(socket.getInputStream(), 2)
                        if (vinFrames.isEmpty()) out += "RX: timeout / no frame"
                        else vinFrames.forEachIndexed { i, f -> out += "RX${i+1}: ${hex(f)}"; decodeVin(f)?.let { out += "DME VIN: $it" } }
                        val rpmReq = hsfzDiag(0xF4, 0x12, byteArrayOf(0x01, 0x0C))
                        out += "\nDME 0x12 → OBD 01 0C (RPM, experimental)"
                        out += "TX: ${hex(rpmReq)}"
                        socket.getOutputStream().write(rpmReq); socket.getOutputStream().flush()
                        val rpmFrames = readFrames(socket.getInputStream(), 2)
                        if (rpmFrames.isEmpty()) out += "RX: timeout / no frame"
                        else rpmFrames.forEachIndexed { i, f ->
                            out += "RX${i+1}: ${hex(f)}"
                            decodeRpm(f)?.let { out += "RPM: %.0f rpm".format(it) }
                            decodeNegative(f)?.let { out += "UDS/OBD negative response: $it" }
                        }
                        socket.close()
                        out += "\nREAD-ONLY test complete. No coding, flashing or write service sent."
                    }
                }
            } catch (e: Exception) { out += "\nERROR ${e.javaClass.simpleName}: ${e.message}" }
            main.post { status.text = out.joinToString("\n") }
        }
    }

    data class Discovery(val ip:String, val vin:String?, val bytes:Int)

    private fun discover(network: Network): Discovery? {
        val s=DatagramSocket(null); network.bindSocket(s); s.reuseAddress=true; s.broadcast=true; s.soTimeout=2500; s.bind(InetSocketAddress(0))
        val req=byteArrayOf(0,0,0,0,0,0x11)
        listOf("169.254.255.255","255.255.255.255").forEach { try{s.send(DatagramPacket(req,req.size,InetAddress.getByName(it),6811))}catch(_:Exception){} }
        return try {
            val b=ByteArray(1024); val p=DatagramPacket(b,b.size); s.receive(p); val data=p.data.copyOf(p.length)
            val a=String(data,StandardCharsets.US_ASCII); val m="BMWVIN"; val x=a.indexOf(m)
            val vin=if(x>=0) a.substring(x+m.length).filter{it.isLetterOrDigit()}.take(17).takeIf{it.length==17} else null
            Discovery(p.address.hostAddress?:"?",vin,p.length)
        } catch(_:SocketTimeoutException){null} finally{s.close()}
    }

    private fun hsfzDiag(src:Int, dst:Int, uds:ByteArray):ByteArray {
        val len=2+uds.size
        return byteArrayOf((len ushr 24).toByte(),(len ushr 16).toByte(),(len ushr 8).toByte(),len.toByte(),0x00,0x01,src.toByte(),dst.toByte()) + uds
    }

    private fun readFrames(input:InputStream, max:Int):List<ByteArray> {
        val result= mutableListOf<ByteArray>()
        repeat(max) {
            try {
                val h=readExact(input,6) ?: return@repeat
                val len=((h[0].toInt() and 255) shl 24) or ((h[1].toInt() and 255) shl 16) or ((h[2].toInt() and 255) shl 8) or (h[3].toInt() and 255)
                if(len<0 || len>65536) return result
                val body=readExact(input,len) ?: return result
                result += h+body
                val type=((h[4].toInt() and 255) shl 8) or (h[5].toInt() and 255)
                if(type==1) return result
            } catch(_:SocketTimeoutException){ return result }
        }
        return result
    }

    private fun readExact(input:InputStream,n:Int):ByteArray? {
        val b=ByteArray(n); var off=0
        while(off<n){ val r=input.read(b,off,n-off); if(r<0)return null; off+=r }
        return b
    }

    private fun payload(frame:ByteArray):ByteArray? {
        if(frame.size<8)return null
        val type=((frame[4].toInt() and 255) shl 8) or (frame[5].toInt() and 255)
        return if(type==1) frame.copyOfRange(8,frame.size) else null
    }

    private fun decodeVin(frame:ByteArray):String? {
        val p=payload(frame)?:return null
        if(p.size>=20 && p[0]==0x62.toByte() && p[1]==0xF1.toByte() && p[2]==0x90.toByte())
            return String(p.copyOfRange(3,20),StandardCharsets.US_ASCII).filter{it.code in 32..126}
        return null
    }

    private fun decodeRpm(frame:ByteArray):Double? {
        val p=payload(frame)?:return null
        for(i in 0 until p.size-3) if(p[i]==0x41.toByte() && p[i+1]==0x0C.toByte())
            return (((p[i+2].toInt() and 255)*256)+(p[i+3].toInt() and 255))/4.0
        return null
    }

    private fun decodeNegative(frame:ByteArray):String? {
        val p=payload(frame)?:return null
        return if(p.size>=3 && p[0]==0x7F.toByte()) "service=${"%02X".format(p[1])}, NRC=${"%02X".format(p[2])}" else null
    }

    private fun hex(b:ByteArray)=b.joinToString(" "){"%02X".format(it.toInt() and 255)}
}
