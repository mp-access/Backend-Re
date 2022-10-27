package ch.uzh.ifi.access.model.dto;

import ch.uzh.ifi.access.model.constants.SubmissionType;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
public class SubmissionDTO {

    Long taskId;

    Long currentFileId;

    String answer;

    SubmissionType type;

    List<SubmissionFileDTO> files;
}
