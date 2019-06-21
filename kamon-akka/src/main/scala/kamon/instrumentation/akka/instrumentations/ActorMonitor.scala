package kamon.instrumentation.akka.instrumentations

import akka.actor.{ActorRef, ActorSystem}
import akka.dispatch.Envelope
import kamon.Kamon
import kamon.context.Context
import kamon.context.Storage.Scope
import kamon.instrumentation.akka.AkkaInstrumentation._
import kamon.instrumentation.akka.AkkaMetrics.{ActorGroupInstruments, ActorInstruments, RouterInstruments}
import kamon.instrumentation.akka.{AkkaInstrumentation, AkkaMetrics}
import kamon.trace.Span

/**
  * Exposes the callbacks to be executed when an instrumented ActorCell is performing its duties. These callbacks apply
  * to all Actors, regardless of them being "regular" actors or routees in a Router.
  */
trait ActorMonitor {

  /**
    * Captures the Context to be injected in Envelopes for targeting the monitored actor.
    */
  def captureEnvelopeContext(): Context

  /**
    * Captures the timestamp to be injected in Envelopes for targeting the monitored actor.
    */
  def captureEnvelopeTimestamp(): Long

  /**
    * Captures the timestamp at the instant when message processing starts. The value captured here will be passed to
    * the onMessageProcessingEnd method.
    */
  def captureProcessingStartTimestamp(): Long

  /**
    * Callback executed when message processing is about to start. Any value returned by this method will be used as the
    * last parameter of the onMessageProcessingEnd method.
    */
  def onMessageProcessingStart(context: Context, envelopeTimestamp: Long, envelope: Envelope): Any

  /**
    * Callback executed when message processing has ended.
    */
  def onMessageProcessingEnd(context: Context, envelopeTimestamp: Long, processingStartTimestamp: Long, stateFromStart: Any): Unit

  /**
    * Callback executed when an exception is thrown by the monitored Actor.
    */
  def onFailure(failure: Throwable): Unit

  /**
    * Callback executed when messages in the monitored Actor's mailbox need to be dropped.
    */
  def onDroppedMessages(count: Long): Unit

  /**
    * Callback executed when an Actor has been stopped and any state or resources related to it should be cleaned up.
    */
  def cleanup(): Unit

}

object ActorMonitor {

  /**
    * Creates an ActorMonitor based on all configuration settings on the Akka instrumentation.
    */
  def from(actorCell: Any, ref: ActorRef, parent: ActorRef, system: ActorSystem): ActorMonitor = {
    val cell = ActorCellInfo.from(actorCell, ref, parent, system)
    val settings = AkkaInstrumentation.settings()
    val isTraced = Kamon.filter(TraceActorFilterName).accept(cell.path)
    val startsTrace = settings.safeActorStartTraceFilter.accept(cell.path)
    val participatesInTracing = isTraced || startsTrace
    val autoGroupingPath = resolveAutoGroupingPath(cell.actorOrRouterClass, ref, parent)

    def traceWrap(monitor: ActorMonitor): ActorMonitor =
      if(participatesInTracing) new TracedMonitor(cell, startsTrace, monitor) else monitor

    val monitor = {

      if (cell.isRouter) {

        // A router cell is only used for Context propagation and most of the actual metrics are being
        // tracked in the routees' cells.
        new ContextPropagationOnly(cell, participatesInTracing, trackActiveActors = false)

      } else {

        val trackedFilter = if (cell.isRouter || cell.isRoutee) Kamon.filter(TrackRouterFilterName) else settings.safeActorTrackFilter
        val isTracked = !cell.isRootSupervisor && trackedFilter.accept(cell.path)
        val trackingGroups: Seq[ActorGroupInstruments] = if (cell.isRootSupervisor) List() else {
          val configuredMatchingGroups = AkkaInstrumentation.matchingActorGroups(cell.path)

          if (configuredMatchingGroups.isEmpty && !isTracked && settings.autoGrouping && !cell.isRouter && !cell.isRoutee) {
            if (!trackedFilter.excludes(cell.path) && Kamon.filter(TrackAutoGroupFilterName).accept(cell.path))
              List(AkkaMetrics.forGroup(autoGroupingPath, system.name))
            else
              List.empty

          } else {

            configuredMatchingGroups.map(groupName => {
              AkkaMetrics.forGroup(groupName, cell.systemName)
            })
          }
        }

        if (cell.isRoutee && isTracked)
          createRouteeMonitor(cell, trackingGroups)
        else
          createRegularActorMonitor(cell, isTracked, participatesInTracing, trackingGroups)
      }
    }

    traceWrap(monitor)
  }


