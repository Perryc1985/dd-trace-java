package datadog.trace.agent.test;

import com.google.common.reflect.ClassPath;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.JarFile;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.dynamic.ClassFileLocator;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.model.InitializationError;
import org.spockframework.runtime.Sputnik;

/**
 * Runs a spock test in an agent-friendly way.
 *
 * <ul>
 *   <li>Adds agent bootstrap classes to bootstrap classpath.
 * </ul>
 */
public class SpockRunner extends Sputnik {
  /**
   * An exact copy of Utils#BOOSTRAP_PACKAGE_PREFIXES.
   *
   * <p>This list is needed to initialize the bootstrap classpath because Utils' static initializer
   * references bootstrap classes (e.g. DatadogClassLoader).
   */
  public static final String[] BOOTSTRAP_PACKAGE_PREFIXES_COPY = {
    "io.opentracing",
    "datadog.slf4j",
    "datadog.trace.bootstrap",
    "datadog.trace.api",
    "datadog.trace.context"
  };

  private static final String[] TEST_BOOTSTRAP_PREFIXES;

  static {
    ByteBuddyAgent.install();
    final String[] testBS = {
      "io.opentracing",
      "org.slf4j",
      "ch.qos.logback",
      // Tomcat's servlet classes must be on boostrap
      // when running tomcat test
      "javax.servlet.ServletContainerInitializer",
      "javax.servlet.ServletContext"
    };
    TEST_BOOTSTRAP_PREFIXES =
        Arrays.copyOf(
            BOOTSTRAP_PACKAGE_PREFIXES_COPY,
            BOOTSTRAP_PACKAGE_PREFIXES_COPY.length + testBS.length);
    for (int i = 0; i < testBS.length; ++i) {
      TEST_BOOTSTRAP_PREFIXES[i + BOOTSTRAP_PACKAGE_PREFIXES_COPY.length] = testBS[i];
    }

    setupBootstrapClasspath();
  }

  private final InstrumentationClassLoader customLoader;

  public SpockRunner(Class<?> clazz)
      throws InitializationError, NoSuchFieldException, SecurityException, IllegalArgumentException,
          IllegalAccessException {
    super(shadowTestClass(clazz));
    assertNoBootstrapClassesInTestClass(clazz);
    // access the classloader created in shadowTestClass above
    Field clazzField = Sputnik.class.getDeclaredField("clazz");
    try {
      clazzField.setAccessible(true);
      customLoader =
          (InstrumentationClassLoader) ((Class<?>) clazzField.get(this)).getClassLoader();
    } finally {
      clazzField.setAccessible(false);
    }
  }

  private static void assertNoBootstrapClassesInTestClass(Class<?> testClass) {
    for (final Field field : testClass.getDeclaredFields()) {
      assertNotBootstrapClass(testClass, field.getType());
    }

    for (final Method method : testClass.getDeclaredMethods()) {
      assertNotBootstrapClass(testClass, method.getReturnType());
      for (final Class paramType : method.getParameterTypes()) {
        assertNotBootstrapClass(testClass, paramType);
      }
    }
  }

  private static void assertNotBootstrapClass(Class<?> testClass, Class<?> clazz) {
    if ((!clazz.isPrimitive()) && isBootstrapClass(clazz.getName())) {
      throw new IllegalStateException(
          testClass.getName()
              + ": Bootstrap classes are not allowed in test class field or method signatures. Offending class: "
              + clazz.getName());
    }
  }

  private static boolean isBootstrapClass(String className) {
    for (int i = 0; i < TEST_BOOTSTRAP_PREFIXES.length; ++i) {
      if (className.startsWith(TEST_BOOTSTRAP_PREFIXES[i])) {
        return true;
      }
    }
    return false;
  }

  // Shadow the test class with bytes loaded by InstrumentationClassLoader
  private static Class<?> shadowTestClass(final Class<?> clazz) {
    try {
      InstrumentationClassLoader customLoader =
          new InstrumentationClassLoader(
              datadog.trace.agent.test.SpockRunner.class.getClassLoader(), clazz.getName());
      return customLoader.shadow(clazz);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public void run(final RunNotifier notifier) {
    final ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
    try {
      Thread.currentThread().setContextClassLoader(customLoader);
      super.run(notifier);
    } finally {
      Thread.currentThread().setContextClassLoader(contextLoader);
    }
  }

  private static void setupBootstrapClasspath() {
    try {
      final File bootstrapJar = createBootstrapJar();
      ByteBuddyAgent.getInstrumentation()
          .appendToBootstrapClassLoaderSearch(new JarFile(bootstrapJar));
      // Utils cannot be referenced before this line, as its static initializers load bootstrap
      // classes (for example, the bootstrap proxy).
      datadog.trace.agent.tooling.Utils.getBootstrapProxy().addURL(bootstrapJar.toURI().toURL());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static File createBootstrapJar() throws IOException {
    Set<String> bootstrapClasses = new HashSet<String>();
    for (ClassPath.ClassInfo info : TestUtils.getTestClasspath().getAllClasses()) {
      // if info starts with bootstrap prefix: add to bootstrap jar
      if (isBootstrapClass(info.getName())) {
        bootstrapClasses.add(info.getResourceName());
      }
    }
    return new File(
        TestUtils.createJarWithClasses(
                AgentTestRunner.class.getClassLoader(), bootstrapClasses.toArray(new String[0]))
            .getFile());
  }

  /** Run test classes in a classloader which loads test classes before delegating. */
  private static class InstrumentationClassLoader extends java.lang.ClassLoader {
    final ClassLoader parent;
    final String shadowPrefix;

    public InstrumentationClassLoader(ClassLoader parent, String shadowPrefix) {
      super(parent);
      this.parent = parent;
      this.shadowPrefix = shadowPrefix;
    }

    /** Forcefully inject the bytes of clazz into this classloader. */
    public Class<?> shadow(Class<?> clazz) throws IOException {
      Class<?> loaded = this.findLoadedClass(clazz.getName());
      if (loaded != null && loaded.getClassLoader() == this) {
        return loaded;
      }
      final ClassFileLocator locator = ClassFileLocator.ForClassLoader.of(clazz.getClassLoader());
      final byte[] classBytes = locator.locate(clazz.getName()).resolve();

      return this.defineClass(clazz.getName(), classBytes, 0, classBytes.length);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      synchronized (super.getClassLoadingLock(name)) {
        Class c = this.findLoadedClass(name);
        if (c != null) {
          return c;
        }
        if (name.startsWith(shadowPrefix)) {
          try {
            return shadow(super.loadClass(name, resolve));
          } catch (Exception e) {
          }
        }

        /*
        if (!name.startsWith("datadog.trace.agent.test.")) {
          for (int i = 0; i < AGENT_PACKAGE_PREFIXES.length; ++i) {
            if (name.startsWith(AGENT_PACKAGE_PREFIXES[i])) {
              throw new ClassNotFoundException(
                  "refusing to load agent class: " + name + " on test classloader.");
            }
          }
        }
        */
        return parent.loadClass(name);
      }
    }
  }
}
