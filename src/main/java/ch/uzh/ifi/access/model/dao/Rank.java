package ch.uzh.ifi.access.model.dao;

import lombok.Data;

@Data
public class Rank {

    Long evaluationId;
    String email;
    Double score;

    public Rank(String email, Double avgScore, Double avgAttemptsCount) {
        this.email = email;
        this.score = avgScore - (avgAttemptsCount / 10.0);
    }
}
