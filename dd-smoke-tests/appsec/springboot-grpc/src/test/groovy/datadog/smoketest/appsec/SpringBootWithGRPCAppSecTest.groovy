package datadog.smoketest.appsec


import okhttp3.Request

class SpringBootWithGRPCAppSecTest extends AbstractAppSecServerSmokeTest {

  @Override
  ProcessBuilder createProcessBuilder() {
    String springBootShadowJar = System.getProperty("datadog.smoketest.appsec.springboot-grpc.shadowJar.path")
    assert springBootShadowJar != null

    List<String> command = [
      javaPath(),
      *defaultJavaProperties,
      *defaultAppSecProperties,
      '-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=0.0.0.0:5005',
      "-jar",
      springBootShadowJar,
      "--server.port=${httpPort}"
    ].collect { it as String }

    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
  }

  static final String ROUTE = 'async_annotation_greeting'

  def greeter() {
    setup:
    String url = "http://localhost:${httpPort}/${ROUTE}"
    def request = new Request.Builder()
      .url("${url}?message=${'.htaccess'.bytes.encodeBase64()}")
      .get().build()

    when:
    def response = client.newCall(request).execute()

    then:
    def responseBodyStr = response.body().string()
    responseBodyStr != null
    responseBodyStr.contains("bye")
    response.body().contentType().toString().contains("text/plain")
    response.code() == 200

    and:
    waitForTraceCount(2) == 2
    rootSpans.size() == 2
    def grpcRootSpan = rootSpans.find { it.triggers }
    grpcRootSpan.triggers['rule']['tags']['type'] == 'lfi'
    grpcRootSpan.triggers['rule_matches'][0][0]['parameters']['address'][0] == 'grpc.server.request.message'
  }
}
