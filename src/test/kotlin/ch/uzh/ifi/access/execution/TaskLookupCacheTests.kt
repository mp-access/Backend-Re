package ch.uzh.ifi.access.execution

import ch.uzh.ifi.access.BaseTest
import ch.uzh.ifi.access.service.TaskLookupService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.cache.CacheManager

/**
 * Verifies that the @Cacheable proxy on TaskLookupService actually engages: after resolving a slug
 * triple once, the resolved id is present in the cache. If resolveTaskId were ever folded back into
 * SubmissionService (a self-call), the caching proxy would be bypassed and the cache would stay EMPTY
 * after the call -- so an empty cache here is exactly the self-invocation regression this guards against.
 *
 * Checked through the CacheManager rather than a repository spy on purpose: JDK 21 + springmockk cannot
 * build a @SpykBean for a Spring Data repository proxy (IllegalAccessException), and this needs no mock.
 *
 * Relies on the mock course imported by the import test classes, so it must run after them in the suite.
 */
class TaskLookupCacheTests(
    @Autowired val taskLookupService: TaskLookupService,
    @Autowired val cacheManager: CacheManager,
) : BaseTest() {

    @Test
    fun `resolving a slug engages the cache and repeats return the same id`() {
        val course = "access-mock-course"
        val assignment = "classes"
        val task = "carpark-multiple-inheritance"
        val cacheKey = "$course/$assignment/$task"
        val cache = cacheManager.getCache("TaskLookupService.resolveTaskId")!!

        // Start from a clean slot so we can prove THIS call is what populated it.
        cache.evict(cacheKey)

        val id1 = taskLookupService.resolveTaskId(course, assignment, task)
        assertNotNull(id1, "the mock task should resolve to a real id (is the mock course imported?)")

        // The @Cacheable proxy engaged: the resolved id was written to the cache. A self-invocation
        // bug would leave this null because the proxy would never run -- the footgun this test guards.
        val cached = cache.get(cacheKey)?.get()
        assertNotNull(cached, "resolveTaskId was not cached -> the @Cacheable proxy was bypassed")
        assertEquals(id1, cached, "cached value must equal the resolved id")

        // A repeat resolution returns the same id.
        assertEquals(id1, taskLookupService.resolveTaskId(course, assignment, task))
    }
}
