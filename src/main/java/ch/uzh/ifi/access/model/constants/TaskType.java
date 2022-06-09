package ch.uzh.ifi.access.model.constants;

import com.fasterxml.jackson.annotation.JsonValue;
import com.google.common.base.CaseFormat;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum TaskType {
    CODE, CODE_SNIPPET, TEXT, SINGLE_CHOICE, MULTIPLE_CHOICE;

    @JsonValue
    public String getName() {
        return CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, name());
    }
}
