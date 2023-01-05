package ch.uzh.ifi.access.model.dto;

import ch.uzh.ifi.access.model.constants.Command;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
public class SubmissionDTO {
    boolean restricted = true;
    String userId;
    Command command;
    List<SubmissionFileDTO> files = new ArrayList<>();
}
