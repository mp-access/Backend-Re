package ch.uzh.ifi.access.repository;

import ch.uzh.ifi.access.model.SubmissionFile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SubmissionFileRepository extends JpaRepository<SubmissionFile, Long> {
    Optional<SubmissionFile> findTopByTaskFile_IdAndSubmission_UserIdOrderByIdDesc(Long taskFileId, String userId);
}