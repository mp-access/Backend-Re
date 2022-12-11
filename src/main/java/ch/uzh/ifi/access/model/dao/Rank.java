package ch.uzh.ifi.access.model.dao;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class Rank {
    String email;
    Double score;
}
