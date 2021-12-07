package com.raquo.airstream.core

import com.raquo.airstream.combine.generated._
import com.raquo.airstream.combine.{CombineEventStreamN, MergeEventStream}
import com.raquo.airstream.core.Source.EventSource
import com.raquo.airstream.custom.CustomSource._
import com.raquo.airstream.custom.{CustomSource, CustomStreamSource}
import com.raquo.airstream.debug.{DebuggableEventStream, Debugger, DebuggerEventStream}
import com.raquo.airstream.distinct.DistinctEventStream
import com.raquo.airstream.eventbus.EventBus
import com.raquo.airstream.misc._
import com.raquo.airstream.misc.generated._
import com.raquo.airstream.split.{SplittableEventStream, SplittableOneEventStream}
import com.raquo.airstream.timing._

import scala.annotation.unused
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js
import scala.scalajs.js.JSConverters._
import scala.util.{Failure, Success, Try}

trait EventStream[+A] extends Observable[A] with BaseObservable[EventStream, A] with EventSource[A] {

  override def map[B](project: A => B): EventStream[B] = {
    new MapEventStream(this, project, recover = None)
  }

  /** @param passes Note: guarded against exceptions */
  def filter(passes: A => Boolean): EventStream[A] = {
    new FilterEventStream(parent = this, passes)
  }

  def filterNot(predicate: A => Boolean): EventStream[A] = filter(!predicate(_))

  /** Apply `pf` to event and emit the resulting value, or emit nothing if `pf` is not defined for that event.
    *
    * @param pf Note: guarded against exceptions
    */
  def collect[B](pf: PartialFunction[A, B]): EventStream[B] = collectOpt(pf.lift)

  /** Emit `x` if parent stream emits `Some(x)`, nothing otherwise */
  def collectSome[B](implicit ev: A <:< Option[B]): EventStream[B] = collectOpt(ev(_))

  /** Apply `fn` to parent stream event, and emit resulting x if it returns Some(x)
    *
    * @param fn Note: guarded against exceptions
    */
  def collectOpt[B](fn: A => Option[B]): EventStream[B] = {
    new CollectEventStream(parent = this, fn)
  }

  /** @param ms milliseconds of delay */
  def delay(ms: Int = 0): EventStream[A] = {
    new DelayEventStream(parent = this, ms)
  }

  /** Make a stream that emits this stream's values but waits for `after` stream to emit first in a given transaction.
    * You can use this for Signals too with `Signal.composeChanges` (see docs for more details)
    */
  def delaySync(after: EventStream[_]): EventStream[A] = {
    new SyncDelayEventStream[A](parent = this, after = after)
  }

  /** See docs for [[ThrottleEventStream]] */
  def throttle(ms: Int, leading: Boolean = true): EventStream[A] = {
    new ThrottleEventStream(parent = this, intervalMs = ms, leading = leading)
  }

  /** See docs for [[DebounceEventStream]] */
  def debounce(ms: Int): EventStream[A] = {
    new DebounceEventStream(parent = this, ms)
  }

  /** Drop (skip) the first `numEvents` events from this stream. Note: errors are NOT dropped.
    *
    * @param resetOnStop  Reset the count if the stream stops
    */
  def drop(numEvents: Int, resetOnStop: Boolean = false): EventStream[A] = {
    var numDropped = 0
    new DropEventStream[A](
      parent = this,
      dropWhile = _ => {
        val shouldDrop = numDropped < numEvents
        if (shouldDrop) {
          numDropped += 1
        }
        shouldDrop
      },
      reset = () => {
        numDropped = 0
      },
      resetOnStop
    )
  }

  /** Drop (skip) events from this stream as long as they pass the test (as soon as they stop passing, stop dropping)
    * Note: errors are NOT dropped.
    *
    * @param passes       Note: MUST NOT THROW!
    * @param resetOnStop  Forget everything and start dropping again if the stream stops
    */
  def dropWhile(passes: A => Boolean, resetOnStop: Boolean = false): EventStream[A] = {
    new DropEventStream[A](
      parent = this,
      dropWhile = ev => passes(ev),
      reset = () => (),
      resetOnStop
    )
  }

