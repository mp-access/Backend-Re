package ch.uzh.ifi.access.model.constants;

import com.google.common.base.Joiner;

import java.util.Optional;

public enum Role {
    STUDENT, ASSISTANT, SUPERVISOR;

    public String getName() {
        return name().toLowerCase();
    }

    /*TODO: this doesn't seem safe. What if someone manages to create a course "something-supervisor"
            then this person would become supervisor of the course called "something" */
    public String withCourse(String courseSlug) {
        return Joiner.on("-").skipNulls().join(courseSlug, getName());
    }

    public Optional<Role> getSubRole() {
        return this.equals(SUPERVISOR) ? Optional.of(ASSISTANT) : Optional.empty();
    }

}
