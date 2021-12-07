package com.raquo.airstream.timing

import com.raquo.airstream.core.{Transaction, WritableEventStream}

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue // #TODO #nc remove this in 15.0.0
import scala.concurrent.Future

// #nc[Remove]
/** This stream emits a value that the future resolves with.
  *
  * This stream will not emit any events to subscribers added after the
  * future was resolved. Use [[FutureSignal]] if that is desired.
  *
  * @param future Note: guarded against failures
  */
class FutureEventStream[A](future: Future[A]) extends WritableEventStream[A] {

  override protected val topoRank: Int = 1

  if (!future.isCompleted) {
    // @TODO[API] Do we need "isStarted" filter on these? Doesn't seem to affect anything for now...
    future.onComplete(_.fold(
      nextError => {
        //println(s"> init trx from FutureEventStream.init($nextError)")
        new Transaction(fireError(nextError, _))
      },
      nextValue => {
        //println(s"> init trx from FutureEventStream.init($nextValue)")
        new Transaction(fireValue(nextValue, _))
      }
    ))
  }

  override protected def onWillStart(): Unit = () // noop
}
