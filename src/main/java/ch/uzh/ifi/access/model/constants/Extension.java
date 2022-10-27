package ch.uzh.ifi.access.model.constants;

import com.fasterxml.jackson.annotation.JsonValue;
import com.google.common.collect.Lists;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.NotImplementedException;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.util.Strings;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;

@Getter
@AllArgsConstructor
public enum Extension {
    PY("python", "python:latest"),
    R("r", "r-base:latest"),
    TXT("text/plain", null),
    JSON("application/json", null),
    SH("application/x-sh", null),
    CSV("text/csv", null),
    PNG("image/png", null),
    JPG("image/jpg", null);

    private final String language;

    private final String dockerImage;

    public static String[] listSupported() {
        return Arrays.stream(values()).map(Extension::getName).toList().toArray(new String[]{});
    }

    public static Extension fromPath(Path file) {
        String targetExtension = FilenameUtils.getExtension(file.getFileName().toString());
        return Arrays.stream(values()).filter(extension -> extension.getName().equals(targetExtension)).findFirst().orElse(null);
    }

    public static Extension fromLanguage(String language) {
        return Arrays.stream(values()).filter(extension -> extension.getLanguage().equals(language)).findFirst().orElse(null);
    }

    @JsonValue
    public String getName() {
        return name().toLowerCase();
    }

    public boolean isCode() {
        return Strings.isNotBlank(dockerImage);
    }

    public boolean isImage() {
        return StringUtils.startsWith(language, "image");
    }

    public String formRunCommand(String path) {
        return switch (this) {
            case PY -> "python -m " + path.replace(".py", "").replace("/", ".");
            case R -> "Rscript " + path;
            default -> throw new NotImplementedException();
        };
    }

    public String formTestCommand() {
        return switch (this) {
            case PY -> "python -m unittest discover public -v";
            case R -> "Rscript";
            default -> throw new NotImplementedException();
        };
    }

    public String formGradingCommand(String scriptFilename) {
        return switch (this) {
            case PY -> "python " + scriptFilename;
            case R -> "Rscript " + scriptFilename;
            default -> throw new NotImplementedException();
        };
    }

    public String getDefaultGradingScript() {
        return switch (this) {
            case PY -> """
                    from unittest import TestCase, TextTestRunner, TestLoader
                    import json

                    with open('test_results.json', 'w') as test_results_file:
                        test_results = TextTestRunner(verbosity=2).run(TestLoader().discover(start_dir='private'))
                        json.dump(vars(test_results), test_results_file, default=lambda o: None)
                    """;
            case R -> """
                    jpeg(file="plot.jpeg")
                    garbage <- dev.off()
                    """;
            default -> throw new NotImplementedException();
        };
    }

    public String parseErrorMessage(String logs) {
        if (Strings.isBlank(logs))
            return null;
        return switch (this) {
            case PY -> Optional.ofNullable(StringUtils.substringBetween(logs, "@@")).filter(Strings::isNotBlank)
                    .orElseGet(() -> Lists.reverse(logs.lines().toList()).stream().filter(line -> line.contains(": ")).findFirst()
                            .orElse("Crashed while running tests - make sure all your files contain valid syntax"));
            default -> logs;
        };
    }
}
