package io.oryxos.web.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.oryxos.core.policy.Action;
import io.oryxos.web.security.RbacEndpointCoverage.MappedEndpoint;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * 端点覆盖漂移闸（039 SC-005）：端点从**真实控制器的注解**推导，而不是照抄一份手写清单。
 *
 * <p>{@link RbacEndpointCoverageCheck} 只在 {@code oryxos.web.rbac.enabled=true} 时于**启动期**拦一次。在它之前，
 * 新增一个 {@code @RestController} 就能让开了 RBAC 的部署直接起不来，而 CI 全绿 —— 本仓已经发生过一次： {@code capabilities} /
 * {@code routing} / {@code evals} / {@code flows} 四个控制器从未登记，{@code team-tasks} 则因为带
 * {@code @ConditionalOnProperty} 而更隐蔽（默认不注册，一旦打开就与 RBAC 撞车）。
 *
 * <p><b>为什么不用 Spring 的 {@code ClassPathScanningCandidateComponentProvider}</b>：它会跳过条件化组件，而本闸要
 * 覆盖的恰恰是那些**可能被打开的**开关端点。这里直接枚举 {@code target/classes} 下的类并看注解，与运行时的条件求值 无关 —— 于是「打开该开关 + 打开
 * RBAC」这个配置组合也被覆盖。
 */
class RbacEndpointCoverageDriftTest {

  private static final String BASE_PACKAGE = "io.oryxos.web";

  /** 与 {@code RbacEndpointCoverage} 对「没写 method 的 @RequestMapping」的处理保持一致。 */
  private static final List<String> METHODS_WHEN_UNSPECIFIED =
      List.of("GET", "POST", "PUT", "PATCH", "DELETE");

  @Test
  @DisplayName("真实控制器的每个受保护端点都已在 RequestActionResolver 登记")
  void everyControllerEndpointIsRegistered() {
    List<MappedEndpoint> endpoints = controllerEndpoints();

    // 扫描本身要有效：一个什么都没扫到的用例永远不会红，等于没有
    assertThat(endpoints).hasSizeGreaterThan(100);
    assertThat(RbacEndpointCoverage.gaps(endpoints)).isEmpty();
  }

  @Test
  @DisplayName("枚举不跳过条件化控制器（team-tasks 就是这么漏掉的）")
  void enumerationAlsoSeesConditionallyRegisteredControllers() {
    assertThat(restControllers())
        .as("带 @ConditionalOnProperty 的控制器也必须在枚举里，否则这类漂移会再次溜过本闸")
        .extracting(Class::getSimpleName)
        .contains("TeamTaskApiController", "A2aJsonRpcController", "CapabilityApiController");
  }

  @Test
  @DisplayName("补登记的五个前缀解析到预期动作，而不是「非空就行」")
  void newlyRegisteredPrefixesResolveToTheIntendedAction() {
    assertResolvesTo("GET", "/api/v1/capabilities", Action.READ_WORKSPACE);
    assertResolvesTo("GET", "/api/v1/routing/decisions", Action.READ_WORKSPACE);
    assertResolvesTo("GET", "/api/v1/evals/export/trace/x", Action.READ_AUDIT);
    assertResolvesTo("POST", "/api/v1/flows/generate", Action.RUN_AGENT);
    assertResolvesTo("GET", "/api/v1/team-tasks", Action.READ_WORKSPACE);
    assertResolvesTo("GET", "/api/v1/team-tasks/x", Action.READ_WORKSPACE);
    assertResolvesTo("POST", "/api/v1/team-tasks", Action.RUN_AGENT);
  }

  private static void assertResolvesTo(String method, String path, Action action) {
    RequestActionResolver.Resolution resolution = RequestActionResolver.resolve(method, path);
    assertThat(resolution).as("%s %s 应已登记", method, path).isNotNull();
    assertThat(resolution.isSkip()).as("%s %s 不应被判为 skip", method, path).isFalse();
    assertThat(resolution.action()).as("%s %s", method, path).isEqualTo(action);
  }

  /** 每个 {@code @RestController} 的注解声明摊平成 (method, pattern) 列表。 */
  private static List<MappedEndpoint> controllerEndpoints() {
    List<MappedEndpoint> endpoints = new ArrayList<>();
    for (Class<?> controller : restControllers()) {
      for (String prefix : classPrefixes(controller)) {
        for (Method method : controller.getDeclaredMethods()) {
          addMethodEndpoints(endpoints, prefix, method);
        }
      }
    }
    return endpoints;
  }

  /** 枚举编译产物里的 {@code @RestController}：与运行时的条件求值无关。 */
  private static List<Class<?>> restControllers() {
    Path root = classOutputRoot();
    List<Class<?>> controllers = new ArrayList<>();
    try (Stream<Path> files = Files.walk(root)) {
      for (Path file : files.filter(RbacEndpointCoverageDriftTest::isClassFile).toList()) {
        Class<?> type = loadOrNull(className(root, file));
        if (type != null && type.isAnnotationPresent(RestController.class)) {
          controllers.add(type);
        }
      }
    } catch (IOException e) {
      throw new IllegalStateException("枚举控制器失败: " + root, e);
    }
    controllers.sort(Comparator.comparing(Class::getName));
    return controllers;
  }

