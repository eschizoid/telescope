package io.github.eschizoid.telescope.quarkus.deployment;

import io.github.eschizoid.telescope.conversion.BridgeProvider;
import io.github.eschizoid.telescope.conversion.BridgeRegistry;
import io.github.eschizoid.telescope.conversion.ContainerCopy;
import io.github.eschizoid.telescope.conversion.FromMapProvider;
import io.github.eschizoid.telescope.conversion.ProxyResolver;
import io.github.eschizoid.telescope.inject.TelescopeProjection;
import io.github.eschizoid.telescope.inject.TelescopeTransformation;
import io.github.eschizoid.telescope.internal.pairing.EndpointMappers;
import io.github.eschizoid.telescope.quarkus.TelescopeMapperRegistry;
import io.github.eschizoid.telescope.quarkus.rest.TelescopeResponse;
import io.github.eschizoid.telescope.quarkus.rest.runtime.MappedEndpoint;
import io.github.eschizoid.telescope.quarkus.rest.runtime.TelescopeRequestBodyReader;
import io.github.eschizoid.telescope.quarkus.rest.runtime.TelescopeResponseFilter;
import io.github.eschizoid.telescope.quarkus.rest.runtime.TelescopeRestEndpoints;
import io.github.eschizoid.telescope.quarkus.rest.runtime.TelescopeRestRecorder;
import io.github.eschizoid.telescope.quarkus.runtime.TelescopeFieldRecorder;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.BeanContainerBuildItem;
import io.quarkus.arc.deployment.SyntheticBeanBuildItem;
import io.quarkus.arc.deployment.UnremovableBeanBuildItem;
import io.quarkus.arc.processor.BuiltinScope;
import io.quarkus.deployment.Capabilities;
import io.quarkus.deployment.Capability;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.AdditionalIndexedClassesBuildItem;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.nativeimage.LambdaCapturingTypeBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveHierarchyBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ServiceProviderBuildItem;
import io.quarkus.deployment.util.JandexUtil;
import io.quarkus.resteasy.reactive.spi.ContainerResponseFilterBuildItem;
import io.quarkus.resteasy.reactive.spi.MessageBodyReaderBuildItem;
import jakarta.inject.Singleton;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.RuntimeType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jboss.jandex.ClassType;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.ParameterizedType;
import org.jboss.jandex.Type;

/**
 * The build steps of the telescope Quarkus extension. They register telescope's service providers
 * and the method references they and the generated beans decode, so a native image runs the
 * generated bridges; and they find every mapped request and response body of Quarkus REST in the
 * application's index, refuse a misconfigured one with the method named, register the response
 * filter and the body reader for them, register every type a body is read or written as for
 * reflection, and record the bodies for the runtime, which resolves their mappers at startup.
 */
class TelescopeProcessor {

  private static final String FEATURE = "telescope";
  // Named rather than loaded, because the class links against Hibernate Validator, which an
  // application that validates nothing does not have.
  private static final String WIRE_VALIDATION = "io.github.eschizoid.telescope.quarkus.rest.runtime.WireValidation";
  // Every service telescope reads through ServiceLoader: the generated bridges @Bridge fields run
  // on, the generated @FromMap binders, and telescope-jpa's container policy and proxy resolver.
  // A Quarkus native image finds no provider the build does not register.
  private static final List<String> SERVICES = List.of(
    BridgeProvider.class.getName(),
    FromMapProvider.class.getName(),
    ContainerCopy.Policy.class.getName(),
    ProxyResolver.class.getName(),
    "io.github.eschizoid.telescope.internal.ProxyClasses"
  );

  @BuildStep
  FeatureBuildItem feature() {
    return new FeatureBuildItem(FEATURE);
  }

  // The projection and transformation interfaces live in telescope-core, which carries no index;
  // the type arguments of a projection named by using are resolved against them, and the
  // application's projections and transformations are found through them.
  @BuildStep
  AdditionalIndexedClassesBuildItem indexContracts() {
    return new AdditionalIndexedClassesBuildItem(
      TelescopeProjection.class.getName(),
      TelescopeTransformation.class.getName()
    );
  }

  @BuildStep
  void serviceProviders(
    final BuildProducer<ServiceProviderBuildItem> services,
    final BuildProducer<LambdaCapturingTypeBuildItem> lambdas
  ) {
    for (final var service : SERVICES) {
      final var found = ServiceProviderBuildItem.allProvidersFromClassPath(service);
      if (found.providers().isEmpty()) continue;
      services.produce(found);
      // A @Bridge field's mapper checks that the bridge was generated from its rows by reading the
      // method references the rows hold, which needs the declaring class's lambdas serializable.
      if (service.equals(BridgeProvider.class.getName())) for (final var provider : found.providers()) {
        final var declaring = declaringType(provider);
        if (declaring != null) lambdas.produce(new LambdaCapturingTypeBuildItem(declaring));
      }
    }
  }

