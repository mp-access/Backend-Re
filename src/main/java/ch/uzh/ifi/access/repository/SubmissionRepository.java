package ch.uzh.ifi.access.repository;

import ch.uzh.ifi.access.model.Submission;
import ch.uzh.ifi.access.model.constants.SubmissionType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SubmissionRepository extends JpaRepository<Submission, Long> {

    Optional<Submission> findFirstByTask_IdAndUserIdAndPointsNotNullOrderByPointsDesc(Long taskId, String userId);

    List<Submission> findByTask_IdAndUserIdOrderByCreatedAtDesc(Long taskId, String userId);

    Integer countByTask_IdAndUserIdAndTypeAndValidTrue(Long taskId, String userId, SubmissionType type);
}