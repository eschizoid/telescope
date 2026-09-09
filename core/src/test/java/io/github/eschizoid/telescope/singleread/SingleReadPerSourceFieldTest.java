package io.github.eschizoid.telescope.singleread;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A generated bridge reads each source property exactly once per conversion, whatever the
 * conversion expression wraps around it. A getter is not always a field load: one that returns a
 * defensive copy pays each extra read as a full copy, a lazy-loading proxy resolves again, and a
 * volatile-backed getter can legally return null on a second load after a non-null first — under
 * which a re-reading null-guard dereferences the second, null, result.
 */
class SingleReadPerSourceFieldTest {

  @BeforeEach
  void resetCounters() {
    CountingSource.REGION_READS.set(0);
    CountingSource.NICKNAME_READS.set(0);
    CountingSource.PROFILE_READS.set(0);
  }

  private static CountingSource source(final String region, final String nickname, final Optional<String> profile) {
    final var s = new CountingSource();
    s.setRegion(region);
    s.setNickname(nickname);
    s.setProfile(profile);
    return s;
  }

  @Test
  @DisplayName("forward reads each bean property once, including the Optional shape")
  void forwardReadsEachPropertyOnce() {
    final var mapped = CountingSourceBridge.forward(source("APAC", "kai", Optional.of("p1")));

    assertEquals("APAC", mapped.getRegion());
    assertEquals("kai", mapped.getNickname());
    assertEquals(Optional.of("p1"), mapped.getProfile());
    assertEquals(1, CountingSource.REGION_READS.get());
    assertEquals(1, CountingSource.NICKNAME_READS.get());
    assertEquals(1, CountingSource.PROFILE_READS.get(), "the Optional wrap maps a local, not a re-read");
  }

  @Test
  @DisplayName("the @Default coalesce decides null-ness and substitutes on one read")
  void defaultCoalesceSharesTheSingleRead() {
    CountingRec.REGION_READS.set(0);
    final var defaulted = CountingRecBridge.forward(new CountingRec(null));
    assertEquals("EMEA", defaulted.region(), "null region takes the declared default");
    assertEquals(1, CountingRec.REGION_READS.get(), "deciding null-ness and defaulting share one read");

    CountingRec.REGION_READS.set(0);
    final var kept = CountingRecBridge.forward(new CountingRec("APAC"));
    assertEquals("APAC", kept.region());
    assertEquals(1, CountingRec.REGION_READS.get());
  }

  @Test
  @DisplayName("backward reads each target property once")
  void backwardReadsSymmetrically() {
    final var target = new CountingTarget();
    target.setRegion("LATAM");
    target.setNickname("ana");
    target.setProfile(Optional.of("p2"));

    final var back = CountingSourceBridge.backward(target);

    assertEquals("LATAM", back.getRegion());
    assertEquals(1, CountingSource.REGION_READS.get(), "building the source bean reads it back once to verify");
  }
}
