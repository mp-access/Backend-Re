package ch.uzh.ifi.access.model.dto;

import ch.uzh.ifi.access.model.constants.SubmissionType;
import lombok.Data;

import java.util.List;

@Data
public class SubmissionDTO {
    boolean restricted = true;
    Long taskId;
    String userId;
    SubmissionType type;
    List<SubmissionFileDTO> files;
}
