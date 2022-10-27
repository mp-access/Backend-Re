package ch.uzh.ifi.access.model.dao;

import lombok.Data;
import lombok.NoArgsConstructor;

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

    public Double calculateTestsPassedRatio() {
        return (testsRun - failures.size() - errors.size()) / testsRun;
    }
}