package datadog.trace.api;

public interface StatsDClientManager {
  StatsDClient statsDClient(String host, Integer port, String namespace, String[] constantTags);
}