  // A projection's translate rows and a transformation's path are method references decoded at run
  // time; each is written in the interface the application declares, or in a class implementing it.
  @BuildStep
  void generatedBeanLambdas(
    final CombinedIndexBuildItem index,
    final BuildProducer<LambdaCapturingTypeBuildItem> lambdas
  ) {
    final var types = new LinkedHashSet<String>();
    for (final var contract : List.of(TelescopeProjection.class, TelescopeTransformation.class)) {
      final var name = DotName.createSimple(contract);
      for (final var face : index.getIndex().getAllKnownSubinterfaces(name)) types.add(face.name().toString());
      for (final var type : index.getIndex().getAllKnownImplementations(name)) types.add(type.name().toString());
    }
    for (final var type : types) lambdas.produce(new LambdaCapturingTypeBuildItem(type));
  }

  /**
   * Registers, for every {@code @Bridge} field whose rows call a service, one singleton bean of the
   * field's declared type, named after the field as its class writes it, built when the application
   * starts from the field's mapper bound to the container's bean of each service, which are kept
   * from removal. The fields are read from the providers the {@code @Bridge} processor generated;
   * reading what they record initialises no declaring class.
   */
  @BuildStep
  @Record(ExecutionTime.RUNTIME_INIT)
  void fieldBeans(
    final TelescopeFieldRecorder recorder,
    final CombinedIndexBuildItem index,
    final BuildProducer<SyntheticBeanBuildItem> synthetic,
    final BuildProducer<UnremovableBeanBuildItem> unremovable
  ) {
    final var named = new HashMap<String, String>();
    for (final var provider : BridgeRegistry.serviceFields(Thread.currentThread().getContextClassLoader())) {
      final var name = BridgeRegistry.beanName(provider);
      final var declaring = provider.declaringType().getName();
      // A field of a class outside the application's index belongs to no bean archive the
      // application builds, so it registers no bean.
      final var indexed = index.getIndex().getClassByName(DotName.createSimple(declaring));
      if (indexed == null) continue;
      final var taken = named.putIfAbsent(name, declaring);
      if (taken != null) throw new IllegalStateException(EndpointMappers.fieldBeanNamedTwice(name, taken, declaring));
      final var raw = DotName.createSimple(provider.fieldType());
      final var field = indexed.field(provider.fieldName());
      final Type type = field != null
        ? field.type()
        : ParameterizedType.create(
            raw,
            new Type[] {
              ClassType.create(DotName.createSimple(provider.sourceType())),
              ClassType.create(DotName.createSimple(provider.targetType())),
            },
            null
          );
      for (final var service : provider.services()) {
        unremovable.produce(UnremovableBeanBuildItem.beanTypes(DotName.createSimple(service)));
      }
      synthetic.produce(
        SyntheticBeanBuildItem.configure(raw)
          .types(type)
          .named(name)
          .scope(Singleton.class)
          .unremovable()
          .startup()
          .setRuntimeInit()
          .createWith(recorder.field(name))
          .done()
      );
    }
  }

  @BuildStep
  MappedBodiesBuildItem bodies(final CombinedIndexBuildItem index, final Capabilities capabilities) {
    if (!capabilities.isPresent(Capability.REST)) return new MappedBodiesBuildItem(List.of());
    return new MappedBodiesBuildItem(
      MappedBodies.scan(index.getIndex(), capabilities.isPresent(Capability.HIBERNATE_VALIDATOR))
    );
  }

  @BuildStep
  void register(
    final MappedBodiesBuildItem bodies,
    final CombinedIndexBuildItem index,
    final BuildProducer<AdditionalBeanBuildItem> beans,
    final BuildProducer<UnremovableBeanBuildItem> unremovable,
    final BuildProducer<ContainerResponseFilterBuildItem> filters,
    final BuildProducer<MessageBodyReaderBuildItem> readers,
    final BuildProducer<ReflectiveHierarchyBuildItem> reflection,
    final BuildProducer<ReflectiveClassBuildItem> holders
  ) {
    if (bodies.bodies().isEmpty()) return;
    final var classes = new ArrayList<String>();
    classes.add(TelescopeRestEndpoints.class.getName());
    if (bodies.hasResponses()) classes.add(TelescopeResponseFilter.class.getName());
    if (bodies.hasRequests()) classes.add(TelescopeRequestBodyReader.class.getName());
    if (bodies.bodies().stream().anyMatch(MappedBody::validated)) classes.add(WIRE_VALIDATION);
    beans.produce(
      AdditionalBeanBuildItem.builder()
        .addBeanClasses(classes)
        .setDefaultScope(BuiltinScope.SINGLETON.getName())
        .setUnremovable()
        .build()
    );
    // Projections and the registry are looked up at startup, never injected, so the container
    // would otherwise remove them as unused.
    unremovable.produce(
      UnremovableBeanBuildItem.beanTypes(MappedBodies.PROJECTION, DotName.createSimple(TelescopeMapperRegistry.class))
    );
    if (bodies.hasResponses()) filters.produce(
      new ContainerResponseFilterBuildItem.Builder(TelescopeResponseFilter.class.getName())
        .setNameBindingNames(Set.of(TelescopeResponse.class.getName()))
        .setRegisterAsBean(false)
        .build()
    );
    // Ahead of every reader of the default priority, so a mapped body is read here; every other
    // body is declined and reaches the reader Quarkus REST would have chosen.
    if (bodies.hasRequests()) readers.produce(
      new MessageBodyReaderBuildItem.Builder(TelescopeRequestBodyReader.class.getName(), Object.class.getName())
        .setBuiltin(false)
        .setPriority(Priorities.USER - 1000)
        .setRuntimeType(RuntimeType.SERVER)
        .build()
    );
    for (final var type : wireTypes(bodies.bodies(), index.getIndex()))
      reflection.produce(ReflectiveHierarchyBuildItem.builder(type).index(index.getIndex()).source(FEATURE).build());
    final var found = bridgeHolders(bodies.bodies());
    if (!found.isEmpty()) holders.produce(
      ReflectiveClassBuildItem.builder(found.toArray(String[]::new)).fields().build()
    );
  }

