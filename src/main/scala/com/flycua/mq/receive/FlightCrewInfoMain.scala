package com.flycua.mq.receive

import java.sql.{Connection, PreparedStatement, ResultSet}
import java.util.{Properties, Timer, TimerTask}
import com.flycua.mq.model.{CrewRoot, FlightInfo, PolitInfo, SysInfo}
import com.flycua.mq.utils.{JdbcUtils, OtherUtils}
import org.apache.hadoop.conf.Configuration
import org.apache.spark.SparkConf
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.internal.Logging
import org.apache.spark.streaming.dstream.{DStream, ReceiverInputDStream}
import org.apache.spark.streaming.{Durations, StreamingContext}
import scala.collection.{immutable, mutable}
import scala.collection.mutable.ListBuffer
import scala.xml.{Elem, NodeSeq, XML}

object FlightCrewInfoMain{

    //hdfs配置信息
    private var HDFS_CONF: Configuration = _

    //配置信息
    private val conf_map: mutable.HashMap[String, String] = new mutable.HashMap[String, String]

    //MQ Receiver 配置信息
    private val mq_list: ListBuffer[mutable.HashMap[String, String]] = new ListBuffer[mutable.HashMap[String, String]]

    private var jdbc_util:(JdbcUtils,Int) = _

    //配置信息更新标志
    private var update_config: Boolean = false

    def main(args: Array[String]): Unit = {

        //本地测试
//        val ssc: StreamingContext = createStreamingContext_local

        //集群运行
        setMQConf(OtherUtils.loadProperties_local(this.getClass.getResourceAsStream("/static_config.properties")))
        setMQConf(loadHdfsProperties().orNull)
        //重置更新标志
        this.update_config = false
        val ssc: StreamingContext = StreamingContext.getOrCreate(
            conf_map.getOrElse("spark.checkpoint.directory","/tmp/cua-flightCrew/checkpoint"),
            createStreamingContext,
            HDFS_CONF
        )
        //定时任务，间隔一定时间更新配置信息
        new Timer("update config timed task").schedule(new TimerTask{
            override def run(): Unit = FlightCrewInfoMain.updateConfigAtTime()
        },1000,120000)
        //集群运行

        ssc.start()

        ssc.awaitTermination()

        ssc.stop()

    }

    //本地运行，创建ssc
    private def createStreamingContext_local: StreamingContext ={

        //更新配置信息
        setMQConf(OtherUtils.loadProperties_local(this.getClass.getResourceAsStream("/static_config.properties")))
        setMQConf(OtherUtils.loadProperties_local(this.getClass.getResourceAsStream("/dynamic_config.properties")))
        //重置更新标志
        this.update_config = false

        val conf: SparkConf = new SparkConf()
                .setAppName("Receiver FlightCrewInfo")
                .setMaster("local[6]")
                .set("spark.streaming.blockInterval", conf_map.getOrElse("spark.streaming.blockInterval","200"))

        val ssc: StreamingContext = new StreamingContext(conf, Durations.seconds(conf_map.getOrElse("spark.streaming.seconds","1").toInt))

        ssc.sparkContext.setLogLevel(conf_map.getOrElse("spark.log.level","ERROR"))

        if(HDFS_CONF == null)
            HDFS_CONF = OtherUtils.createHadoopConf(conf_map)

        streamingLogic(ssc)

        ssc

    }

    //集群创建ssc
    private def createStreamingContext(): StreamingContext ={

        val conf: SparkConf = new SparkConf()
                .setAppName("Receiver FlightCrewInfo")
                .set("spark.streaming.blockInterval", conf_map.getOrElse("spark.streaming.blockInterval","200"))

        val ssc: StreamingContext = new StreamingContext(conf, Durations.seconds(conf_map.getOrElse("spark.streaming.seconds","1").toInt))

        ssc.checkpoint(conf_map.getOrElse("spark.checkpoint.directory","/tmp/cua-flightCrew/checkpoint"))

        ssc.sparkContext.setLogLevel(conf_map.getOrElse("spark.log.level","ERROR"))

        streamingLogic(ssc)

        ssc

    }

