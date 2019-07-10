import java.io.{BufferedReader, ByteArrayInputStream, InputStreamReader}
import java.net.URI
import java.util.{Properties, StringTokenizer}

import com.flycua.mq.model.{FlightInfo, SysInfo}
import com.flycua.mq.utils.OtherUtils
import org.apache.commons.io.input.BOMInputStream
import org.apache.commons.lang3.StringUtils
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.spark.rdd.RDD
import org.apache.spark.streaming.dstream.ReceiverInputDStream
import org.apache.spark.streaming.{Duration, Seconds, StreamingContext}
import org.apache.spark.{SparkConf, SparkContext, TaskContext}

import scala.collection.mutable.ListBuffer
import scala.xml.{Elem, XML}

object sparkTest {

    def main(args: Array[String]): Unit = {

//        val properties: Properties = loadHdfsProperties()
//
//        println(properties.getProperty("MQ_CHARSET_NAME"))

    }

//    //装载HDFS上的配置文件
//    private def loadHdfsProperties(): Properties ={
//
//        val hdfs_properties: Properties = OtherUtils.loadProperties_local(this.getClass.getResourceAsStream("/static_config.properties"))
//
//        val HDFS_CONF: Configuration = OtherUtils.createHadoopConf(hdfs_properties)
//
//        OtherUtils.loadProperties_hdfs(HDFS_CONF, hdfs_properties.getProperty("HDFS_FLIGHT_CREW_CONFIG_DIR"))
//
//    }

}
