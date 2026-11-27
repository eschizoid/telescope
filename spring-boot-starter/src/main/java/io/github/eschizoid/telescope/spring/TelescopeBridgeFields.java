package io.github.eschizoid.telescope.spring;

import io.github.eschizoid.telescope.conversion.BridgeProvider;
import io.github.eschizoid.telescope.conversion.BridgeRegistry;
import io.github.eschizoid.telescope.conversion.ServiceNeeds;
import io.github.eschizoid.telescope.internal.pairing.EndpointMappers;
import java.util.HashMap;
import java.util.List;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.ResolvableType;

/**
 * Registers, for every {@code @Bridge} field whose rows call a service, its own or through a mapper
 * it converts through, one singleton bean of the field's declared type, {@code
 * EditMapper<OrderChanges, Order>}, {@code ForwardMapper<Order, OrderDto>} or {@code Mapper<Order,
 * OrderDto>}, holding the field's mapper bound to the one bean of each service. The fields are the
 * ones the {@code @Bridge} processor recorded in its generated providers, which {@code
 * ServiceLoader} finds; the field's value is read from its provider, so nothing is scanned or read
 * reflectively.
 *
 * <p>Each bean is named after the field as its class writes it, {@code OrderMappers.EDITS}, which a
 * {@code @Qualifier} takes. A service with no bean, or with two beans and none primary, fails
 * startup naming the field, the row and the service; two fields giving one name fail startup naming
 * both declaring classes.
 */
public final class TelescopeBridgeFields implements BeanDefinitionRegistryPostProcessor, BeanClassLoaderAware {

  private ClassLoader loader = TelescopeBridgeFields.class.getClassLoader();

  /** Default constructor invoked by Spring's bean factory. */
  public TelescopeBridgeFields() {}

  @Override
  public void setBeanClassLoader(final ClassLoader classLoader) {
    this.loader = classLoader;
  }

  @Override
  public void postProcessBeanDefinitionRegistry(final BeanDefinitionRegistry registry) throws BeansException {
    final var named = new HashMap<String, Class<?>>();
    final var factory = (ConfigurableListableBeanFactory) registry;
    for (final var provider : BridgeRegistry.serviceFields(loader)) {
      final var name = BridgeRegistry.beanName(provider);
      final var taken = named.putIfAbsent(name, provider.declaringType());
      if (taken != null) throw new IllegalStateException(
        EndpointMappers.fieldBeanNamedTwice(name, taken.getName(), provider.declaringType().getName())
      );
      final var definition = new RootBeanDefinition();
      definition.setTargetType(
        ResolvableType.forClassWithGenerics(provider.fieldType(), provider.sourceType(), provider.targetType())
      );
      definition.setInstanceSupplier(() -> bound(provider, name, factory));
      registry.registerBeanDefinition(name, definition);
    }
  }

  @Override
  public void postProcessBeanFactory(final ConfigurableListableBeanFactory beanFactory) throws BeansException {
    // Every definition is registered in postProcessBeanDefinitionRegistry.
  }

  /**
   * The name of the bean this registrar registers for the {@code @Bridge} field declaring {@code
   * source -> target} as a {@code kind}, or null where no such field calls a service.
   */
  public static String beanFor(
    final ClassLoader loader,
    final Class<?> kind,
    final Class<?> source,
    final Class<?> target
  ) {
    for (final var provider : BridgeRegistry.serviceFields(loader)) {
      if (provider.fieldType() == kind && provider.sourceType() == source && provider.targetType() == target) {
        return BridgeRegistry.beanName(provider);
      }
    }
    return null;
  }

  /** The field's mapper bound to the one bean of each of its services. */
  private static Object bound(
    final BridgeProvider provider,
    final String name,
    final ConfigurableListableBeanFactory factory
  ) {
    final var field = provider.field();
    final var services = provider.services();
    final var values = new Object[services.size()];
    for (var i = 0; i < values.length; i++) {
      final var service = services.get(i);
      final var names = factory.getBeanNamesForType(service);
      // getIfUnique answers the primary bean among several, and null where there is none or no
      // primary one.
      final var bean = names.length == 0 ? null : factory.getBeanProvider(service).getIfUnique();
      if (bean == null) {
        final var need = ServiceNeeds.needOf(field, service);
        final var row = need == null ? null : need.row();
        final var in = need == null ? null : need.in();
        throw new IllegalStateException(
          names.length == 0
            ? EndpointMappers.serviceBeanMissing(name, service.getName(), row, in, false)
            : EndpointMappers.serviceBeansAmbiguous(name, service.getName(), row, in, List.of(names))
        );
      }
      values[i] = bean;
    }
    return ServiceNeeds.withServices(field, values);
  }
}
