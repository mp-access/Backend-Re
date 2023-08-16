package ch.uzh.ifi.access.model.constants;


import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum Visibility {
    HIDDEN, REGISTERED, PUBLIC;

    @JsonValue
    public String getName() {
        return name().toLowerCase();
    }

}
