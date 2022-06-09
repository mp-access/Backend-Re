package ch.uzh.ifi.access.model.constants;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;

import java.nio.file.Path;

@Getter
@AllArgsConstructor
public enum FilePermission {
    EDITABLE(false), READ_ONLY(false), SOLUTION(true), GRADING(true);

    private final boolean restricted;

    public static FilePermission fromPath(Path dir) {
        return switch (StringUtils.substringBefore(dir.toString(), "/")) {
            case "public" -> EDITABLE;
            case "solution" -> SOLUTION;
            case "resource" -> READ_ONLY;
            default -> GRADING;
        };
    }
}
