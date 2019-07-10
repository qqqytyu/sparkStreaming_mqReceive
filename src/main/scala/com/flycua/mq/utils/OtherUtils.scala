package com.flycua.mq.utils

import java.io.{BufferedReader, ByteArrayOutputStream, IOException, InputStream}
import java.net.URI
import java.text.SimpleDateFormat
import java.util.{Date, Properties}

import org.apache.commons.lang3.StringUtils
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

object OtherUtils {

    private val df: SimpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

    //将Exception转换为string
    def printException(ex:Exception): Unit ={

        val now: String = df.format(new Date)

        val buf: ByteArrayOutputStream = new java.io.ByteArrayOutputStream()

        ex.printStackTrace(new java.io.PrintWriter(buf, true))

        val exStr: String = buf.toString

        buf.close()

        println(s"ERROR:\t$now\t$exStr")

    }

    def printException(err:String): Unit ={
        val now: String = df.format(new Date)
        println(s"INFO:\t$now\t$err")
    }

    def printInfo(info:String): Unit ={
        val now: String = df.format(new Date)
        println(s"INFO:\t$now\t$info")
    }

    def printWarn(warn:String): Unit ={
        val now: String = df.format(new Date)
        println(s"INFO:\t$now\t$warn")
    }

    //去除分割字符串后，字符串内部带bom格式的问题
    def splitUnBom(str: String, separator: String): List[String] ={
        val lb: ListBuffer[String] = new ListBuffer[String]
        StringUtils.splitByWholeSeparator(str, separator).foreach(s =>{
            if(!s.isEmpty && StringUtils.substringBefore(s,"\uFEFF").isEmpty)
                lb.append(StringUtils.splitByWholeSeparator(s,"\uFEFF")(0))
            else
                lb.append(s)
        })
        lb.toList
    }

    //输入流转string
    def inputStreamToString(br: BufferedReader): String ={
        val sb:StringBuilder = new StringBuilder
        var str:String = br.readLine()
        while(str != null) {
            sb.append(str)
            str = br.readLine()
        }
        br.close()
        sb.toString
    }

    //读取配置文件
    def loadProperties_local(input: InputStream): Properties ={

        val properties: Properties = new Properties()

        properties.load(input)

        input.close()

        properties

    }

    //装载HDFS上的配置文件
    def loadProperties_hdfs(hdfs_conf: Configuration, conf_path: String, necessity:Boolean = true): Option[Properties] ={

        val system: FileSystem = FileSystem.get(hdfs_conf)

        val cp: Path = new Path(conf_path)

        if(system.exists(cp)){

            val properties: Properties = new Properties()

            properties.load(system.open(cp))

            system.close()

            Some(properties)

        } else if(necessity){
            throw new IOException(s"FlightCrewInfoMain: $conf_path\t is not exists!")
        } else
            None

    }

    //创建HDFS配置信息
    def createHadoopConf(map: mutable.HashMap[String, String]): Configuration ={
        val configuration: Configuration = new Configuration(false)
        configuration.set("fs.defaultFS", map.getOrElse("fs.defaultFS",""))
        configuration.set("ha.zookeeper.quorum", map.getOrElse("ha.zookeeper.quorum",""))
        configuration.set("dfs.replication", map.getOrElse("dfs.replication","1"))
        configuration.set("dfs.nameservices", map.getOrElse("dfs.nameservices",""))
        configuration.set("dfs.ha.namenodes.mycluster", map.getOrElse("dfs.ha.namenodes.mycluster",""))
        configuration.set("dfs.namenode.rpc-address.mycluster.nn1", map.getOrElse("dfs.namenode.rpc-address.mycluster.nn1",""))
        configuration.set("dfs.namenode.rpc-address.mycluster.nn2", map.getOrElse("dfs.namenode.rpc-address.mycluster.nn2",""))
        configuration.set("dfs.client.failover.proxy.provider.mycluster", map.getOrElse("dfs.client.failover.proxy.provider.mycluster",""))
        configuration.set("HADOOP_USER_NAME", map.getOrElse("HADOOP_USER_NAME","hdfs"))
        configuration
    }

}
