package io.github.eschizoid.telescope.quarkus.rest.runtime;

import io.quarkus.arc.Arc;
import io.quarkus.runtime.annotations.Recorder;
import java.util.List;

/** Hands the bodies the build found to the runtime, which resolves their mappers at startup. */
@Recorder
public class TelescopeRestRecorder {

  /** Default constructor invoked by the Quarkus bytecode recorder. */
  public TelescopeRestRecorder() {}

  /**
   * Resolves the mapper of every body the build found.
   *
   * @param endpoints the bodies the build found
   */
  public void install(final List<MappedEndpoint> endpoints) {
    Arc.container().instance(TelescopeRestEndpoints.class).get().install(endpoints);
  }
}
