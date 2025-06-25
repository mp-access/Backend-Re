package ch.uzh.ifi.access.performance

import ch.uzh.ifi.access.AccessTestExecutionCondition
import ch.uzh.ifi.access.AccessUser
import ch.uzh.ifi.access.BaseTest
import io.github.oshai.kotlinlogging.KotlinLogging
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.extension.ConditionEvaluationResult
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import java.util.concurrent.TimeUnit


@ExtendWith(DumpAvailabilityCondition::class)
annotation class SkipWhenDBNotReady

class DumpAvailabilityCondition : AccessTestExecutionCondition() {

    override fun evaluateExecutionCondition(context: ExtensionContext): ConditionEvaluationResult {
        val cwd = "scripts/dumpImport/"
        val ready = runCommand("./is_ready.bash", cwd).trim()
        return if (ready == "ready") {
            ConditionEvaluationResult.enabled("DB ready for performance testing")
        } else {
            ConditionEvaluationResult.disabled("DB not ready for performance testing - skipping tests")
        }
    }

}

@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SkipWhenDBNotReady
class CalculationTests(@Autowired val mvc: MockMvc) : BaseTest() {

    private val logger = KotlinLogging.logger {}

    @Test
    @Order(1)
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    fun `Can retrieve participants`() {
        mvc.perform(
            get("/courses/info1-hs24/participants")
                .contentType("application/json")
                .header("X-API-Key", "1234")
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(content().contentType("application/json"))
            .andExpect(jsonPath("$.[?(@.email =~ /philip.*?/i)].email", `is`(not(empty<Any>()))))
    }

    @Test
    @Order(1)
    //@Timeout(value = 1, unit = TimeUnit.SECONDS)
    @AccessUser(
        username = "assistant@uzh.ch",
        authorities = ["assistant", "info1-hs24-assistant", "info1-hs24"]
    )
    fun `Can retrieve participants with points`() {
        mvc.perform(
            get("/courses/info1-hs24/points")
                .contentType("application/json")
        )
            .andExpect(status().isOk)
            .andExpect(content().contentType("application/json"))
            .andExpect(jsonPath("$.[?(@.email =~ /philip.*?/i)].email", `is`(not(empty<Any>()))))
    }


}
