package ch.uzh.ifi.access.model.constants;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;

@Getter
@AllArgsConstructor
public enum SubmissionType {
    RUN, TEST, GRADE;

    @JsonValue
    public String getName() {
        return name().toLowerCase();
    }

    public boolean isGraded() {
        return this.equals(GRADE);
    }

    private String getDisplayName() {
        return switch (this) {
            case RUN, TEST -> StringUtils.capitalize(getName());
            case GRADE -> "Submission";
        };
    }

    public String formatName(Long ordinalNum) {
        return getDisplayName() + " " + ordinalNum.toString();
    }
}
