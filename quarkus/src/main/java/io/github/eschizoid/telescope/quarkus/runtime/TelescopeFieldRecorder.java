package io.github.eschizoid.telescope.quarkus.runtime;

import io.github.eschizoid.telescope.conversion.BridgeRegistry;
import io.github.eschizoid.telescope.conversion.ServiceNeeds;
import io.github.eschizoid.telescope.internal.pairing.EndpointMappers;
import io.quarkus.arc.Arc;
import io.quarkus.arc.SyntheticCreationalContext;
import io.quarkus.runtime.annotations.Recorder;
import java.util.ArrayList;
import java.util.function.Function;

/**
 * Builds, at startup, the bean of each {@code @Bridge} field whose rows call a service: the field's
 * mapper, read from the provider the {@code @Bridge} processor generated for it, bound to the one
 * bean of each service the container holds. A service with no bean, or with several, fails startup
 * naming the field, the row and the service.
 */
@Recorder
public class TelescopeFieldRecorder {

  /** Default constructor invoked by the Quarkus bytecode recorder. */
  public TelescopeFieldRecorder() {}

  /**
   * The creation of the bean named {@code name}, the field as its class writes it.
   *
   * @param name the bean's name, {@code OrderMappers.EDITS}
   * @return what the container calls to build the bean
   */
  public Function<SyntheticCreationalContext<Object>, Object> field(final String name) {
    return context -> bound(name);
  }

  private static Object bound(final String name) {
    final var loader = Thread.currentThread().getContextClassLoader();
    for (final var provider : BridgeRegistry.serviceFields(loader)) {
      if (!BridgeRegistry.beanName(provider).equals(name)) continue;
      final var field = provider.field();
      final var services = provider.services();
      final var values = new Object[services.size()];
      for (var i = 0; i < values.length; i++) {
        final var service = services.get(i);
        final var found = Arc.container().select(service);
        if (found.isUnsatisfied() || found.isAmbiguous()) {
          final var need = ServiceNeeds.needOf(field, service);
          final var row = need == null ? null : need.row();
          final var in = need == null ? null : need.in();
          if (found.isUnsatisfied()) throw new IllegalStateException(
            EndpointMappers.serviceBeanMissing(name, service.getName(), row, in, true)
          );
          final var names = new ArrayList<String>();
          for (final var handle : found.handles()) {
            final var bean = handle.getBean();
            names.add(bean.getName() != null ? bean.getName() : bean.getBeanClass().getName());
          }
          names.sort(null);
          throw new IllegalStateException(
            EndpointMappers.serviceBeansAmbiguous(name, service.getName(), row, in, names)
          );
        }
        values[i] = found.get();
      }
      return ServiceNeeds.withServices(field, values);
    }
    throw new IllegalStateException(
      "No @Bridge field provider for the bean " + name + " is registered; re-run the @Bridge processor"
    );
  }
}
