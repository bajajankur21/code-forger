package com.codeforger.agents;

import com.codeforger.model.GeneratedCode;
import org.springframework.stereotype.Component;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Component
public class ValidatorAgent {

    public ValidationResult validate(GeneratedCode code) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new ValidationException(
                    "No system Java compiler available — run on a JDK, not a JRE");
        }

        Path tempDir;
        try {
            tempDir = Files.createTempDirectory("codeforger-validate-");
        } catch (IOException e) {
            throw new ValidationException("Could not create temp dir for validation", e);
        }

        try {
            List<Path> sourceFiles = writeSources(tempDir, code.files());
            return compile(compiler, tempDir, sourceFiles);
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private static List<Path> writeSources(Path root, Map<String, String> files) {
        List<Path> written = new ArrayList<>(files.size());
        for (Map.Entry<String, String> e : files.entrySet()) {
            Path target = root.resolve(e.getKey()).normalize();
            if (!target.startsWith(root)) {
                throw new ValidationException(
                        "Refusing to write outside temp dir: " + e.getKey());
            }
            try {
                Files.createDirectories(target.getParent());
                Files.writeString(target, e.getValue(), StandardCharsets.UTF_8);
            } catch (IOException ex) {
                throw new ValidationException(
                        "Could not write source file " + e.getKey(), ex);
            }
            written.add(target);
        }
        return written;
    }

    private static ValidationResult compile(
            JavaCompiler compiler, Path tempDir, List<Path> sourceFiles) {

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();

        try (StandardJavaFileManager fileManager =
                     compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {

            Path classOut = tempDir.resolve("__out");
            Files.createDirectories(classOut);
            fileManager.setLocationFromPaths(
                    StandardLocation.CLASS_OUTPUT, List.of(classOut));

            Iterable<? extends JavaFileObject> units =
                    fileManager.getJavaFileObjectsFromPaths(sourceFiles);

            List<String> options = List.of(
                    "-classpath", System.getProperty("java.class.path"),
                    // -proc:full runs annotation processors found on the classpath
                    // (Lombok) so generated @Data/@Builder members exist at compile time.
                    "-proc:full"
            );

            boolean ok = compiler.getTask(
                    null, fileManager, diagnostics, options, null, units).call();

            if (ok) {
                return ValidationResult.pass();
            }

            List<CompileError> errors = diagnostics.getDiagnostics().stream()
                    .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                    .map(d -> toCompileError(d, tempDir))
                    .toList();
            return ValidationResult.fail(errors);

        } catch (IOException e) {
            throw new ValidationException("Compilation IO failure", e);
        }
    }

    private static CompileError toCompileError(
            Diagnostic<? extends JavaFileObject> d, Path tempDir) {
        String file = "<unknown>";
        JavaFileObject source = d.getSource();
        if (source != null) {
            Path absolute = Path.of(source.toUri());
            file = tempDir.relativize(absolute).toString().replace('\\', '/');
        }
        int line = (int) d.getLineNumber();
        String message = d.getMessage(null);
        return new CompileError(file, line, message);
    }

    private static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