    //装载HDFS上的配置文件
    private def loadHdfsProperties(): Option[Properties] ={

        if(HDFS_CONF == null) HDFS_CONF = OtherUtils.createHadoopConf(conf_map)

        OtherUtils.loadProperties_hdfs(
            HDFS_CONF, conf_map.getOrElse("HDFS_FLIGHT_CREW_CONFIG_DIR","/tmp/cua-flightCrew/config/dynamic_config.properties"))

    }

    //将配置信息加载到HashMap中
    private def setMQConf(ps: Properties): Unit ={
        import scala.collection.JavaConverters._
        if(ps == null) throw new Exception("Properties is null!!!")
        ps.stringPropertyNames.asScala.foreach(key =>{
            if(conf_map.getOrElse(key,"") != ps.getProperty(key)) {
                conf_map.put(key, ps.getProperty(key))
                update_config = true
            }
        })
    }

    //ssc执行逻辑
    private def streamingLogic(ssc: StreamingContext): Unit ={

        var jdbc_br: Broadcast[(JdbcUtils,Int)] = ssc.sparkContext.broadcast(createOrUpdateJDBC())

        val jdbc_sql: Broadcast[String] = ssc.sparkContext.broadcast(conf_map.getOrElse("FLIGHT.CREW.SQL",""))

        var mq_br: Broadcast[List[mutable.HashMap[String, String]]] = ssc.sparkContext.broadcast(createOrUpdateMQConfig())

        //启动多个receiver接收数据
        val mq_stream: immutable.IndexedSeq[ReceiverInputDStream[String]] = (0 until conf_map.getOrElse("spark.receiver.num", "2").toInt).map(i => {
            ssc.receiverStream(
                new FlightCrewInfoReceiver(
                    conf_map.getOrElse("MQ_WAIT_INT","1000").toInt,
                    conf_map.getOrElse("MQ_CHARSET_NAME","UTF-8"),
                    mq_br,i
                )
            )
        })

        //合并处理
        val mq_rdd: DStream[String] = ssc.union(mq_stream)

        //将MQ XML数据信息转换为类对象
        val model_rdd: DStream[(String,CrewRoot)] = mq_rdd.map(str => {
            val mq_xml: String = toXmlStr(str)
            val mq_elem: Elem = XML.loadString(mq_xml)

            val sys_seq: NodeSeq = mq_elem \ "SysInfo"
            val sysInfo: SysInfo = SysInfo(
                sys_seq \ "MessageSequenceID" text, sys_seq \ "ServiceType" text,
                sys_seq \ "MessageType" text, sys_seq \ "SendDateTime" text,
                sys_seq \ "CreateDateTime" text)

            val flight_seq: NodeSeq = mq_elem \ "FlightInfo"
            val flightInfo: FlightInfo = FlightInfo(
                flight_seq \ "Flight_Id" text, flight_seq \ "Mm_Leg_Id" text,
                flight_seq \ "Flight_Date" text, flight_seq \ "Carrier" text,
                flight_seq \ "Flight_No" text, flight_seq \ "Departure_Airport" text,
                flight_seq \ "Arrival_Airport" text, flight_seq \ "Crew_Link_Line" text,
                flight_seq \ "Ac_Type" text, flight_seq \ "Flight_Flag" text,
                flight_seq \ "Op_Time" text, flight_seq \ "Weather_Standard" text
            )

            val politInfoes: ListBuffer[PolitInfo] = new ListBuffer[PolitInfo]()
            mq_elem \ "CrewInfo" \ "PolitInfo" foreach (node => {
                //过滤掉虚班任务人员信息
                if(!(node \ "Rank_No" text).equals("S004") || !(node \ "Rank_Name" text).equals("航班任务搭机") || !(node \ "Fjs_Order" text).equals("0"))
                    politInfoes.append(PolitInfo(
                        node \ "Flight_Id" text, node \ "Fxw_Id" text,
                        node \ "Org_Code" text, node \ "Post" text,
                        node \ "Prank" text, node \ "Staff_Code" text,
                        node \ "Staff_Name" text, node \ "Eng_Surname" text,
                        node \ "Eng_Name" text, node \ "License_No" text,
                        node \ "Operate" text, node \ "FirstLaunch" text,
                        node \ "Board_Card_No" text, node \ "Passport_Code" text,
                        node \ "Visa_Code" text, node \ "Sex" text,
                        node \ "Birthday" text, node \ "Nationality" text,
                        node \ "Mobile_Tel" text, node \ "P_Code" text,
                        node \ "Id_No" text, node \ "Rank_No" text,
                        node \ "Rank_Name" text, node \ "Rec_Id" text,
                        node \ "Fjs_Order" text, node \ "Ts_Flag" text,
                        node \ "Op_Time" text
                    ))
            })

            (mq_xml,CrewRoot(sysInfo, flightInfo, politInfoes))
        })

        //存储数据到oracle, (成功标记，1)
        val mark_rdd: DStream[(Int, Int)] = model_rdd.mapPartitions(iter => new Iterator[(Int, Int)] with Logging {

            private var conn: Connection = _

            private var jdbc_br_version: Int = 0

            override def hasNext: Boolean = iter.hasNext || {
                jdbc_br.value._1.closeConnection(conn)
                false
            }

            override def next(): (Int, Int) = {
                createOrUpdateConn()
                saveAsJdbc(iter.next(), conn, jdbc_sql.value)
            }

            // 更新连接或创建连接
            private def createOrUpdateConn(): Unit ={
                if(!jdbc_br.value._1.connectionIsValid(conn)){
                    createConn()
                    jdbc_br_version = jdbc_br.value._2
                } else if(jdbc_br.value._2 > jdbc_br_version){
                    jdbc_br_version = jdbc_br.value._2
                    jdbc_br.value._1.closeConnection(conn)
                    createConn()
                    this.logWarning(s"JDBC broadcast version update, vsersion:$jdbc_br_version")
                }
            }

            //创建连接
            private def createConn(): Unit =jdbc_br.value._1.acquireConnection() match {
                    case Some(connection: Connection) =>
                        conn = connection
                        conn.setAutoCommit(false)
                    case None =>
                        this.logError("JDBC Connection is not valid, streaming process stop")
                    //                            ssc.stop()
            }

        })

        mark_rdd.reduceByKey(_+_).foreachRDD(rdd=>{

            // 集群时启用
            if(update_config){
                OtherUtils.printInfo("发现配置文件数据出现变动，更新配置数据")
                rdd.sparkContext.setLogLevel(conf_map.getOrElse("spark.log.level","INFO"))
                jdbc_br = rdd.sparkContext.broadcast(createOrUpdateJDBC())
                jdbc_br.unpersist(true)
                mq_br = rdd.sparkContext.broadcast(createOrUpdateMQConfig())
                mq_br.unpersist(true)
                update_config = false
                OtherUtils.printInfo("更新配置数据完成")
            }
            // 集群时启用


            rdd.foreachPartition(_.foreach{
                case (1, num:Int) => OtherUtils.printInfo(s"入库成功条数:$num")
                case (-1, num:Int) => OtherUtils.printInfo(s"入库失败条数:$num")
            })
        })
//        mq_rdd.foreachRDD(rdd=>rdd.foreach(s=>println(toXmlStr(s))))

    }

