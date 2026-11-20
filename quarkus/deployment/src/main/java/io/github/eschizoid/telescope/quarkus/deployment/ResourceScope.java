package io.github.eschizoid.telescope.quarkus.deployment;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Set;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.ModuleInfo;

/**
 * The application's index as the wire scan sees it: every class and annotation it holds, except a
 * JAX-RS {@code @Path} outside the scoped classes. SmallRye OpenAPI finds resources by their
 * {@code @Path}, so the scan reads only the scoped resources, while every type a schema names is
 * still found in the index.
 */
final class ResourceScope implements IndexView {

  static final DotName PATH = DotName.createSimple("jakarta.ws.rs.Path");

  private final IndexView delegate;
  private final Set<DotName> scoped;

  ResourceScope(final IndexView delegate, final Set<DotName> scoped) {
    this.delegate = delegate;
    this.scoped = Set.copyOf(scoped);
  }

  private Collection<AnnotationInstance> inScope(final DotName name, final Collection<AnnotationInstance> found) {
    if (!name.equals(PATH)) return found;
    final var kept = new ArrayList<AnnotationInstance>(found.size());
    for (final var annotation : found) if (scoped.contains(owner(annotation.target()))) kept.add(annotation);
    return kept;
  }

  private static DotName owner(final AnnotationTarget target) {
    return switch (target.kind()) {
      case CLASS -> target.asClass().name();
      case METHOD -> target.asMethod().declaringClass().name();
      case METHOD_PARAMETER -> target.asMethodParameter().method().declaringClass().name();
      case FIELD -> target.asField().declaringClass().name();
      default -> null;
    };
  }

  @Override
  public Collection<AnnotationInstance> getAnnotations(final DotName annotationName) {
    return inScope(annotationName, delegate.getAnnotations(annotationName));
  }

  @Override
  public Collection<AnnotationInstance> getAnnotationsWithRepeatable(
    final DotName annotationName,
    final IndexView index
  ) {
    return inScope(annotationName, delegate.getAnnotationsWithRepeatable(annotationName, index));
  }

  @Override
  public Collection<AnnotationInstance> getAnnotationsWithRepeatable(
    final DotName annotationName,
    final DotName containerAnnotationName
  ) {
    return inScope(annotationName, delegate.getAnnotationsWithRepeatable(annotationName, containerAnnotationName));
  }

  @Override
  public Collection<ClassInfo> getKnownClasses() {
    return delegate.getKnownClasses();
  }

  @Override
  public ClassInfo getClassByName(final DotName className) {
    return delegate.getClassByName(className);
  }

  @Override
  public Collection<ClassInfo> getKnownDirectSubclasses(final DotName className) {
    return delegate.getKnownDirectSubclasses(className);
  }

  @Override
  public Collection<ClassInfo> getAllKnownSubclasses(final DotName className) {
    return delegate.getAllKnownSubclasses(className);
  }

  @Override
  public Collection<ClassInfo> getKnownDirectSubinterfaces(final DotName interfaceName) {
    return delegate.getKnownDirectSubinterfaces(interfaceName);
  }

  @Override
  public Collection<ClassInfo> getAllKnownSubinterfaces(final DotName interfaceName) {
    return delegate.getAllKnownSubinterfaces(interfaceName);
  }

  @Override
  public Collection<ClassInfo> getKnownDirectImplementations(final DotName interfaceName) {
    return delegate.getKnownDirectImplementations(interfaceName);
  }

  @Override
  public Collection<ClassInfo> getAllKnownImplementations(final DotName interfaceName) {
    return delegate.getAllKnownImplementations(interfaceName);
  }

  // IndexView still declares it, so every view answers it.
  @Override
  @SuppressWarnings("deprecation")
  public Collection<ClassInfo> getKnownDirectImplementors(final DotName interfaceName) {
    return delegate.getKnownDirectImplementors(interfaceName);
  }

  // IndexView still declares it, so every view answers it.
  @Override
  @SuppressWarnings("deprecation")
  public Collection<ClassInfo> getAllKnownImplementors(final DotName interfaceName) {
    return delegate.getAllKnownImplementors(interfaceName);
  }

  @Override
  public Collection<ModuleInfo> getKnownModules() {
    return delegate.getKnownModules();
  }

  @Override
  public ModuleInfo getModuleByName(final DotName moduleName) {
    return delegate.getModuleByName(moduleName);
  }

  @Override
  public Collection<ClassInfo> getKnownUsers(final DotName className) {
    return delegate.getKnownUsers(className);
  }

  @Override
  public Collection<ClassInfo> getClassesInPackage(final DotName packageName) {
    return delegate.getClassesInPackage(packageName);
  }

  @Override
  public Set<DotName> getSubpackages(final DotName packageName) {
    return delegate.getSubpackages(packageName);
  }
}
