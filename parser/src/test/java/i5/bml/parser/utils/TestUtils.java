package i5.bml.parser.utils;

import i5.bml.parser.Parser;
import i5.bml.parser.errors.SyntaxErrorListener;
import i5.bml.parser.walker.DiagnosticsCollector;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.junit.jupiter.api.Assertions;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class TestUtils {

    public static String readFileIntoString(String fileName) {
        var inputString = "";
        try {
            var inputResource = Objects.requireNonNull(TestUtils.class.getClassLoader().getResource(fileName));
            inputString = Files.readString(Paths.get(inputResource.toURI()));
        } catch (IOException | URISyntaxException e) {
            throw new RuntimeException(e);
        }

        return inputString;
    }

    public static List<Diagnostic> collectSyntaxErrors(String fileName) {
        var diagnosticsCollector = new DiagnosticsCollector();
        var syntaxErrorListener = new SyntaxErrorListener();
        var pair = Parser.parse(TestUtils.readFileIntoString(fileName));
        pair.getRight().removeErrorListeners();
        pair.getRight().addErrorListener(syntaxErrorListener);
        new ParseTreeWalker().walk(diagnosticsCollector, pair.getRight().program());
        return syntaxErrorListener.getCollectedSyntaxErrors();
    }

    public static void assertNoErrors(String relativeFilePath, List<String> expectedErrors) {
        var pair = Parser.parse(TestUtils.readFileIntoString(relativeFilePath));
        var diagnosticsCollector = new DiagnosticsCollector();
        new ParseTreeWalker().walk(diagnosticsCollector, pair.getRight().program());

        var diagnostics = diagnosticsCollector.getCollectedDiagnostics();
        expectedErrors.forEach(e -> {
            Assertions.assertTrue(diagnostics.stream().anyMatch(d -> d.getMessage().equals(e)),
                    () -> "Expected error: %s".formatted(e));
        });
        diagnostics.removeIf(d -> expectedErrors.contains(d.getMessage())
                || d.getSeverity() != DiagnosticSeverity.Error);

        Assertions.assertTrue(diagnostics.isEmpty(), () -> "Found diagnostics:\n%s".formatted(TestUtils.prettyPrintDiagnostics(diagnostics)));
    }

    public static String prettyPrintDiagnostics(List<Diagnostic> diagnostics) {
        return diagnostics.stream()
                .map(d -> "line=%d: %s".formatted(d.getRange().getStart().getLine(), d.getMessage()))
                .collect(Collectors.joining("\n"));
    }
}