  /**
   * 生产类的输出目录（{@code target/classes}）。
   *
   * <p>不能用 {@code getResource("io/oryxos/web")} 定位：测试运行时 {@code target/test-classes} 排在 classpath
   * 前面，同一个包名会解析到测试目录，枚举结果为空 —— 一个"永远扫不到东西"的闸比没有闸更糟。这里取生产类自己的 code source，指向的一定是它被编译出来的那个目录。
   */
  private static Path classOutputRoot() {
    URL location = RequestActionResolver.class.getProtectionDomain().getCodeSource().getLocation();
    if (location == null || !"file".equals(location.getProtocol())) {
      throw new IllegalStateException("本用例需要目录形态的类输出，实际: " + location);
    }
    try {
      Path root = Path.of(location.toURI()).resolve(BASE_PACKAGE.replace('.', '/'));
      if (!Files.isDirectory(root)) {
        throw new IllegalStateException("类输出目录不存在: " + root);
      }
      return root;
    } catch (URISyntaxException e) {
      throw new IllegalStateException("类输出目录 URI 无法解析: " + location, e);
    }
  }

  private static boolean isClassFile(Path path) {
    String name = path.getFileName().toString();
    return name.endsWith(".class") && !name.contains("$");
  }

  private static String className(Path root, Path file) {
    String relative = root.relativize(file).toString().replace(java.io.File.separatorChar, '.');
    return BASE_PACKAGE + "." + relative.substring(0, relative.length() - ".class".length());
  }

  private static Class<?> loadOrNull(String className) {
    try {
      return Class.forName(className, false, RbacEndpointCoverageDriftTest.class.getClassLoader());
    } catch (ClassNotFoundException | LinkageError e) {
      // 少数类可能因可选依赖加载不了：跳过。枚举数量的下限由用例断言，所以静默清空不可能发生。
      return null;
    }
  }

  private static List<String> classPrefixes(Class<?> controller) {
    RequestMapping mapping = controller.getAnnotation(RequestMapping.class);
    if (mapping == null) {
      return List.of("");
    }
    List<String> declared = declaredPaths(mapping.value(), mapping.path());
    return declared.isEmpty() ? List.of("") : declared;
  }

  private static void addMethodEndpoints(
      List<MappedEndpoint> endpoints, String prefix, Method method) {
    GetMapping get = method.getAnnotation(GetMapping.class);
    if (get != null) {
      add(endpoints, prefix, declaredPaths(get.value(), get.path()), List.of("GET"));
    }
    PostMapping post = method.getAnnotation(PostMapping.class);
    if (post != null) {
      add(endpoints, prefix, declaredPaths(post.value(), post.path()), List.of("POST"));
    }
    PutMapping put = method.getAnnotation(PutMapping.class);
    if (put != null) {
      add(endpoints, prefix, declaredPaths(put.value(), put.path()), List.of("PUT"));
    }
    PatchMapping patch = method.getAnnotation(PatchMapping.class);
    if (patch != null) {
      add(endpoints, prefix, declaredPaths(patch.value(), patch.path()), List.of("PATCH"));
    }
    DeleteMapping delete = method.getAnnotation(DeleteMapping.class);
    if (delete != null) {
      add(endpoints, prefix, declaredPaths(delete.value(), delete.path()), List.of("DELETE"));
    }
    RequestMapping mapping = method.getAnnotation(RequestMapping.class);
    if (mapping != null) {
      List<String> methods = new ArrayList<>();
      for (RequestMethod declared : mapping.method()) {
        methods.add(declared.name());
      }
      add(
          endpoints,
          prefix,
          declaredPaths(mapping.value(), mapping.path()),
          methods.isEmpty() ? METHODS_WHEN_UNSPECIFIED : methods);
    }
  }

  private static void add(
      List<MappedEndpoint> endpoints, String prefix, List<String> paths, List<String> methods) {
    List<String> effective = paths.isEmpty() ? List.of("") : paths;
    for (String path : effective) {
      String pattern = join(prefix, path);
      for (String method : methods) {
        endpoints.add(new MappedEndpoint(method, pattern));
      }
    }
  }

  private static List<String> declaredPaths(String[] value, String[] path) {
    if (value != null && value.length > 0) {
      return List.of(value);
    }
    if (path != null && path.length > 0) {
      return List.of(path);
    }
    return List.of();
  }

  private static String join(String prefix, String path) {
    String left = prefix == null ? "" : prefix.trim();
    String right = path == null ? "" : path.trim();
    if (left.isEmpty()) {
      return right.isEmpty() ? "/" : right;
    }
    if (right.isEmpty()) {
      return left;
    }
    boolean leftSlash = left.endsWith("/");
    boolean rightSlash = right.startsWith("/");
    if (leftSlash && rightSlash) {
      return left + right.substring(1);
    }
    if (!leftSlash && !rightSlash) {
      return left + "/" + right;
    }
    return left + right;
  }
}