  /** Drop (skip) events from this stream as long as they do NOT pass the test (as soon as they start passing, stop dropping)
    * Note: errors are NOT dropped.
    *
    * @param passes       Note: MUST NOT THROW!
    * @param resetOnStop  Forget everything and start dropping again if the stream stops
    */
  def dropUntil(passes: A => Boolean, resetOnStop: Boolean = false): EventStream[A] = {
    new DropEventStream[A](
      parent = this,
      dropWhile = ev => !passes(ev),
      () => (),
      resetOnStop
    )
  }

  /** Take the first `numEvents` events from this stream, ignore the rest.
    * Note: As long as events are being taken, ALL errors are also taken
    *
    * @param resetOnStop  Reset the count if the stream stops
    */
  def take(numEvents: Int, resetOnStop: Boolean = false): EventStream[A] = {
    var numTaken = 0
    new TakeEventStream[A](
      parent = this,
      takeWhile = _ => {
        val shouldTake = numTaken < numEvents
        if (shouldTake) {
          numTaken += 1
        }
        shouldTake
      },
      reset = () => {
        numTaken = 0
      },
      resetOnStop
    )
  }

  /** Imitate parent stream as long as events pass the test; stop emitting after that.
    *
    * @param passes       Note: MUST NOT THROW!
    * @param resetOnStop  Forget everything and start dropping again if the stream stops
    */
  def takeWhile(passes: A => Boolean, resetOnStop: Boolean = false): EventStream[A] = {
    new TakeEventStream[A](
      parent = this,
      takeWhile = ev => passes(ev),
      reset = () => (),
      resetOnStop
    )
  }

  /** Imitate parent stream as long as events to NOT pass the test; stop emitting after that.
    *
    * @param passes       Note: MUST NOT THROW!
    * @param resetOnStop  Forget everything and start dropping again if the stream stops
    */
  def takeUntil(passes: A => Boolean, resetOnStop: Boolean = false): EventStream[A] = {
    new TakeEventStream[A](
      parent = this,
      takeWhile = ev => !passes(ev),
      () => (),
      resetOnStop
    )
  }


  // @TODO[API] Should we introduce some kind of FoldError() wrapper?
  /** A signal that emits the accumulated value every time that the parent stream emits.
    *
    * @param fn Note: guarded against exceptions
    */
  def foldLeft[B](initial: B)(fn: (B, A) => B): Signal[B] = {
    foldLeftRecover(
      Success(initial)
    )(
      (currentValue, nextParentValue) => Try(fn(currentValue.get, nextParentValue.get))
    )
  }

  /** A signal that emits the accumulated value every time that the parent stream emits.
    *
    * @param fn Note: Must not throw!
    */
  def foldLeftRecover[B](initial: Try[B])(fn: (Try[B], Try[A]) => Try[B]): Signal[B] = {
    new FoldLeftSignal(parent = this, () => initial, fn)
  }

  /** @param cacheInitialValue if false, signal's initial value will be re-evaluated on every
    *                          restart (so long as the parent stream does not emit any values)
    */
  @inline def startWith[B >: A](initial: => B, cacheInitialValue: Boolean = false): Signal[B] = {
    toSignal(initial, cacheInitialValue)
  }

  /** @param cacheInitialValue if false, signal's initial value will be re-evaluated on every
    *                          restart (so long as the parent stream does not emit any values)
    */
  @inline def startWithTry[B >: A](initial: => Try[B], cacheInitialValue: Boolean = false): Signal[B] = {
    toSignalWithTry(initial, cacheInitialValue)
  }

  @inline def startWithNone: Signal[Option[A]] = toWeakSignal

  /** @param cacheInitialValue if false, signal's initial value will be re-evaluated on every
    *                          restart (so long as the parent stream does not emit any values)
    */
  def toSignal[B >: A](initial: => B, cacheInitialValue: Boolean = false): Signal[B] = {
    toSignalWithTry(Success(initial), cacheInitialValue)
  }

  /** @param cacheInitialValue if false, signal's initial value will be re-evaluated on every
    *                          restart (so long as the parent stream does not emit any values)
    */
  def toSignalWithTry[B >: A](initial: => Try[B], cacheInitialValue: Boolean = false): Signal[B] = {
    new SignalFromEventStream(this, initial, cacheInitialValue)
  }

  def compose[B](operator: EventStream[A] => EventStream[B]): EventStream[B] = {
    operator(this)
  }

