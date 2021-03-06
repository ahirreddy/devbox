package devbox
import java.io._
import java.nio.file.attribute.PosixFilePermission

import devbox.common._
import devbox.common.Cli.{Arg, showArg}
import Cli.pathScoptRead
object DevboxMain {
  case class Config(repo: List[String] = Nil,
                    stride: Int = 1,
                    debounceMillis: Int = 150,
                    help: Boolean = false,
                    toast: Boolean = false,
                    logFile: Option[os.Path] = None,
                    ignoreStrategy: String = "",
                    readOnlyRemote: String = null,
                    healthCheckInterval: Int = 0,
                    retryInterval: Int = 0)

  def main(args: Array[String]): Unit = {

    val signature = Seq(
      Arg[Config, String](
        "repo", None,
        "Which repository to sync",
        (c, v) => c.copy(repo = v :: c.repo)
      ),
      Arg[Config, Int](
        "debounce", None,
        "How many milliseconds to wait for the filesystem to stabilize before syncing",
        (c, v) => c.copy(stride = v)
      ),
      Arg[Config, Unit](
        "help", None,
        "Print this message",
        (c, v) => c.copy(help = true)
      ),
      Arg[Config, Unit](
        "toast", None,
        "Enable Mac-OS toast notifications",
        (c, v) => c.copy(toast = true)
      ),
      Arg[Config, os.Path](
        "log-file", None,
        "Redirect logging output to a file",
        (c, v) => c.copy(logFile = Some(v))
      ),
      Arg[Config, String](
        "ignore-strategy", None,
        "",
        (c, v) => c.copy(ignoreStrategy = v)
      ),
      Arg[Config, String](
        "readonly-remote", None,
        "",
        (c, v) => c.copy(readOnlyRemote = v)
      ),
      Arg[Config, Int](
        "health-check-interval", None,
        "Interval between health check, health check should succeed before the next health check (in seconds)",
        (c, v) => c.copy(healthCheckInterval = v)
      )
    )


    Cli.groupArgs(args.toList, signature, Config()) match{
      case Left(msg) =>
        System.err.println(msg)
        System.exit(1)
      case Right((config, remaining)) =>
        if (config.help){
          val leftMargin = signature.map(showArg(_).length).max + 2
          System.out.println(Cli.formatBlock(signature, leftMargin).mkString("\n"))
        }else {
          val skipper = Skipper.fromString(config.ignoreStrategy)
          val agent = new java.lang.ProcessBuilder()
              .command(remaining:_*)
              .start()
          Util.autoclose(new Syncer(
            new ReliableAgent(remaining),
            for(s <- config.repo)
            yield s.split(':') match{
              case Array(src) => (os.Path(src, os.pwd), os.rel / os.Path(src, os.pwd).last)
              case Array(src, dest) => (os.Path(src, os.pwd), os.rel / dest.split('/'))
            },
            skipper,
            config.debounceMillis,
            () => (),
            Logger.File(config.logFile.get, config.toast),
            if (config.readOnlyRemote == null) (_, sig) => sig
            else {
              val (regexStr, negate) =
                if (config.readOnlyRemote.head != '!') (config.readOnlyRemote, false)
                else (config.readOnlyRemote.drop(1), true)
              val regex = com.google.re2j.Pattern.compile(regexStr)

              {
                case (path, Signature.File(perms, blockHashes, size))
                  if regex.matches(path.toString) ^ negate =>
                  Signature.File(
                    perms
                      - PosixFilePermission.GROUP_WRITE
                      - PosixFilePermission.OTHERS_WRITE
                      - PosixFilePermission.OWNER_WRITE,
                    blockHashes,
                    size
                  )
                case (path, sig) => sig
              }
            },
            healthCheckInterval = config.healthCheckInterval
          )){syncer =>
            syncer.start()
            Thread.sleep(Long.MaxValue)
          }
        }
        System.exit(0)
    }

  }
}
