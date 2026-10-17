package io.oryxos.web.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 出网/入网的类型必须是 MVC 转换器认的那套 JSON 实现。
 *
 * <p>Boot 4 / Spring 7 的 MVC JSON 转换器是 Jackson 3（{@code tools.jackson}）。把 Jackson 2（{@code
 * com.fasterxml.jackson.databind}）的树放到签名上不会有编译错误，只有两种运行期后果：
 *
 * <ul>
 *   <li>{@code @RequestBody} 侧：转换器构造不出抽象类型 ⇒ 请求一律 500（#980：A2A 的 JSON-RPC 端点整个不可用）；
 *   <li>{@code @ResponseBody} 侧：节点被当成 POJO 序列化 ⇒ 200，但载荷变成 {@code
 *       {"array":false,…,"nodeType":"OBJECT"}}， 内容静默全丢（#981：eval 导出与 run events）。
 * </ul>
 *
 * <p>那三个缺陷都是事后补测试才发现的。这条漂移闸把"别再放进签名"变成 CI 的事：扫 {@code io.oryxos.web.controller} 下所有控制器与 DTO
 * 的**签名**（返回值、{@code @RequestBody} 参数、record 组件，含泛型 实参），出现 Jackson 2 的 databind 类型即失败。
 *
 * <p>只扫签名：类内部的局部变量、私有字段与私有方法不管 —— core 仍用 Jackson 2，在边界上解析一次字符串是允许的 （#980 的 A2A 控制器就是这么做的）。
 */
class WireTypesAreJackson3Test {

  private static final String BASE_PACKAGE = "io.oryxos.web.controller";
  private static final String JACKSON2_TREE = "com.fasterxml.jackson.databind";

  @Test
  @DisplayName("控制器与 DTO 的签名里没有 Jackson 2 的树")
  void wireSignaturesUseTheMapperTheConverterUses() {
    List<Class<?>> scanned = webTypes();

    // 扫描本身要有效：什么都没扫到的用例永远不会红
    assertThat(scanned).hasSizeGreaterThan(30);

    List<String> violations = new ArrayList<>();
    for (Class<?> type : scanned) {
      collectRecordComponents(type, violations);
      collectControllerSignatures(type, violations);
    }

    assertThat(violations)
        .as("Jackson 2 的树放在签名上不会编译失败：@RequestBody 侧请求一律 500，@ResponseBody 侧 200 但载荷被序列化成属性转储")
        .isEmpty();
  }

  private static void collectRecordComponents(Class<?> type, List<String> violations) {
    RecordComponent[] components = type.getRecordComponents();
    if (components == null) {
      return;
    }
    for (RecordComponent component : components) {
      violations.addAll(findJackson2Tree(type, component.getName(), component.getGenericType()));
    }
  }

  private static void collectControllerSignatures(Class<?> type, List<String> violations) {
    if (!type.isAnnotationPresent(RestController.class)) {
      return;
    }
    for (Method method : type.getDeclaredMethods()) {
      if (!java.lang.reflect.Modifier.isPublic(method.getModifiers())) {
        continue;
      }
      violations.addAll(
          findJackson2Tree(type, method.getName() + " 的返回值", method.getGenericReturnType()));
      Type[] parameterTypes = method.getGenericParameterTypes();
      java.lang.annotation.Annotation[][] annotations = method.getParameterAnnotations();
      for (int i = 0; i < parameterTypes.length; i++) {
        if (hasRequestBody(annotations[i])) {
          violations.addAll(
              findJackson2Tree(type, method.getName() + " 的 @RequestBody", parameterTypes[i]));
        }
      }
    }
  }

  private static boolean hasRequestBody(java.lang.annotation.Annotation[] annotations) {
    for (java.lang.annotation.Annotation annotation : annotations) {
      if (annotation instanceof RequestBody) {
        return true;
      }
    }
    return false;
  }

  /** 递归找类型里的 Jackson 2 databind 引用：泛型实参（{@code ApiResponse<JsonNode>}）、数组、通配符上界。 */
  private static List<String> findJackson2Tree(Class<?> owner, String where, Type type) {
    List<String> violations = new ArrayList<>();
    walk(owner, where, type, violations);
    return violations;
  }

  private static void walk(Class<?> owner, String where, Type type, List<String> violations) {
    if (type instanceof Class<?> clazz) {
      if (clazz.isArray()) {
        walk(owner, where, clazz.getComponentType(), violations);
        return;
      }
      if (clazz.getName().startsWith(JACKSON2_TREE)) {
        violations.add(owner.getName() + " " + where + " → " + clazz.getName());
      }
      return;
    }
    if (type instanceof ParameterizedType parameterized) {
      walk(owner, where, parameterized.getRawType(), violations);
      for (Type argument : parameterized.getActualTypeArguments()) {
        walk(owner, where, argument, violations);
      }
      return;
    }
    if (type instanceof WildcardType wildcard) {
      for (Type bound : wildcard.getUpperBounds()) {
        walk(owner, where, bound, violations);
      }
    }
  }

  /** 编译产物里的类：用生产类的 code source 定位，避免命中 target/test-classes。 */
  private static List<Class<?>> webTypes() {
    Path root = classOutputRoot();
    List<Class<?>> types = new ArrayList<>();
    try (Stream<Path> files = Files.walk(root)) {
      for (Path file : files.filter(WireTypesAreJackson3Test::isClassFile).toList()) {
        Class<?> type = loadOrNull(className(root, file));
        if (type != null) {
          types.add(type);
        }
      }
    } catch (IOException e) {
      throw new IllegalStateException("枚举 web 类失败: " + root, e);
    }
    types.sort(java.util.Comparator.comparing(Class::getName));
    return types;
  }

  private static Path classOutputRoot() {
    URL location = AuditApiController.class.getProtectionDomain().getCodeSource().getLocation();
    if (location == null || !"file".equals(location.getProtocol())) {
      throw new IllegalStateException("本用例需要目录形态的类输出，实际: " + location);
    }
    try {
      return Path.of(location.toURI()).resolve(BASE_PACKAGE.replace('.', '/'));
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
      return Class.forName(className, false, WireTypesAreJackson3Test.class.getClassLoader());
    } catch (ClassNotFoundException | LinkageError e) {
      return null;
    }
  }
}
