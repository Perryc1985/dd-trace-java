package com.datadog.profiling.testing;

import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import delight.fileupload.FileUpload;
import okhttp3.MediaType;
import okhttp3.mockwebserver.RecordedRequest;

public final class ProfilingTestUtils {

  private static final MediaType OCTET_STREAM = MediaType.parse("application/octet-stream");

  public static Multimap<String, Object> parseProfilingRequestParameters(
      final RecordedRequest request) {
    return FileUpload.parse(request.getBody().readByteArray(), request.getHeader("Content-Type"))
        .stream()
        .collect(
            ImmutableMultimap::<String, Object>builder,
            (builder, value) ->
                builder.put(
                    value.getFieldName(),
                    OCTET_STREAM.toString().equals(value.getContentType())
                        ? value.get()
                        : value.getString()),
            (builder1, builder2) -> builder1.putAll(builder2.build()))
        .build();
  }
}