    //读取HDFS上配置文件信息并更新
    def updateConfigAtTime(): Unit ={
        OtherUtils.printInfo("准备开始读取配置文件信息")
        loadHdfsProperties() match {
            case Some(properties:Properties) => setMQConf(properties)
            case None => OtherUtils.printWarn(s"为在HDFS中读取到指定配置文件信息: ${conf_map.getOrElse("HDFS_FLIGHT_CREW_CONFIG_DIR","/tmp/flightCrew/config/dynamic_config.properties")}")
        }
        OtherUtils.printInfo("读取配置文件信息结束")
    }

    //成功（1,1）失败（-1,1）
    private def saveAsJdbc(mq_tup: (String,CrewRoot), conn: Connection, sql:String):(Int, Int) ={
        val crewRoot: CrewRoot = mq_tup._2
        try{
            val ps: PreparedStatement = conn.prepareStatement(sql, ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY)
            ps.setString(1,crewRoot.sysInfo.MessageSequenceID)
            ps.setString(2,crewRoot.sysInfo.ServiceType)
            ps.setString(3,crewRoot.sysInfo.MessageType)
            ps.setString(4,crewRoot.sysInfo.SendDateTime)
            ps.setString(5,crewRoot.sysInfo.CreateDateTime)
            ps.setString(6,crewRoot.flightInfo.Flight_Id)
            ps.setString(7,crewRoot.flightInfo.Mm_Leg_Id)
            ps.setString(8,crewRoot.flightInfo.Flight_Date)
            ps.setString(9,crewRoot.flightInfo.Carrier)
            ps.setString(10,crewRoot.flightInfo.Flight_No)
            ps.setString(11,crewRoot.flightInfo.Departure_Airport)
            ps.setString(12,crewRoot.flightInfo.Arrival_Airport)
            ps.setString(13,crewRoot.flightInfo.Crew_Link_Line)
            ps.setString(14,crewRoot.flightInfo.Ac_Type)
            ps.setString(15,crewRoot.flightInfo.Flight_Flag)
            ps.setString(16,crewRoot.flightInfo.Op_Time)
            ps.setString(17,crewRoot.flightInfo.Weather_Standard)
            crewRoot.crewInfo.foreach(politInfo=>{
                ps.setString(18,politInfo.fxw_Id)
                ps.setString(19,politInfo.org_Code)
                ps.setString(20,politInfo.Post)
                ps.setString(21,politInfo.Prank)
                ps.setString(22,politInfo.Staff_Code)
                ps.setString(23,politInfo.Staff_Name)
                ps.setString(24,politInfo.Eng_Surname)
                ps.setString(25,politInfo.Eng_Name)
                ps.setString(26,politInfo.License_No)
                ps.setString(27,politInfo.Operate)
                ps.setString(28,politInfo.Board_Card_No)
                ps.setString(29,politInfo.Passport_Code)
                ps.setString(30,politInfo.Visa_Code)
                ps.setString(31,politInfo.Sex)
                ps.setString(32,politInfo.Birthday)
                ps.setString(33,politInfo.Nationality)
                ps.setString(34,politInfo.Mobile_Tel)
                ps.setString(35,politInfo.P_Code)
                ps.setString(36,politInfo.Id_No)
                ps.setString(37,politInfo.Rank_No)
                ps.setString(38,politInfo.Rank_Name)
                ps.setString(39,politInfo.Rec_Id)
                ps.setString(40,politInfo.Fjs_Order)
                ps.setString(41,politInfo.Ts_Flag)
                ps.executeUpdate()
            })
            conn.commit()
            (1,1)
        } catch {
            case ex:Exception =>
                OtherUtils.printException(ex)
                OtherUtils.printException(mq_tup._1)
                conn.rollback()
                (-1,1)
        }
    }

