package dev.videosearch.media;

import dev.videosearch.api.ApiError;
import org.springframework.http.HttpStatus;

public record ByteRange(long start, long end, boolean partial) {
  public long length() {
    return end - start + 1;
  }

  public static ByteRange parse(String header, long size) {
    if (size <= 0) throw invalid();
    if (header == null || header.isBlank()) return new ByteRange(0, size - 1, false);
    try {
      if (!header.startsWith("bytes=") || header.contains(",")) throw invalid();
      String[] pieces = header.substring(6).split("-", -1);
      if (pieces.length != 2) throw invalid();
      long start, end;
      if (pieces[0].isBlank()) {
        long suffix = Long.parseLong(pieces[1]);
        if (suffix <= 0) throw invalid();
        start = Math.max(0, size - suffix);
        end = size - 1;
      } else {
        start = Long.parseLong(pieces[0]);
        end = pieces[1].isBlank() ? size - 1 : Math.min(size - 1, Long.parseLong(pieces[1]));
      }
      if (start < 0 || start >= size || end < start) throw invalid();
      return new ByteRange(start, end, true);
    } catch (NumberFormatException e) {
      throw invalid();
    }
  }

  private static ApiError invalid() {
    return new ApiError(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, "INVALID_RANGE", "播放范围无效");
  }
}
