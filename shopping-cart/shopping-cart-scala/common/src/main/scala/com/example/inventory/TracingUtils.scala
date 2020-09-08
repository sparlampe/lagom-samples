package com.example.inventory

import io.opentracing.propagation.{Format, TextMapAdapter}
import io.opentracing.util.GlobalTracer
import io.opentracing.{Scope, Span, SpanContext, Tracer}
import java.util.{HashMap => JHashMap, Map => JMap}
import scala.jdk.CollectionConverters._

object TracingUtils {
  
  def createCustomSpan[T](parentContext: Map[String, String], spanName: String)(blockToWrapInSpan: (Map[String,String])=> T): T = {

    val tracer: Tracer = GlobalTracer.get
    val context: SpanContext = tracer.extract(
      Format.Builtin.TEXT_MAP,
      new TextMapAdapter(parentContext.asJava))

    val producerSpan: Span = tracer
      .buildSpan(spanName)
      .asChildOf(context)
      .start()

    val producerScope: Scope = tracer.activateSpan(producerSpan)
    val producerContext: SpanContext = tracer.activeSpan().context()
    val contextHeaders: JMap[String, String] = new JHashMap[String, String]()
    tracer.inject(
      producerContext,
      Format.Builtin.TEXT_MAP,
      new TextMapAdapter(contextHeaders))
    val res = blockToWrapInSpan(contextHeaders.asScala.toMap)
    producerSpan.finish()
    producerScope.close()
    res
  }
  
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
