package ch.uzh.ifi.access.model.constants;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

@Getter
public enum Command {
    RUN, TEST, GRADE;

    @JsonValue
    public String getName() {
        return name().toLowerCase();
    }

    public String getDisplayName() {
        return this.equals(GRADE) ? "Submission" : getName();
    }

    public boolean isGraded() {
        return this.equals(GRADE);
    }
}
