package com.flycua.mq.model

case class FlightInfo (
                              Flight_Id: String, Mm_Leg_Id: String, Flight_Date: String, Carrier: String,
                              Flight_No: String, Departure_Airport: String, Arrival_Airport: String,
                              Crew_Link_Line: String, Ac_Type: String, Flight_Flag: String, Op_Time:String,
                              Weather_Standard:String
                      )
