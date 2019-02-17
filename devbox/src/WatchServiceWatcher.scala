package devbox

import java.nio.file._
import java.io.IOException
import java.nio.file.ClosedWatchServiceException
import java.util.concurrent.atomic.AtomicBoolean
import java.nio.file.StandardWatchEventKinds.{ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY, OVERFLOW}
import java.util.concurrent.TimeUnit

import com.sun.nio.file.SensitivityWatchEventModifier

import scala.collection.mutable
import collection.JavaConverters._

class WatchServiceWatcher(root: os.Path,
                          onEvent: Array[String] => Unit,
                          ignorePaths: (os.Path, Boolean) => Boolean,
                          settleDelay: Double) extends AutoCloseable{
  val nioWatchService = FileSystems.getDefault.newWatchService()
  val currentlyWatchedPaths = mutable.Map.empty[os.Path, WatchKey]
  val bufferedEvents = mutable.Buffer.empty[os.Path]
  val isRunning = new AtomicBoolean(false)


  isRunning.set(true)
  walkTreeAndSetWatches()

  def processWatchKey(watchKey: WatchKey) = {
    val p = os.Path(watchKey.watchable().asInstanceOf[java.nio.file.Path], os.pwd)
    bufferedEvents.append(p)
    val events = watchKey.pollEvents()
    val possibleCreations = events.asScala.filter(_.kind() != ENTRY_DELETE)
    if (possibleCreations.nonEmpty){
      bufferedEvents.appendAll(
        possibleCreations.map(e => os.Path(e.context().asInstanceOf[java.nio.file.Path], p))
      )
    }
    watchKey.reset()
  }

  def watchEventsOccurred(): Unit = {
    for(p <- currentlyWatchedPaths.keySet if !os.exists(p, followLinks = false)){
      currentlyWatchedPaths.remove(p).foreach(_.cancel())
    }
    walkTreeAndSetWatches()
  }

  def walkTreeAndSetWatches(): Unit = {

    try {
      for {
        (p, attrs) <- os.walk.stream.attrs(root, skip = (p, attr) => ignorePaths(p, attr.isDir), includeTarget = true)
        if attrs.isDir && !currentlyWatchedPaths.contains(p)
      } {
        pprint.log(p)
        try currentlyWatchedPaths.put(
          p,
          p.toNIO.register(
            nioWatchService,
            Array[WatchEvent.Kind[_]](ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY, OVERFLOW),
            SensitivityWatchEventModifier.HIGH
          )
        ) catch {
          case e: IOException => println("IO Error when registering watch", e)
        }
      }
    }catch {
      case e: IOException => println("IO error when registering watches")
    }
  }

  def start(): Unit = {
    while (isRunning.get()) try {
      nioWatchService.poll(100, TimeUnit.MILLISECONDS) match{
        case null => //continue
        case watchKey0 =>
          processWatchKey(watchKey0)
          while({
            nioWatchService.poll() match{
              case null => false
              case watchKey =>
                processWatchKey(watchKey)
                true
            }
          })()

          debouncedTriggerListener()
          watchEventsOccurred()
      }

    } catch {
      case e: InterruptedException =>
        println("Interrupted, exiting", e)
        isRunning.set(false)
      case e: ClosedWatchServiceException =>
        println("Watcher closed, exiting", e)
        isRunning.set(false)
    }
  }

  def close(): Unit = {
    try {
      isRunning.set(false)
      nioWatchService.close()
    } catch {
      case e: IOException => println("Error closing watcher", e)
    }
  }

  private def debouncedTriggerListener(): Unit = {
    onEvent(
      bufferedEvents.iterator
        .map{p => if (os.isDir(p, followLinks = false)) p else p / os.up}
        .map(_.toString)
        .toArray
    )
    bufferedEvents.clear()
  }
}
