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
    R("R", "r-base:latest"),
    TXT("text/plain", null),
    JSON("application/json", null),
    SH("application/x-sh", null),
    CSV("text/csv", null),
    PNG("image/png", null),
    JPG("image/jpg", null);

    private final String language;

    private final String dockerImage;

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
            default -> throw new NotImplementedException();
        };
    }

    public String formTestCommand() {
        return switch (this) {
            case PY -> "python -m unittest discover public";
            default -> throw new NotImplementedException();
        };
    }

    public String parseErrorMessage(String stdOut) {
        if (Strings.isBlank(stdOut))
            return "No output - did you add a top-level statement or \"if __name__ == '__main__'\"?";
        return switch (this) {
            case PY -> Optional.ofNullable(StringUtils.substringBetween(stdOut, "@@")).filter(Strings::isNotBlank)
                    .orElseGet(() -> Lists.reverse(stdOut.lines().toList()).stream().filter(line -> line.contains(": ")).findFirst()
                            .orElse("Crashed while running tests - make sure all your files contain valid syntax"));
            default -> stdOut;
        };
    }

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
}