  private def createRegularActorMonitor(cellInfo: ActorCellInfo, isTracked: Boolean, participatesInTracing: Boolean,
      groupMetrics: Seq[ActorGroupInstruments]): ActorMonitor = {

    if (isTracked || !groupMetrics.isEmpty) {
      val actorMetrics: Option[AkkaMetrics.ActorInstruments] = if (!isTracked) None else {
        Some(AkkaMetrics.forActor(
          cellInfo.path,
          cellInfo.systemName,
          cellInfo.dispatcherName,
          cellInfo.actorOrRouterClass.getName
        ))
      }

      // Pretty much all actors will end up here because of auto-grouping being enabled by default.
      new TrackedActor(actorMetrics, groupMetrics, cellInfo)

    } else {

      // If the actors is not doing any sort of tracking, it should at least do Context propagation.
      new ActorMonitor.ContextPropagationOnly(cellInfo, participatesInTracing, trackActiveActors = true)
    }
  }

  private def createRouteeMonitor(cellInfo: ActorCellInfo, groupMetrics: Seq[ActorGroupInstruments]): ActorMonitor = {
    val routerMetrics = AkkaMetrics.forRouter(
      cellInfo.path,
      cellInfo.systemName,
      cellInfo.dispatcherName,
      cellInfo.actorOrRouterClass.getName,
      cellInfo.routeeClass.map(_.getName).getOrElse("Unknown")
    )

    new TrackedRoutee(routerMetrics, groupMetrics, cellInfo)
  }

  private def resolveAutoGroupingPath(actorClass: Class[_], ref: ActorRef, parent: ActorRef): String = {
    val name = ref.path.name
    val elementCount = ref.path.elements.size

    val parentPath = if(parent.isInstanceOf[HasGroupPath]) parent.asInstanceOf[HasGroupPath].groupPath else ""
    val refGroupName = {
      if(elementCount == 1)
        if(name == "/") "" else name
      else
        ActorCellInfo.simpleClassName(actorClass)
    }

    val refGroupPath = if(parentPath.isEmpty) refGroupName else parentPath + "/" + refGroupName
    ref.asInstanceOf[HasGroupPath].setGroupPath(refGroupPath)
    refGroupPath
  }

  /**
    * Wraps another ActorMonitor implementation and provides tracing capabilities on top of it.
    */
  class TracedMonitor(cellInfo: ActorCellInfo, startsTrace: Boolean, monitor: ActorMonitor) extends ActorMonitor {
    private val _actorClassName = cellInfo.actorOrRouterClass.getName
    private val _actorSimpleClassName = ActorCellInfo.simpleClassName(cellInfo.actorOrRouterClass)

    override def captureEnvelopeTimestamp(): Long =
      monitor.captureEnvelopeTimestamp()

    override def captureEnvelopeContext(): Context =
      monitor.captureEnvelopeContext()

    override def captureProcessingStartTimestamp(): Long =
      monitor.captureProcessingStartTimestamp()

    override def onMessageProcessingStart(context: Context, envelopeTimestamp: Long, envelope: Envelope): Any = {
      val incomingContext = context
      if(incomingContext.get(Span.Key).isEmpty && !startsTrace) {
        // We will not generate a Span unless message processing is happening inside of a trace.
        new SpanAndMonitorState(null, monitor.onMessageProcessingStart(context, envelopeTimestamp, envelope))

      } else {
        val messageSpan = buildSpan(cellInfo, context, envelopeTimestamp, envelope).start()
        val contextWithMessageSpan = incomingContext.withKey(Span.Key, messageSpan)
        new SpanAndMonitorState(messageSpan, monitor.onMessageProcessingStart(contextWithMessageSpan, envelopeTimestamp, envelope))
      }
    }

    override def onMessageProcessingEnd(context: Context, envelopeTimestamp: Long, processingStartTimestamp: Long, stateFromStart: Any): Unit = {
      val spanAndMonitor = stateFromStart.asInstanceOf[SpanAndMonitorState]
      monitor.onMessageProcessingEnd(context, envelopeTimestamp, processingStartTimestamp, spanAndMonitor.wrappedMonitorState)
      if (spanAndMonitor.span != null)
        spanAndMonitor.span.asInstanceOf[Span].finish()
    }

