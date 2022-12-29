package ch.uzh.ifi.access.model.constants;

public enum Context {
    TASK, SOLUTION, GRADING, INSTRUCTIONS;

    public boolean isInstructions() {
        return this.equals(INSTRUCTIONS);
    }
}
