package com.flycua.mq.utils

import java.sql.Connection
import java.text.SimpleDateFormat
import oracle.jdbc.pool.OracleDataSource
import org.apache.spark.internal.Logging

class  JdbcUtils(val jdbcUrl:String, val username:String, val password:String) extends Serializable{

    var transactionLevel:Int = Connection.TRANSACTION_READ_COMMITTED

    private val df: SimpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

    //获取连接
    def acquireConnection():Option[Connection] ={
        import scala.util.control.Breaks._
        var conn: Connection = null
        breakable{
            for(i <- 1 to 60){
                conn = getConnect
                conn.setTransactionIsolation(transactionLevel)
                if(connectionIsValid(conn)) break
                OtherUtils.printWarn(s"网络波动，准备第 ${i+1} 次重新获取JDBC连接")
                Thread.sleep(60000)
            }
        }
        Some(conn).filter(connectionIsValid)
    }

    //JDBC连接是否有效
    def connectionIsValid(conn:Connection):Boolean ={
        conn != null && conn.isValid(3)
    }

    //关闭连接
    def closeConnection(conn:Connection): Unit = {
        try{
            if(conn != null && !conn.isClosed)
                conn.close()
        }
        catch {
            case ex: Exception => OtherUtils.printException(ex)
        }
    }

    //获取ORACLE JDBC连接
    private def getConnect:Connection = {
//        不推荐使用oracle.jdbc.driver.OracleDriver
//        classOf[oracle.jdbc.OracleDriver]
        val ods: OracleDataSource = new OracleDataSource()
        ods.setLoginTimeout(10)
        ods.setURL(this.jdbcUrl)
        ods.setUser(this.username)
        ods.setPassword(this.password)
        ods.getConnection()
    }

}


