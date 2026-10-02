package net.codefinch.jev.benchmarks.load;

import com.sun.management.OperatingSystemMXBean;
import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;

/** Combined client/server/driver process observations; never reported as client-only allocation. */
public record ProcessResources(
    long cpuNanos, long heapUsedBytes, long committedVirtualBytes, long gcCount, long gcMillis) {
  /** Captures bounded counters from standard JVM management beans. */
  public static ProcessResources capture() {
    var os = ManagementFactory.getOperatingSystemMXBean();
    long cpu = os instanceof OperatingSystemMXBean bean ? bean.getProcessCpuTime() : -1;
    long memory =
        os instanceof OperatingSystemMXBean bean ? bean.getCommittedVirtualMemorySize() : -1;
    long count = 0;
    long millis = 0;
    for (var gc : ManagementFactory.getGarbageCollectorMXBeans()) {
      count += Math.max(0, gc.getCollectionCount());
      millis += Math.max(0, gc.getCollectionTime());
    }
    return new ProcessResources(
        cpu,
        ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),
        memory,
        count,
        millis);
  }

  /** Measurement plus result-drain deltas; heap snapshots are not allocated bytes. */
  public Map<String, Object> since(ProcessResources before) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("scope", "combined client + server + driver; measurement and result drain");
    result.put(
        "processCpuNanos", cpuNanos < 0 || before.cpuNanos < 0 ? -1 : cpuNanos - before.cpuNanos);
    result.put("heapBeforeBytes", before.heapUsedBytes);
    result.put("heapAfterBytes", heapUsedBytes);
    result.put("committedVirtualMemoryBytes", committedVirtualBytes);
    result.put("gcCount", gcCount - before.gcCount);
    result.put("gcMillis", gcMillis - before.gcMillis);
    result.put("allocatedBytes", "not measured as a census; diagnostic JFR sampling is separate");
    result.put("rss", "unavailable; committed virtual memory is not resident memory");
    return result;
  }
}
