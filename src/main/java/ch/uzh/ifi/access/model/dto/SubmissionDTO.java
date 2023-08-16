package ch.uzh.ifi.access.model.dto;

import ch.uzh.ifi.access.model.constants.Command;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
public class SubmissionDTO {
    public boolean restricted = true;
    public String userId;
    public Command command;
    public List<SubmissionFileDTO> files = new ArrayList<>();
}