  @BuildStep
  @Record(ExecutionTime.RUNTIME_INIT)
  void install(
    final MappedBodiesBuildItem bodies,
    final TelescopeRestRecorder recorder,
    final BeanContainerBuildItem container
  ) {
    if (bodies.bodies().isEmpty()) return;
    final var endpoints = new ArrayList<MappedEndpoint>();
    for (final var body : bodies.bodies())
      for (final var methodId : body.methodIds())
        endpoints.add(
          new MappedEndpoint(
            methodId,
            body.where(),
            body.annotation(),
            body.request(),
            body.shape(),
            load(body.source()),
            load(body.target()),
            load(body.using()),
            body.holder(),
            body.required(),
            body.validated(),
            body.validationPath()
          )
        );
    recorder.install(endpoints);
  }

  // The generated bridge holders of each pair a body maps without a projection. The forward
  // mapping finds a pair's holder by name, as <Source>To<Target>Bridge or <Source>Bridge beside the
  // source, and reads its BRIDGE field, which a native image allows only for a registered class.
  // Found, the generated bridge maps the pair; missed, the pair falls back to the reflective
  // mapping. A holder is looked up as a class file, because the module that holds it need not be
  // indexed.
  private static List<String> bridgeHolders(final List<MappedBody> bodies) {
    final var loader = Thread.currentThread().getContextClassLoader();
    final var names = new LinkedHashSet<String>();
    for (final var body : bodies) {
      if (body.using() != null || body.source() == null || body.target() == null) continue;
      final var simple = body.target().substring(body.target().lastIndexOf('.') + 1);
      final var nested = simple.substring(simple.lastIndexOf('$') + 1);
      for (final var candidate : List.of(body.source() + "To" + nested + "Bridge", body.source() + "Bridge"))
        if (loader.getResource(candidate.replace('.', '/') + ".class") != null) names.add(candidate);
    }
    return List.copyOf(names);
  }

  // The types a body is read as or written as, which appear in no resource signature: the
  // response targets and request sources, and both type arguments of each projection named.
  private static Set<DotName> wireTypes(final List<MappedBody> bodies, final IndexView index) {
    final var types = new LinkedHashSet<DotName>();
    for (final var body : bodies) {
      final var wire = body.request() ? body.source() : body.target();
      if (wire != null) types.add(DotName.createSimple(wire));
      if (body.using() == null) continue;
      final var projection = DotName.createSimple(body.using());
      for (final Type argument : JandexUtil.resolveTypeParameters(projection, MappedBodies.PROJECTION, index))
        if (argument.kind() == Type.Kind.CLASS) types.add(argument.name());
    }
    return types;
  }

  // The class whose @Bridge field declared the provider's bridge, or null for a bridge a class
  // declared. A generated provider is a plain class whose methods return class literals, so making
  // one at build time runs no application code; a hand-written provider's constructor does run
  // here, in the build. A provider that cannot be made registers no lambdas, and a native image
  // then refuses the first of its rows it reads, naming the class.
  private static String declaringType(final String provider) {
    try {
      final var made = Class.forName(provider, false, Thread.currentThread().getContextClassLoader())
        .getDeclaredConstructor()
        .newInstance();
      final var declaring = made instanceof BridgeProvider bridge ? bridge.declaringType() : null;
      return declaring == null ? null : declaring.getName();
    } catch (final ReflectiveOperationException | LinkageError unreadable) {
      return null;
    }
  }

  private static Class<?> load(final String name) {
    if (name == null) return null;
    try {
      return Class.forName(name, false, Thread.currentThread().getContextClassLoader());
    } catch (final ClassNotFoundException e) {
      throw new IllegalStateException("cannot load " + name + ", which a mapped body names", e);
    }
  }
}
