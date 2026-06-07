package io.github.eschizoid.telescope.mapping;

import io.github.eschizoid.telescope.Telescope;

/**
 * Hand-written legacy-shape holder for {@link PhaseDLegacyHolderTarget} — Phase A constants only,
 * no Phase D {@code construct(Function)}. Simulates the wire shape a pre-Phase-D processor would
 * have emitted so {@link DeepMapPhaseDConstructTest} can assert {@link
 * io.github.eschizoid.telescope.internal.MetadataHolderProbe MetadataHolderProbe} degrades
 * gracefully to a {@code null} constructor field and the reflective {@code structuralIso} forward
 * branch still works.
 */
public final class PhaseDLegacyHolderTargetTelescope {

  private PhaseDLegacyHolderTargetTelescope() {}

  public static final Telescope<PhaseDLegacyHolderTarget, String> name = Telescope.lens(
    PhaseDLegacyHolderTarget::name,
    (s, v) -> new PhaseDLegacyHolderTarget(v, s.age())
  );

  public static final Telescope<PhaseDLegacyHolderTarget, Integer> age = Telescope.lens(
    PhaseDLegacyHolderTarget::age,
    (s, v) -> new PhaseDLegacyHolderTarget(s.name(), v)
  );
}
