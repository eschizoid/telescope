package io.github.eschizoid.telescope.internal;

/**
 * One-time detection of whether the substrate is executing inside a GraalVM native image, read once
 * at class-init and folded by the JIT into a constant.
 *
 * <p>The runtime accessor builders in {@link Records} / {@link Beans} default to {@link
 * java.lang.invoke.LambdaMetafactory}, which synthesizes a class per accessor — the fastest JVM
 * dispatch shape, but native-image's closed world forbids defining a class at run time. When {@link
 * #IN_IMAGE} is true those builders take the class-definition-free {@link
 * java.lang.invoke.MethodHandle} closure path instead (the same shape {@code Records.buildCtorFn}
 * already uses for the canonical constructor). The check is a JDK system property, so this carries
 * no dependency on {@code org.graalvm.nativeimage}: {@code org.graalvm.nativeimage.imagecode} is
 * set to {@code buildtime} / {@code runtime} inside an image and absent on a stock JVM.
 */
final class NativeImage {

  /** True when running inside a GraalVM native image (build-time analysis or the final binary). */
  static final boolean IN_IMAGE = System.getProperty("org.graalvm.nativeimage.imagecode") != null;

  private NativeImage() {}
}
