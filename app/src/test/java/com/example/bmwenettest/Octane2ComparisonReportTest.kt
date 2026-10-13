package com.example.bmwenettest

import org.junit.Assert.*
import org.junit.Test

class Octane2ComparisonReportTest {
    private fun row(time:Long,epoch:Int=0,ram:String="2700.0",
                    lambda:String="",legacy:String=""):String {
        val a=MutableList(36){""}
        a[0]="1791900000000";a[1]=time.toString();a[2]="1791900000000"
        a[3]="1";a[4]=epoch.toString();a[5]="B+"
        a[6]="2701.0";a[7]=ram;a[8]=ram.ifBlank{"2701.0"}
        a[9]=if(ram.isBlank())"OBD_01_0C" else "RAM_NKW"
        a[10]="ACTIVE";a[11]="90";a[12]="60";a[13]="150"
        a[14]="390";a[15]=if(time==4000L)"2000" else "20"
        a[16]="115";a[17]="44";a[18]="70"
        a[19]="30";a[20]="95";a[21]="0.08";a[22]="3"
        a[23]=lambda;a[27]="1.0";a[29]="1";a[30]="-2"
        a[31]="CLOSED_LOOP";a[32]="0";a[33]="5000"
        a[34]=legacy;a[35]="37"
        return a.joinToString(",")
    }
    @Test fun comparesSameCyclesWithoutInventingFuelQualityOrMissingLambda() {
        val report=Octane2ComparisonReport.analyze(sequenceOf(
            Octane2CycleCsv.HEADER,row(1000),
            row(2000,ram="",lambda="1.01",legacy="96"),
            "incomplete",row(4000,epoch=1,ram="2698")))
        assertEquals(3,report.rows)
        assertEquals(1,report.malformed)
        assertEquals(3,report.obdValid)
        assertEquals(2,report.ramValid)
        assertEquals(1,report.lambdaValid)
        assertEquals(1,report.legacyScored)
        assertEquals(1,report.reconnectEpochs)
        assertEquals(1,report.gapsOver1500Ms)
        assertEquals(3,report.targetA)
        assertEquals(0,report.targetB)
        assertTrue(report.russianReport().contains("НЕДОСТАТОЧНО ДАННЫХ"))
        assertEquals(2.0/3.0,report.achievedHz!!,0.00001)
    }
}
