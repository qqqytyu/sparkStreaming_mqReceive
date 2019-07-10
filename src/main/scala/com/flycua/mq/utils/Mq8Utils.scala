package com.flycua.mq.utils

import java.util

import com.ibm.mq.constants.MQConstants
import com.ibm.mq._
import org.apache.spark.internal.Logging

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

class Mq8Utils extends Serializable {

    var qMgr: MQQueueManager = _

    var qmName: String = _

    var qName: String = _

    var prop: util.Hashtable[String, Object] = _

    var openOptions: Int = -1

    var gmo: MQGetMessageOptions = _

    var queue: MQQueue = _

    //初始化连接MQ参数
    def this(conf:mutable.HashMap[String, String]){
        this()
        seqToHashtable(conf)
        qMgr = new MQQueueManager(qmName, prop)
    }

    /**
      * @param waitInt 没有数据等待的时间
      * @param charsetName 字符串编码方式
      * @return 每一条是string，封装为list返回
      */
    def receiveMsg(waitInt:Int, charsetName:String): Option[String] ={

        if(prop == null || qmName == null || qName == null) throw new Exception("MQQueueManager is not initialize")

        val datas:ListBuffer[String] = new ListBuffer[String]

        try{
            //设置将要连接的队列属性
            if(openOptions == -1)
                openOptions =
                        MQConstants.getIntValue("MQOO_INPUT_AS_Q_DEF") |
                        MQConstants.getIntValue("MQOO_OUTPUT") |
                        MQConstants.getIntValue("MQOO_INQUIRE")

            if(gmo == null){
                //创建MQ消息操作选项
                gmo = new MQGetMessageOptions()

                //在同步点控制下获取消息
                gmo.options = gmo.options + MQConstants.getIntValue("MQGMO_SYNCPOINT")

                //如果在队列上没有消息则等待
                gmo.options = gmo.options + MQConstants.getIntValue("MQGMO_FAIL_IF_QUIESCING")

                //如果队列管理器停顿则失败
                gmo.waitInterval = waitInt  //设置等待的毫秒时间限制

                gmo.matchOptions = MQConstants.getIntValue("MQMO_MATCH_MSG_ID")
            }

            //如果关闭就从新打开
            if (qMgr == null || !qMgr.isConnected()) qMgr = new MQQueueManager(qmName, prop)

            //连接队列
            if(queue == null || !queue.isOpen()) queue = qMgr.accessQueue(qName, openOptions)

            //当前队列深度是否有值
            if(queue.getCurrentDepth > 0) {
                // 要读的队列的消息
                val retrieve: MQMessage = new MQMessage()

                // 从队列中取出消息
                queue.get(retrieve, gmo)

                // 是否取出数据
                if(retrieve.getMessageLength > 0) {
                    //创建字节数组
                    val bytes: Array[Byte] = new Array[Byte](retrieve.getMessageLength)

                    //将获取到的消息放到字节数组中
                    retrieve.readFully(bytes)

                    Some(new String(bytes, charsetName))
                } else None
            } else None
        } catch {
            case ex:MQException =>
                if(ex.completionCode != 2 && ex.reasonCode != 2033){
                    OtherUtils.printWarn("A WebSphere MQ error occurred : Completion code "
                            + ex.completionCode + " Reason code " + ex.reasonCode)
                    None
                } else {
                    closeQMgr()
                    throw ex
                }
            case ex:Exception =>
                closeQMgr()
                throw ex
        }

    }

    //关闭连接
    def closeQMgr(): Unit ={
        if (qMgr != null && qMgr.isConnected()){
            qMgr.disconnect()
        }
    }

    //将传入参数转换为MQ初始化参数
    private def seqToHashtable(conf:mutable.HashMap[String, String]): Unit ={
        if(prop == null){
            prop = new util.Hashtable()
            conf.foreach {
                case ("hostname", value) => prop.put(MQC.HOST_NAME_PROPERTY, value)
                case ("channel", value) => prop.put(MQC.CHANNEL_PROPERTY, value)
                case ("port", value) => prop.put(MQC.PORT_PROPERTY, Integer.valueOf(value))
                case ("CCSID", value) => prop.put(MQC.CCSID_PROPERTY, Integer.valueOf(value))
                case ("userID", value) if !value.equals("NULL") => prop.put(MQC.USER_ID_PROPERTY, value)
                case ("password", value) if !value.equals("NULL") => prop.put(MQC.PASSWORD_PROPERTY, value)
                case ("qmName", value) => qmName = value
                case ("qName", value) => qName = value
                case _ =>
            }
        }
    }

}

object Mq8Utils{

    private var mq8Utils: Mq8Utils = _

    def apply(conf: mutable.HashMap[String, String]): Mq8Utils = {

        if(mq8Utils == null) mq8Utils = new Mq8Utils(conf)

        mq8Utils

    }

}
