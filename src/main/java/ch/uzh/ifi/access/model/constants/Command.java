package ch.uzh.ifi.access.model.constants;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum Command {
    RUN, TEST, GRADE;

    @JsonValue
    public String getName() {
        return name().toLowerCase();
    }

    public boolean isGraded() {
        return this.equals(GRADE);
    }
}
