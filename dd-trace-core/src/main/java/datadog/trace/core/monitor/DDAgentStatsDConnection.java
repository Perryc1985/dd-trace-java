package datadog.trace.core.monitor;

import static datadog.trace.api.ConfigDefaults.DEFAULT_DOGSTATSD_PORT;
import static datadog.trace.api.ConfigDefaults.DEFAULT_DOGSTATSD_SOCKET_PATH;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.timgroup.statsd.NoOpStatsDClient;
import com.timgroup.statsd.NonBlockingStatsDClient;
import com.timgroup.statsd.StatsDClientErrorHandler;
import datadog.trace.api.Config;
import datadog.trace.api.Platform;
import datadog.trace.util.AgentTaskScheduler;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class DDAgentStatsDConnection implements StatsDClientErrorHandler {
  private static final Logger log = LoggerFactory.getLogger(DDAgentStatsDConnection.class);

  private static final com.timgroup.statsd.StatsDClient NO_OP = new NoOpStatsDClient();

  private static final String UNIX_DOMAIN_SOCKET_PREFIX = "unix://";

  private volatile String host;
  private volatile Integer port;

  private final AtomicInteger clientCount = new AtomicInteger(0);
  private final AtomicInteger errorCount = new AtomicInteger(0);
  volatile com.timgroup.statsd.StatsDClient statsd = NO_OP;

  DDAgentStatsDConnection(final String host, final Integer port) {
    this.host = host;
    this.port = port;
  }

  @Override
  public void handle(final Exception e) {
    errorCount.incrementAndGet();
    log.error(
        "{} in StatsD client - {}", e.getClass().getSimpleName(), statsDAddress(host, port), e);
  }

  public void acquire() {
    if (clientCount.getAndIncrement() == 0) {
      scheduleConnect();
    }
  }

  public void release() {
    if (clientCount.decrementAndGet() == 0) {
      doClose();
    }
  }

  public int getErrorCount() {
    return errorCount.get();
  }

  private void scheduleConnect() {
    long remainingDelay =
        Config.get().getDogStatsDStartDelay()
            - MILLISECONDS.toSeconds(
                System.currentTimeMillis() - Config.get().getStartTimeMillis());

    if (remainingDelay > 0) {
      if (log.isDebugEnabled()) {
        log.debug(
            "Scheduling StatsD connection in {} seconds - {}",
            remainingDelay,
            statsDAddress(host, port));
      }
      AgentTaskScheduler.INSTANCE.scheduleWithJitter(
          ConnectTask.INSTANCE, this, remainingDelay, SECONDS);
    } else {
      doConnect();
    }
  }

  private void doConnect() {
    synchronized (this) {
      if (NO_OP == statsd && clientCount.get() > 0) {
        discoverConnectionSettings();
        if (log.isDebugEnabled()) {
          log.debug("Creating StatsD client - {}", statsDAddress(host, port));
        }
        // when using UDS, set "entity-id" to "none" to avoid having the DogStatsD
        // server add origin tags (see https://github.com/DataDog/jmxfetch/pull/264)
        String entityID = port == 0 ? "none" : null;
        try {
          statsd =
              new NonBlockingStatsDClient(
                  null, host, port, Integer.MAX_VALUE, null, this, entityID);
        } catch (final Exception e) {
          log.error("Unable to create StatsD client - {}", statsDAddress(host, port), e);
        }
      }
    }
  }

  @SuppressFBWarnings("DMI_HARDCODED_ABSOLUTE_FILENAME")
  private void discoverConnectionSettings() {
    if (null == host) {
      if (!Platform.isWindows() && new File(DEFAULT_DOGSTATSD_SOCKET_PATH).exists()) {
        log.info("Detected {}.  Using it to send StatsD data.", DEFAULT_DOGSTATSD_SOCKET_PATH);
        host = DEFAULT_DOGSTATSD_SOCKET_PATH;
        port = 0; // tells dogstatsd client to treat host as a socket path
      } else {
        host = Config.get().getAgentHost();
      }
    }
    if (host.startsWith(UNIX_DOMAIN_SOCKET_PREFIX)) {
      host = host.substring(UNIX_DOMAIN_SOCKET_PREFIX.length());
      port = 0; // tells dogstatsd client to treat host as a socket path
    }
    if (null == port) {
      port = DEFAULT_DOGSTATSD_PORT;
    }
  }

  private void doClose() {
    synchronized (this) {
      if (NO_OP != statsd && 0 == clientCount.get()) {
        if (log.isDebugEnabled()) {
          log.debug("Closing StatsD client - {}", statsDAddress(host, port));
        }
        try {
          statsd.close();
        } catch (final Exception e) {
          log.debug("Problem closing StatsD client - {}", statsDAddress(host, port), e);
        } finally {
          statsd = NO_OP;
        }
      }
    }
  }

  private static String statsDAddress(final String host, final Integer port) {
    return (null != host ? host : "<auto-detect>") + (null != port && port > 0 ? ":" + port : "");
  }

  private static final class ConnectTask
      implements AgentTaskScheduler.Task<DDAgentStatsDConnection> {
    public static final ConnectTask INSTANCE = new ConnectTask();

    @Override
    public void run(final DDAgentStatsDConnection target) {
      target.doConnect();
    }
  }
}
