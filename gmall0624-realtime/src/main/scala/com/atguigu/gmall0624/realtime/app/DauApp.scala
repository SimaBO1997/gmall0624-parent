package com.atguigu.gmall0624.realtime.app

import java.{lang, util}
import java.text.SimpleDateFormat
import java.util.Date

import com.alibaba.fastjson.JSON
import com.atguigu.gmall0624.bean.StartUpLog
import com.atguigu.gmall0624.common.constant.GmallConstant
import com.atguigu.gmall0624.realtime.util.MyKafkaUtil
import org.apache.hadoop.conf.Configuration
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.spark.SparkConf
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.rdd.RDD
import org.apache.spark.streaming.dstream.{DStream, InputDStream}
import org.apache.spark.streaming.{Seconds, StreamingContext}
import redis.clients.jedis.Jedis
import org.apache.phoenix.spark._


object DauApp {
  def main(args: Array[String]): Unit = {
    val conf: SparkConf = new SparkConf().setMaster("local[*]").setAppName("DauApp")

    val ssc = new StreamingContext(conf, Seconds(5))

    val inputDstream: InputDStream[ConsumerRecord[String, String]] = MyKafkaUtil.getKafkaStream(GmallConstant.KAFKA_TOPIC_STARTUP, ssc)

    //去重
    //1.保存当日用户访问的清单到redis中
    //2.利用redis中的清单进行过滤


    val startuplogDstream: DStream[StartUpLog] = inputDstream.map { record =>
      val jsonString: String = record.value()
      val startUpLog: StartUpLog = JSON.parseObject(jsonString, classOf[StartUpLog])
      val date = new Date(startUpLog.ts)
      val formattor = new SimpleDateFormat("yyyy-MM-dd HH")
      val dateString: String = formattor.format(date)
      val dateArr: Array[String] = dateString.split(" ")
      startUpLog.logDate = dateArr(0)
      startUpLog.logHour = dateArr(1)
      startUpLog
    }

    //过滤


    //driver
    val filtered1Dstream: DStream[StartUpLog] = startuplogDstream.transform { rdd =>
      //……driver周期性的查询redis的清单  通过广播变量发送到executor中
//      println("过滤前" + rdd.count())
      val jedis = new Jedis("hadoop102", 6379)
      val dateStr: String = new SimpleDateFormat("yyyy-MM-dd").format(new Date)
      val dateKey: String = "dau:" + dateStr
      val dauMidSet: util.Set[String] = jedis.smembers(dateKey)
      jedis.close()
      val dauMidBC: Broadcast[util.Set[String]] = ssc.sparkContext.broadcast(dauMidSet)

      val filtereRdd: RDD[StartUpLog] = rdd.filter { startuplog =>
        val midSet: util.Set[String] = dauMidBC.value
        !midSet.contains(startuplog.mid)
      }
//      println("过滤后" + filtereRdd.count())
      filtereRdd
    }

    //利用redis无法取出一个批次内的数据，所以，每个批次都要做自查，内部去重，方法：用mid 进行分组 ，取每组第一
    val startupGroupbyMidDstream: DStream[(String, Iterable[StartUpLog])]
    = filtered1Dstream.map(startuplog => (startuplog.mid, startuplog)).groupByKey()

    val filtered2Dstream: DStream[StartUpLog] = startupGroupbyMidDstream.flatMap {
      case (mid, startuplogItr) =>
      val sortList: List[StartUpLog] = startuplogItr.toList.sortWith { (startuplog1, startuplog2) =>
        startuplog1.ts < startuplog2.ts
      }

      val top1LogList: List[StartUpLog] = sortList.take(1)
      top1LogList
    }


    //保存
    //redis   type(String List Set Hash Zset)set     key:日期 dau:2019-11-26     value: mid
    //set的写入
    //    filteredDstream.foreachRDD { rdd =>
    //      rdd.foreachPartition { startupLogItr =>
    //        //executor
    //        val jedis = new Jedis("hadoop102", 6379)
    //        for (startuplog <- startupLogItr) {
    //          val dateKey: String = "dau:" + startuplog.logDate
    //
    //          jedis.sadd(dateKey, startuplog.mid)
    //        }
    //        jedis.close()
    //      }
    //    }

    //保存
    // redis  type :set      key    dau:2019-11-26    value:  mid
    //set 的写入
    filtered2Dstream.cache()

    filtered2Dstream.foreachRDD { rdd =>
      //driver
      rdd.foreachPartition { startupLogItr =>
        // executor
        val jedis = new Jedis("hadoop102", 6379)
        for (startuplog <- startupLogItr) {
          println(startuplog)

          val dateKey: String = "dau:" + startuplog.logDate //executor
          jedis.sadd(dateKey, startuplog.mid)
        }
        jedis.close()
      }
    }

    filtered2Dstream.foreachRDD { rdd =>
      //ctrl +shift +u 切换大小写
      rdd.saveToPhoenix("GMALL0624_DAU", Seq("MID", "UID", "APPID", "AREA", "OS", "CH", "TYPE", "VS", "LOGDATE", "LOGHOUR", "TS"), new Configuration, Some("hadoop102,hadoop103,hadoop104:2181"))
    }


    ssc.start()
    ssc.awaitTermination()

  }
}
