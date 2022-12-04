package ch.uzh.ifi.access.model.constants;

import com.google.common.base.Joiner;

public enum Role {
    STUDENT, ASSISTANT, SUPERVISOR;

    public String getName() {
        return name().toLowerCase();
    }

    public String withCourseURL(String courseURL) {
        return Joiner.on("-").skipNulls().join(courseURL, getName());
    }

}
