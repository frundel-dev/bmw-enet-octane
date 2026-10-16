package com.example.bmwenettest

/**
 * Synchronous SLOW read policy for the single DME polling owner.
 *
 * The two callbacks perform the SAME serialized OBD/UDS transactions as the
 * original Service loop. This object does not own a socket, schedule a coroutine
 * or introduce a second DME reader. Request order and decoding are unchanged.
 */
object SlowPollEngine {
    data class Values(
        val speed:Double?=null,
        val iat:Double?=null,
        val ignition:Double?=null,
        val coolant:Double?=null,
        val throttle:Double?=null,
        val stft:Double?=null,
        val ltft:Double?=null,
        val lambda:Double?=null,
        val oil:Double?=null,
        val superKnock:Int?=null
    )
    data class Result(
        val values:Values,
        val optimized:Boolean,
        val slowRequests:Int,
        val lastSlowMs:Long,
        val sensorReceivedAtMs:Long
    )

    fun poll(
        mode:String,
        profile:HighSpeedProfilePolicy.Profile,
        cycle:Long,
        previousCycleMs:Long,
        hadFailures:Boolean,
        benchmark:Boolean,
        scheduler:SlowPollScheduler,
        nowMs:Long,
        previous:Values,
        lastSlowMs:Long,
        sensorReceivedAtMs:Long,
        clockMs:()->Long,
        readObd:(Int)->ByteArray?,
        readUds:(Int)->ByteArray?
    ):Result {
        val optimized=mode=="B" || mode=="B+"
        var speed=previous.speed
        var iat=previous.iat
        var ignition=previous.ignition
        var coolant=previous.coolant
        var throttle=previous.throttle
        var stft=previous.stft
        var ltft=previous.ltft
        var lambda=previous.lambda
        var oil=previous.oil
        var superKnock=previous.superKnock
        var requests=0
        var lastSlow=lastSlowMs
        var lastSensor= sensorReceivedAtMs
        if(optimized) {
            val budget=HighSpeedProfilePolicy.optionalBudget(
                profile,cycle,previousCycleMs,hadFailures)
            // Mandatory safety OBD PIDs are polled even when CORE's optional
            // budget is zero, without adding concurrency or a new socket.
            val due=if(benchmark)scheduler.dueForBenchmark(clockMs(),budget)
                else if(budget>0)scheduler.due(clockMs(),budget)
                else emptyList()
            for(id in due) {
                val data=if(id==0x5728)readUds(0x5728) else readObd(id)
                requests++
                scheduler.record(id,clockMs(),data?.isNotEmpty()==true)
                val first=data?.firstOrNull()?.toInt()?.and(255)
                when(id) {
                    0x0D -> speed=first?.toDouble()
                    0x0F -> iat=first?.minus(40)?.toDouble()
                    0x0E -> ignition=first?.div(2.0)?.minus(64.0)
                    0x05 -> coolant=first?.minus(40)?.toDouble()
                    0x11 -> throttle=first?.times(100.0/255.0)
                    0x06 -> stft=first?.minus(128)?.times(100.0/128.0)
                    0x07 -> ltft=first?.minus(128)?.times(100.0/128.0)
                    0x44 -> lambda=data?.takeIf{it.size==2}?.let {
                        (((it[0].toInt() and 255)*256)+(it[1].toInt() and 255))*2.0/65535.0
                    }
                    0x5C -> oil=first?.minus(40)?.toDouble()
                    0x5728 -> superKnock=first
                }
            }
        } else if(nowMs-lastSlow>=2000L) {
            // Legacy A topology deliberately requests ALL SLOW values in
            // the historic order; preserve even unsupported/missing responses.
            speed=readObd(0x0D)?.firstOrNull()?.let{(it.toInt() and 255).toDouble()}
            iat=readObd(0x0F)?.let{((it[0].toInt()and 255)-40).toDouble()}
            ignition=readObd(0x0E)?.let{(it[0].toInt()and 255)/2.0-64.0}
            coolant=readObd(0x05)?.let{((it[0].toInt()and 255)-40).toDouble()}
            throttle=readObd(0x11)?.let{(it[0].toInt()and 255)*100.0/255.0}
            stft=readObd(0x06)?.firstOrNull()?.let{((it.toInt()and 255)-128)*100.0/128.0}
            ltft=readObd(0x07)?.firstOrNull()?.let{((it.toInt()and 255)-128)*100.0/128.0}
            lambda=readObd(0x44)?.takeIf{it.size>=2}?.let {
                (((it[0].toInt()and 255)*256)+(it[1].toInt()and 255))*2.0/65535.0
            }
            oil=readObd(0x5C)?.firstOrNull()?.let{((it.toInt()and 255)-40).toDouble()}
            superKnock=readUds(0x5728)?.firstOrNull()?.let{it.toInt() and 255}
            lastSensor=clockMs()
            lastSlow=nowMs
        }
        return Result(
            Values(speed,iat,ignition,coolant,throttle,stft,ltft,
                lambda,oil,superKnock),
            optimized,requests,lastSlow,lastSensor)
    }
}
