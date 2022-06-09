package ch.uzh.ifi.access.model.constants;

public enum Role {
    STUDENT, ASSISTANT, SUPERVISOR;

    public String getName() {
        return name().toLowerCase();
    }

    public String withCourseURL(String courseURL) {
        return String.join("-", courseURL, getName());
    }

}
