package ch.uzh.ifi.access.model.constants;

import com.google.common.base.Joiner;

import java.util.Optional;

public enum Role {
    STUDENT, ASSISTANT, SUPERVISOR;

    public String getName() {
        return name().toLowerCase();
    }

    public String withCourse(String courseURL) {
        return Joiner.on("-").skipNulls().join(courseURL, getName());
    }

    public Optional<Role> getSubRole() {
        return this.equals(SUPERVISOR) ? Optional.of(ASSISTANT) : Optional.empty();
    }

}
