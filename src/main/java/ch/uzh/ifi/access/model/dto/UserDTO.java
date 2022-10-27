package ch.uzh.ifi.access.model.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class UserDTO {
    String userId;
    Long taskId;
    Integer addAttempts;
}
