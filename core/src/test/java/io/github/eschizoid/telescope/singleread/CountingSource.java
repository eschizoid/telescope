package io.github.eschizoid.telescope.singleread;

import io.github.eschizoid.telescope.annotations.Bridge;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bean fixture whose getters count their invocations. The generated bridge must read each source
 * property exactly once per conversion: the {@code Optional} shape wraps its read and the plain
 * references feed setters, and a getter that copies, lazy-loads, or reads a volatile field is
 * only safe under exactly-once. The {@code @Default} coalesce is pinned on the record fixture —
 * bean sources do not receive the coalesce at all today.
 */
@Bridge(CountingTarget.class)
public class CountingSource {

  public static final AtomicInteger REGION_READS = new AtomicInteger();
  public static final AtomicInteger NICKNAME_READS = new AtomicInteger();
  public static final AtomicInteger PROFILE_READS = new AtomicInteger();

  private String region;
  private String nickname;
  private Optional<String> profile = Optional.empty();

  public String getRegion() {
    REGION_READS.incrementAndGet();
    return region;
  }

  public void setRegion(final String region) {
    this.region = region;
  }

  public String getNickname() {
    NICKNAME_READS.incrementAndGet();
    return nickname;
  }

  public void setNickname(final String nickname) {
    this.nickname = nickname;
  }

  public Optional<String> getProfile() {
    PROFILE_READS.incrementAndGet();
    return profile;
  }

  public void setProfile(final Optional<String> profile) {
    this.profile = profile;
  }
}
