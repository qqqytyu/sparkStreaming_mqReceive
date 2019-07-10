package com.flycua.mq.model

import scala.collection.mutable.ListBuffer

case class CrewRoot (sysInfo: SysInfo, flightInfo: FlightInfo, crewInfo: ListBuffer[PolitInfo])
