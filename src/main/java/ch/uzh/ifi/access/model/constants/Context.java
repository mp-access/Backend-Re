package ch.uzh.ifi.access.model.constants;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;

@Getter
public enum Context {
    TASK, SOLUTION, GRADING, INSTRUCTIONS;

    @JsonValue
    public String getName() {
        return StringUtils.capitalize(name().toLowerCase());
    }

    public boolean isInstructions() {
        return this.equals(INSTRUCTIONS);
    }
}