    override def onFailure(failure: Throwable): Unit =
      monitor.onFailure(failure)

    override def onDroppedMessages(count: Long): Unit =
      monitor.onDroppedMessages(count)

    override def cleanup(): Unit =
      monitor.cleanup()

    private def buildSpan(cellInfo: ActorCellInfo, context: Context, envelopeTimestamp: Long, envelope: Envelope): Span.Delayed = {
      val messageClass = ActorCellInfo.simpleClassName(envelope.message.getClass)
      val parentSpan = context.get(Span.Key)

      Kamon.internalSpanBuilder(operationName(messageClass, envelope.sender), "akka.actor")
        .asChildOf(parentSpan)
        .doNotTrackMetrics()
        .tag("akka.system", cellInfo.systemName)
        .tag("akka.actor.path", cellInfo.path)
        .tag("akka.actor.class", _actorClassName)
        .tag("akka.actor.message-class", messageClass)
        .delay(Kamon.clock().toInstant(envelopeTimestamp))
    }

    private def operationName(messageClass: String, sender: ActorRef): String = {
      val operationType = if(AkkaPrivateAccess.isPromiseActorRef(sender)) "ask(" else "tell("

      StringBuilder.newBuilder
        .append(operationType)
        .append(_actorSimpleClassName)
        .append(", ")
        .append(messageClass)
        .append(")")
        .result()
    }

    private class SpanAndMonitorState(val span: Span, val wrappedMonitorState: Any)
  }

  /**
    * Basic implementation that only provides Context propagation across Actors.
    */
  class ContextPropagationOnly(cellInfo: ActorCellInfo, participatesInTracing: Boolean, trackActiveActors: Boolean) extends ActorMonitor {
    private val _systemMetrics = AkkaMetrics.forSystem(cellInfo.systemName)

    if(trackActiveActors)
      _systemMetrics.activeActors.increment()

    override def captureEnvelopeTimestamp(): Long =
      if(participatesInTracing) Kamon.clock().nanos() else 0L

    override def captureEnvelopeContext(): Context =
      Kamon.currentContext()

    override def captureProcessingStartTimestamp(): Long =
      if(participatesInTracing) Kamon.clock().nanos() else 0L

    override def onMessageProcessingStart(context: Context, envelopeTimestamp: Long, envelope: Envelope): Any = {
      _systemMetrics.processedMessagesByNonTracked.increment()
      Kamon.store(context)
    }

    override def onMessageProcessingEnd(context: Context, envelopeTimestamp: Long, processingStartTimestamp: Long, stateFromStart: Any): Unit =
      stateFromStart.asInstanceOf[Scope].close()

    def onFailure(failure: Throwable): Unit = {}

    def onDroppedMessages(count: Long): Unit = {}

    def cleanup(): Unit = {
      if(trackActiveActors)
        _systemMetrics.activeActors.decrement()
    }
  }

  /**
    * ActorMonitor that tracks Actor and/or Group metrics and performs Context propagation.
    */
  class TrackedActor(actorMetrics: Option[ActorInstruments], groupMetrics: Seq[ActorGroupInstruments], cellInfo: ActorCellInfo)
    extends GroupMetricsTrackingActor(groupMetrics, cellInfo) {

    private val _processedMessagesCounter = AkkaMetrics.forSystem(cellInfo.systemName).processedMessagesByTracked

    override def captureEnvelopeTimestamp(): Long =
      super.captureEnvelopeTimestamp()

    override def captureEnvelopeContext(): Context = {
      actorMetrics.foreach { am => am.mailboxSize.increment() }
      super.captureEnvelopeContext()
    }

    override def onMessageProcessingStart(context: Context, envelopeTimestamp: Long, envelope: Envelope): Any = {
      _processedMessagesCounter.increment()
      Kamon.store(context)
    }

    override def onMessageProcessingEnd(context: Context, envelopeTimestamp: Long, processingStartTimestamp: Long, stateFromStart: Any): Unit = {
      try stateFromStart.asInstanceOf[Scope].close() finally {
        val timestampAfterProcessing = clock.nanos()
        val timeInMailbox = processingStartTimestamp - envelopeTimestamp
        val processingTime = timestampAfterProcessing - processingStartTimestamp

        actorMetrics.foreach { am =>
          am.processingTime.record(processingTime)
          am.timeInMailbox.record(timeInMailbox)
          am.mailboxSize.decrement()
        }
        recordGroupMetrics(processingTime, timeInMailbox)
      }
    }

    override def onFailure(failure: Throwable): Unit = {
      actorMetrics.foreach { am => am.errors.increment() }
      super.onFailure(failure: Throwable)
    }

    override def cleanup(): Unit = {
      super.cleanup()
      actorMetrics.foreach(_.remove())
    }
  }

