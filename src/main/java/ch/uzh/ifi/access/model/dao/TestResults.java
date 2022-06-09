package ch.uzh.ifi.access.model.dao;

import ch.uzh.ifi.access.model.constants.Extension;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.NotImplementedException;

import javax.ws.rs.NotAllowedException;
import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
public class TestResults {

    Double testsRun;

    List<List<String>> failures = new ArrayList<>();

    List<List<String>> errors = new ArrayList<>();

    List<List<String>> skipped = new ArrayList<>();

    List<List<String>> expectedFailures = new ArrayList<>();

    List<List<String>> unexpectedSuccesses = new ArrayList<>();

    public Double calculateTestsPassedRatio(Extension extension) {
        return switch (extension) {
            case PY -> (testsRun - failures.size() - errors.size()) / testsRun;
            case R -> throw new NotImplementedException();
            default -> throw new NotAllowedException("Not an executable");
        };
    }
}