package ch.uzh.ifi.access

import ch.uzh.ifi.access.service.ImportCourseTests
import ch.uzh.ifi.access.api.PublicAPITests
import ch.uzh.ifi.access.service.ImportAssignmentTests
import ch.uzh.ifi.access.service.ImportRepoTests
import org.junit.jupiter.api.ClassOrderer
import org.junit.jupiter.api.TestClassOrder
import org.junit.platform.suite.api.SelectClasses
import org.junit.platform.suite.api.Suite
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.datasource.init.ScriptUtils
import org.springframework.test.context.TestContext
import org.springframework.test.context.TestExecutionListener
import java.sql.DriverManager

@AutoConfigureMockMvc
@SpringBootTest
abstract class BaseTest

@Suite
@SelectClasses(
    ImportRepoTests::class,
    ImportCourseTests::class,
    ImportAssignmentTests::class,
    PublicAPITests::class
)
@TestClassOrder(ClassOrderer.OrderAnnotation::class)
class AllTests

class DatabaseCleanupListener : TestExecutionListener {

    @Configuration
    private class DummyConfig

    override fun beforeTestClass(testContext: TestContext) {
        // We could retrieve the application variables like this:

        /*
        val env = SpringApplicationBuilder()
            .sources(DummyConfig::class.java)
            .web(WebApplicationType.NONE)
            .build()
            .run()
            .environment

        val url = env.getProperty("spring.datasource.url")
        val user = env.getProperty("spring.datasource.username")
        val pass = env.getProperty("spring.datasource.password")
         */

        // However, not to risk production data, let's just hardcode the dev variables:

        val url = "jdbc:postgresql://localhost:5432/access"
        val user = "admin"
        val pass = "admin"
        val connection = DriverManager.getConnection(url, user, pass)
        val resource = ClassPathResource("/drop_all.sql")
        ScriptUtils.executeSqlScript(connection, resource)
        connection.close()
        println("Dropped all ACCESS tables and sequences")
    }
}