  /**
    * ActorMonitor that tracks the activity of a Routee and possibly Actor Group metrics.
    */
  class TrackedRoutee(routerMetrics: RouterInstruments, groupMetrics: Seq[ActorGroupInstruments], cellInfo: ActorCellInfo)
    extends GroupMetricsTrackingActor(groupMetrics, cellInfo) {

    routerMetrics.members.increment()
    private val processedMessagesCounter = AkkaMetrics.forSystem(cellInfo.systemName).processedMessagesByTracked

    override def captureEnvelopeContext(): Context = {
      routerMetrics.pendingMessages.increment()
      super.captureEnvelopeContext()
    }

    override def onMessageProcessingStart(context: Context, envelopeTimestamp: Long, envelope: Envelope): Any = {
      processedMessagesCounter.increment()
      Kamon.store(context)
    }

    override def onMessageProcessingEnd(context: Context, envelopeTimestamp: Long, processingStartTimestamp: Long, stateFromStart: Any): Unit = {
      try stateFromStart.asInstanceOf[Scope].close() finally {
        val timestampAfterProcessing = Kamon.clock().nanos()
        val timeInMailbox = processingStartTimestamp - envelopeTimestamp
        val processingTime = timestampAfterProcessing - processingStartTimestamp

        routerMetrics.processingTime.record(processingTime)
        routerMetrics.timeInMailbox.record(timeInMailbox)
        routerMetrics.pendingMessages.decrement()
        recordGroupMetrics(processingTime, timeInMailbox)
      }
    }

    override def onFailure(failure: Throwable): Unit = {
      routerMetrics.errors.increment()
      super.onFailure(failure)
    }

    override def onDroppedMessages(count: Long): Unit = {
      super.onDroppedMessages(count)
      routerMetrics.pendingMessages.decrement(count)
    }

    override def cleanup(): Unit = {
      super.cleanup()
      routerMetrics.members.decrement()
    }
  }

  /**
    * Base actor tracking class that brings support for Actor Group metrics.
    */
  abstract class GroupMetricsTrackingActor(groupMetrics: Seq[ActorGroupInstruments], cellInfo: ActorCellInfo) extends ActorMonitor {
    private val _shouldTrackActiveActors = !cellInfo.isTemporary
    protected val clock = Kamon.clock()
    protected val systemMetrics = AkkaMetrics.forSystem(cellInfo.systemName)

    // We might need to create an instance when a RepointableActorRef creates an UnstartedCell and in that case,
    // we don't want to increment the number of members in the groups.
    if (_shouldTrackActiveActors) {
      systemMetrics.activeActors.increment()

      groupMetrics.foreach { gm =>
        gm.members.increment()
      }
    }

    override def captureEnvelopeTimestamp(): Long =
      clock.nanos()

    override def captureEnvelopeContext(): Context = {
      groupMetrics.foreach { gm =>
        gm.pendingMessages.increment()
      }

      Kamon.currentContext()
    }

    override def captureProcessingStartTimestamp(): Long =
      clock.nanos()

    override def onFailure(failure: Throwable): Unit = {
      groupMetrics.foreach { gm =>
        gm.errors.increment()
      }
    }

    override def onDroppedMessages(count: Long): Unit = {
      groupMetrics.foreach { gm =>
        gm.pendingMessages.decrement(count)
      }
    }

    protected def recordGroupMetrics(processingTime: Long, timeInMailbox: Long): Unit = {
      groupMetrics.foreach { gm =>
        gm.processingTime.record(processingTime)
        gm.timeInMailbox.record(timeInMailbox)
        gm.pendingMessages.decrement()
      }
    }

    def cleanup(): Unit = {

      // Similarly to the code in the constructor, we only decrement when we are not in a temporary cell.
      if (_shouldTrackActiveActors) {
        systemMetrics.activeActors.decrement()

        groupMetrics.foreach { gm =>
          gm.members.decrement()
        }
      }
    }
  }
}
