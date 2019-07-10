package com.flycua.mq.receive

import java.util
import java.util.stream.Collectors

import com.flycua.mq.utils.{Mq8Utils, OtherUtils}
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.internal.Logging
import org.apache.spark.storage.StorageLevel
import org.apache.spark.streaming.receiver.Receiver

import scala.collection.mutable

class FlightCrewInfoReceiver(
    val waitInt:Int, val charsetName:String, val mq_confs:Broadcast[List[mutable.HashMap[String, String]]], receiverNum:Int
    ) extends Receiver[String](StorageLevel.MEMORY_AND_DISK) with Logging{

    private var mq8Utils: Mq8Utils = _

    private var version: Int = 0

    override def onStart(): Unit = {
        try{
            killThreadByName("FlyCua FlightCrewInfoReceiver")
            OtherUtils.printInfo("FlightCrewInfoReceiver ready to start")
            new Thread("FlyCua FlightCrewInfoReceiver") {
                override def run() {
                    receive()
                }
            }.start()
            OtherUtils.printInfo("FlightCrewInfoReceiver ready to succeed")
        }catch {
            case ex: Exception =>
                OtherUtils.printException(ex)
                myRestart(ex.getMessage)
        }
    }

    override def onStop(): Unit = {
        OtherUtils.printInfo("FlightCrewInfoReceiver ready to die")
        killThreadByName("FlyCua FlightCrewInfoReceiver")
        OtherUtils.printInfo("FlightCrewInfoReceiver is over")
    }

    //MQ数据接收主体
    private def receive():Unit ={
        try{
            while(!Thread.interrupted){
                createOrUpateMQReceive()
                mq8Utils.receiveMsg(waitInt, charsetName) match {
                    case Some(mq_mess: String) => this.store(mq_mess)
                    case None => OtherUtils.printWarn("FlightCrewInfoReceiver: No data received")
                }
            }
            OtherUtils.printWarn("FlightCrewInfoReceiver receive kill command")
        }catch {
            case ex: Exception =>
                OtherUtils.printException(ex)
                myRestart(ex.getMessage)
        }
    }

    private def createOrUpateMQReceive(): Unit ={
        val vs: Int = mq_confs.value(receiverNum).getOrElse("version","0").toInt
        if(mq8Utils == null) {
            mq8Utils = new Mq8Utils(mq_confs.value(receiverNum))
        } else if (vs > version){
            mq8Utils.closeQMgr()
            mq8Utils = new Mq8Utils(mq_confs.value(receiverNum))
            OtherUtils.printWarn(s"FlightCrewInfoReceiver version update, version:$vs")
        }
        version = vs
    }

    //根据线程名来kill掉线程
    private def killThreadByName(name:String): Unit ={
        import scala.collection.JavaConverters._
        val allThread: util.Map[Thread, Array[StackTraceElement]] = Thread.getAllStackTraces
        val threadNames: mutable.Buffer[Thread] = allThread.keySet().stream().collect(Collectors.toList()).asScala
        threadNames.foreach(thread =>{
            if(thread.getName.equals(name)){
                thread.interrupt()
                OtherUtils.printWarn(name + "thread is kill")
            }
        })
    }

    //Receiver 重启服务
    private def myRestart(ex:String):Unit ={
        import scala.util.control.Breaks._
        breakable{
            while(true){
                try{
                    OtherUtils.printWarn(s"FlightCrewInfoReceiver restart\t ex mess: $ex")
                    restart(ex)
                    OtherUtils.printWarn(s"FlightCrewInfoReceiver restart succeed!")
                    break
                }catch {
                    case ex:Exception =>
                        OtherUtils.printException(ex.getMessage)
                        Thread.sleep(60000)
                }
            }
        }
    }

}
