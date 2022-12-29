package ch.uzh.ifi.access.model.dto;

import ch.uzh.ifi.access.model.constants.Command;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class SubmissionDTO {
    boolean restricted = true;
    Long taskId;
    String userId;
    Command command;
    List<SubmissionFileDTO> files = new ArrayList<>();
}
