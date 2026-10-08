package io.github.eschizoid.telescope;

import io.github.eschizoid.telescope.annotations.Focus;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fusion fixture with one component of every navigable shape. {@code members()} counts its reads
 * into {@code memberReads}, which every rebuild carries forward, so a test can tell one walk of the
 * list from one walk per edit.
 */
@Focus
record FusionCrew(
  String label,
  List<FusionMember> members,
  Map<String, FusionMember> byRole,
  Optional<FusionMember> lead,
  FusionMember captain,
  AtomicInteger memberReads
) {
  @Override
  public List<FusionMember> members() {
    memberReads.incrementAndGet();
    return members;
  }
}
