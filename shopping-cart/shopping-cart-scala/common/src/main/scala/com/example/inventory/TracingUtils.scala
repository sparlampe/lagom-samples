package com.example.inventory

import io.opentracing.propagation.{Format, TextMapAdapter}
import io.opentracing.util.GlobalTracer
import io.opentracing.{SpanContext}
import java.util.{HashMap => JHashMap, Map => JMap}
import scala.jdk.CollectionConverters._

object TracingUtils {
  
  def getCurrentSpanContext: Map[String, String] ={
    val context: SpanContext = GlobalTracer.get().activeSpan().context()
    val contextHeaders: JMap[String, String] = new JHashMap[String, String]()
    GlobalTracer.get().inject(
      context,
      Format.Builtin.TEXT_MAP,
      new TextMapAdapter(contextHeaders))
    contextHeaders.asScala.toMap
  }
}