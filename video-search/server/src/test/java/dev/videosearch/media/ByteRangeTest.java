package dev.videosearch.media;

import static org.junit.jupiter.api.Assertions.*;

import dev.videosearch.api.ApiError;
import org.junit.jupiter.api.Test;

class ByteRangeTest {
  @Test
  void supportsSeekSuffixAndClampsEnd() {
    assertEquals(new ByteRange(100, 999, true), ByteRange.parse("bytes=100-", 1000));
    assertEquals(new ByteRange(900, 999, true), ByteRange.parse("bytes=-100", 1000));
    assertEquals(new ByteRange(100, 999, true), ByteRange.parse("bytes=100-5000", 1000));
  }

  @Test
  void invalidRangesCannotReadOutsideObject() {
    for (String value :
        new String[] {
          "bytes=1000-", "bytes=200-100", "bytes=-0", "bytes=0-1,3-5", "bytes=-", "items=0-1"
        }) assertThrows(ApiError.class, () -> ByteRange.parse(value, 1000));
  }
}