    //创建或更新JDBC配置信息
    private def createOrUpdateJDBC(): (JdbcUtils, Int) ={
        if(jdbc_util == null)
            jdbc_util = (new JdbcUtils(conf_map.getOrElse("JDBC_PATH",""),
                conf_map.getOrElse("JDBC_USER",""),
                conf_map.getOrElse("JDBC_PWD","")
            ),1)
        else{
            if(!jdbc_util._1.jdbcUrl.equals(conf_map.getOrElse("JDBC_PATH",""))
                        || !jdbc_util._1.password.equals(conf_map.getOrElse("JDBC_USER",""))
                        || !jdbc_util._1.username.equals(conf_map.getOrElse("JDBC_PWD","")))
                jdbc_util = (new JdbcUtils(conf_map.getOrElse("JDBC_PATH",""),
                    conf_map.getOrElse("JDBC_USER",""),
                    conf_map.getOrElse("JDBC_PWD","")
                ),jdbc_util._2 + 1)
        }
        jdbc_util
    }

    //创建或更新MQ配置信息
    private def createOrUpdateMQConfig(): List[mutable.HashMap[String, String]] ={
        if(mq_list.isEmpty)
            (1 to conf_map.getOrElse("spark.receiver.num","2").toInt).foreach(_ => mq_list += new mutable.HashMap[String, String])
        mq_list.indices.foreach(i=>{
            var version: Boolean = false

            if(!mq_list(i).getOrElse("hostname","").equals(conf_map.getOrElse(s"MQ_HOSTNAME_${(i%2)+1}",""))){
                mq_list(i).put("hostname", conf_map.getOrElse(s"MQ_HOSTNAME_${(i%2)+1}",""))
                version = true
            }

            if(!mq_list(i).getOrElse("port","").equals(conf_map.getOrElse(s"MQ_PORT_${(i%2)+1}",""))) {
                mq_list(i).put("port", conf_map.getOrElse(s"MQ_PORT_${(i%2)+1}",""))
                version = true
            }

            if(!mq_list(i).getOrElse("channel","").equals(conf_map.getOrElse(s"MQ_CHANNEL_${(i%2)+1}",""))) {
                mq_list(i).put("channel", conf_map.getOrElse(s"MQ_CHANNEL_${(i%2)+1}",""))
                version = true
            }

            if(!mq_list(i).getOrElse("qmName","").equals(conf_map.getOrElse(s"MQ_QUEUE_MANAGER_NAME_${(i%2)+1}",""))) {
                mq_list(i).put("qmName", conf_map.getOrElse(s"MQ_QUEUE_MANAGER_NAME_${(i%2)+1}",""))
                version = true
            }

            if(!mq_list(i).getOrElse("userID","").equals(conf_map.getOrElse(s"MQ_USER_ID_${(i%2)+1}","NULL"))) {
                mq_list(i).put("userID", conf_map.getOrElse(s"MQ_USER_ID_${(i%2)+1}","NULL"))
                version = true
            }

            if(!mq_list(i).getOrElse("password","").equals(conf_map.getOrElse(s"MQ_PWD_${(i%2)+1}","NULL"))) {
                mq_list(i).put("password", conf_map.getOrElse(s"MQ_PWD_${(i%2)+1}","NULL"))
                version = true
            }

            if(!mq_list(i).getOrElse("CCSID","").equals(conf_map.getOrElse("MQ_CCSID",""))) {
                mq_list(i).put("CCSID", conf_map.getOrElse("MQ_CCSID",""))
                version = true
            }

            if(!mq_list(i).getOrElse("qName","").equals(conf_map.getOrElse("MQ_QUEUE_NAME",""))) {
                mq_list(i).put("qName", conf_map.getOrElse("MQ_QUEUE_NAME",""))
                version = true
            }
            if(version) mq_list(i).put("version", (mq_list(i).getOrElse("version","0").toInt + 1).toString)
        })
        mq_list.toList
    }

    //获取数据“<TopicData><![CDATA[]]></TopicData>”中的数据
    private def toXmlStr(str:String):String ={

        val headSplit: List[String] = OtherUtils.splitUnBom(str,"<TopicData><![CDATA[")

        if(headSplit.length != 2)
            OtherUtils.printException(s"FlightCrewInfoMain : xml data did not comply with the format! \n $str")

        val xmls: List[String] = OtherUtils.splitUnBom(headSplit(1),"]]></TopicData>")

        if(xmls.length != 2)
            OtherUtils.printException(s"FlightCrewInfoMain : xml data did not comply with the format! \n $str")

        xmls.head
    }

}
