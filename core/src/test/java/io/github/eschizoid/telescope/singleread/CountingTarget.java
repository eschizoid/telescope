package io.github.eschizoid.telescope.singleread;

import java.util.Optional;

/** Target for the exactly-once read fixture. */
public class CountingTarget {

  private String region;
  private String nickname;
  private Optional<String> profile = Optional.empty();

  public String getRegion() {
    return region;
  }

  public void setRegion(final String region) {
    this.region = region;
  }

  public String getNickname() {
    return nickname;
  }

  public void setNickname(final String nickname) {
    this.nickname = nickname;
  }

  public Optional<String> getProfile() {
    return profile;
  }

  public void setProfile(final Optional<String> profile) {
    this.profile = profile;
  }
}