  /** Distinct all values (both events and errors) using a comparison function
    *
    * @param fn (prev, next) => isSame
    */
  override def distinctTry(fn: (Try[A], Try[A]) => Boolean): EventStream[A] = {
    new DistinctEventStream[A](parent = this, fn, resetOnStop = false)
  }

  /** See docs for [[MapEventStream]]
    *
    * @param pf Note: guarded against exceptions
    */
  override def recover[B >: A](pf: PartialFunction[Throwable, Option[B]]): EventStream[B] = {
    new MapEventStream[A, B](
      parent = this,
      project = identity,
      recover = Some(pf)
    )
  }

  override def recoverToTry: EventStream[Try[A]] = map(Try(_)).recover[Try[A]] { case err => Some(Failure(err)) }

  /** See also [[debug]] convenience method in [[BaseObservable]] */
  override def debugWith(debugger: Debugger[A]): EventStream[A] = {
    new DebuggerEventStream[A](this, debugger)
  }

  /** This is used in Signal-s. It's a no-op for Streams. */
  override protected def onAddedExternalObserver(observer: Observer[A]): Unit = ()

  override def toObservable: EventStream[A] = this

}

object EventStream {

  /** Event stream that never emits anything */
  val empty: EventStream[Nothing] = {
    fromCustomSource[Nothing](
      shouldStart = _ => false,
      start = (_, _, _, _) => (),
      stop = _ => ()
    )
  }

  /** @param emitOnce if true, the event will be emitted at most one time.
    *                 If false, the event will be emitted every time the stream is started. */
  def fromSeq[A](events: Seq[A], emitOnce: Boolean = false): EventStream[A] = {
    fromCustomSource[A](
      shouldStart = startIndex => if (emitOnce) startIndex == 1 else true,
      start = (fireEvent, _, _, _) => events.foreach(fireEvent),
      stop = _ => ()
    )
  }

  /** @param emitOnce if true, the event will be emitted at most one time.
    *                 If false, the event will be emitted every time the stream is started. */
  def fromValue[A](event: A, emitOnce: Boolean = false): EventStream[A] = {
    fromCustomSource[A](
      shouldStart = startIndex => if (emitOnce) startIndex == 1 else true,
      start = (fireEvent, _, _, _) => fireEvent(event),
      stop = _ => ()
    )
  }

  /** @param emitOnce if true, the event will be emitted at most one time.
    *                 If false, the event will be emitted every time the stream is started. */
  def fromTry[A](value: Try[A], emitOnce: Boolean = false): EventStream[A] = {
    fromCustomSource[A](
      shouldStart = startIndex => if (emitOnce) startIndex == 1 else true,
      start = (fireEvent, fireError, _, _) => value.fold(fireError, fireEvent),
      stop = _ => ()
    )
  }

  def fromFuture[A](future: Future[A], emitOnce: Boolean = false): EventStream[A] = {
    fromJsPromise(future.toJSPromise, emitOnce)
  }

  def fromJsPromise[A](promise: js.Promise[A], emitOnce: Boolean = false): EventStream[A] = {
    new JsPromiseEventStream[A](promise, emitOnce)
  }

  /** Easy helper for custom events. See [[CustomStreamSource]] for docs.
    *
    * @param stop MUST NOT THROW!
    */
  def fromCustomSource[A](
    shouldStart: StartIndex => Boolean = _ => true,
    start: (FireValue[A], FireError, GetStartIndex, GetIsStarted) => Unit,
    stop: StartIndex => Unit
  ): EventStream[A] = {
    CustomStreamSource[A] { (fireValue, fireError, getStartIndex, getIsStarted) =>
      CustomSource.Config(
        onStart = () => start(fireValue, fireError, getStartIndex, getIsStarted),
        onStop = () => stop(getStartIndex())
      ).when {
        () => shouldStart(getStartIndex())
      }
    }
  }

  /** Create a stream and a callback that, when fired, makes that stream emit. */
  def withCallback[A]: (EventStream[A], A => Unit) = {
    val bus = new EventBus[A]
    (bus.events, bus.writer.onNext)
  }

  /** Create a stream and a JS callback that, when fired, makes that stream emit. */
  def withJsCallback[A]: (EventStream[A], js.Function1[A, Unit]) = {
    val bus = new EventBus[A]
    (bus.events, bus.writer.onNext)
  }

