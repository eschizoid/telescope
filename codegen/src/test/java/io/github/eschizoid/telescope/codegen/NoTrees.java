package io.github.eschizoid.telescope.codegen;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Set;
import javax.annotation.processing.Completion;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;

/**
 * Runs a processor behind a processing environment that is not javac's own, as a compiler without
 * the javac tree API hands one: {@code Trees.instance} refuses it, while every other service is
 * javac's.
 */
final class NoTrees implements Processor {

  private final Processor delegate;

  private NoTrees(final Processor delegate) {
    this.delegate = delegate;
  }

  static Processor wrapping(final Processor delegate) {
    return new NoTrees(delegate);
  }

  @Override
  public Set<String> getSupportedOptions() {
    return delegate.getSupportedOptions();
  }

  @Override
  public Set<String> getSupportedAnnotationTypes() {
    return delegate.getSupportedAnnotationTypes();
  }

  @Override
  public SourceVersion getSupportedSourceVersion() {
    return delegate.getSupportedSourceVersion();
  }

  @Override
  public void init(final ProcessingEnvironment env) {
    final var foreign = (ProcessingEnvironment) Proxy.newProxyInstance(
      NoTrees.class.getClassLoader(),
      new Class<?>[] { ProcessingEnvironment.class },
      (proxy, method, args) -> {
        try {
          return method.invoke(env, args);
        } catch (final InvocationTargetException e) {
          throw e.getCause();
        }
      }
    );
    delegate.init(foreign);
  }

  @Override
  public boolean process(final Set<? extends TypeElement> annotations, final RoundEnvironment roundEnv) {
    return delegate.process(annotations, roundEnv);
  }

  @Override
  public Iterable<? extends Completion> getCompletions(
    final Element element,
    final AnnotationMirror annotation,
    final ExecutableElement member,
    final String userText
  ) {
    return delegate.getCompletions(element, annotation, member, userText);
  }
}