  /** Create a stream and an observer that, when receiving an event or an error, makes that stream emit. */
  def withObserver[A]: (EventStream[A], Observer[A]) = {
    val bus = new EventBus[A]
    (bus.events, bus.writer)
  }

  def periodic(
    intervalMs: Int,
    resetOnStop: Boolean = false
  ): PeriodicEventStream[Int] = {
    new PeriodicEventStream[Int](
      initial = 0,
      next = eventNumber => Some((eventNumber + 1, intervalMs)),
      resetOnStop = resetOnStop
    )
  }

  def sequence[A](streams: Seq[EventStream[A]]): EventStream[Seq[A]] = {
    new CombineEventStreamN[A, Seq[A]](streams, identity)
  }

  @inline def combineSeq[A](streams: Seq[EventStream[A]]): EventStream[Seq[A]] = sequence(streams)

  def merge[A](streams: EventStream[A]*): EventStream[A] = {
    new MergeEventStream[A](streams)
  }

  @inline def mergeSeq[A](streams: Seq[EventStream[A]]): EventStream[A] = {
    merge(streams: _*) // @TODO[Performance] Does _* introduce any overhead in Scala.js?
  }

  /** Provides methods on EventStream companion object: combine, combineWith */
  implicit def toEventStreamCompanionCombineSyntax(@unused s: EventStream.type): StaticEventStreamCombineOps.type = StaticEventStreamCombineOps

  /** Provides methods on EventStream: combine, combineWith, withCurrentValueOf, sample */
  implicit def toCombinableStream[A](stream: EventStream[A]): CombinableEventStream[A] = new CombinableEventStream(stream)

  /** Provides methods on EventStream: split, splitOneIntoSignals */
  implicit def toSplittableStream[M[_], Input](stream: EventStream[M[Input]]): SplittableEventStream[M, Input] = new SplittableEventStream(stream)

  /** Provides methods on EventStream: splitOne, splitOneIntoSignals */
  implicit def toSplittableOneStream[A](stream: EventStream[A]): SplittableOneEventStream[A] = new SplittableOneEventStream(stream)

  /** Provides debug* methods on EventStream: debugSpy, debugLogEvents, debugBreakErrors, etc. */
  implicit def toDebuggableStream[A](stream: EventStream[A]): DebuggableEventStream[A] = new DebuggableEventStream[A](stream)

  // toTupleStreamN conversions provide mapN and filterN methods on Signals of tuples

  implicit def toTupleStream2[T1, T2](stream: EventStream[(T1, T2)]): TupleEventStream2[T1, T2] = new TupleEventStream2(stream)

  implicit def toTupleStream3[T1, T2, T3](stream: EventStream[(T1, T2, T3)]): TupleEventStream3[T1, T2, T3] = new TupleEventStream3(stream)

  implicit def toTupleStream4[T1, T2, T3, T4](stream: EventStream[(T1, T2, T3, T4)]): TupleEventStream4[T1, T2, T3, T4] = new TupleEventStream4(stream)

  implicit def toTupleStream5[T1, T2, T3, T4, T5](stream: EventStream[(T1, T2, T3, T4, T5)]): TupleEventStream5[T1, T2, T3, T4, T5] = new TupleEventStream5(stream)

  implicit def toTupleStream6[T1, T2, T3, T4, T5, T6](stream: EventStream[(T1, T2, T3, T4, T5, T6)]): TupleEventStream6[T1, T2, T3, T4, T5, T6] = new TupleEventStream6(stream)

  implicit def toTupleStream7[T1, T2, T3, T4, T5, T6, T7](stream: EventStream[(T1, T2, T3, T4, T5, T6, T7)]): TupleEventStream7[T1, T2, T3, T4, T5, T6, T7] = new TupleEventStream7(stream)

  implicit def toTupleStream8[T1, T2, T3, T4, T5, T6, T7, T8](stream: EventStream[(T1, T2, T3, T4, T5, T6, T7, T8)]): TupleEventStream8[T1, T2, T3, T4, T5, T6, T7, T8] = new TupleEventStream8(stream)

  implicit def toTupleStream9[T1, T2, T3, T4, T5, T6, T7, T8, T9](stream: EventStream[(T1, T2, T3, T4, T5, T6, T7, T8, T9)]): TupleEventStream9[T1, T2, T3, T4, T5, T6, T7, T8, T9] = new TupleEventStream9(stream)
}
