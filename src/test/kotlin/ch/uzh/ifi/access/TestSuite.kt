package ch.uzh.ifi.access

import ch.uzh.ifi.access.api.AfterSubmissionTests
import ch.uzh.ifi.access.api.ManagementAPITests
import ch.uzh.ifi.access.api.SubmissionTests
import ch.uzh.ifi.access.import.ImportAssignmentTests
import ch.uzh.ifi.access.import.ImportCourseTests
import ch.uzh.ifi.access.import.ImportRepoTests
import ch.uzh.ifi.access.import.ImportTaskTests
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.ClassOrderer
import org.junit.jupiter.api.TestClassOrder
import org.junit.jupiter.api.extension.AfterTestExecutionCallback
import org.junit.jupiter.api.extension.ExecutionCondition
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.platform.suite.api.SelectClasses
import org.junit.platform.suite.api.Suite
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContext
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.userdetails.User
import org.springframework.security.test.context.support.WithSecurityContext
import org.springframework.security.test.context.support.WithSecurityContextFactory
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.ResultHandler
import java.io.File
import java.util.concurrent.TimeUnit

@Suite
@SelectClasses(
    ImportRepoTests::class,
    ImportCourseTests::class,
    ImportAssignmentTests::class,
    ImportTaskTests::class,
    ManagementAPITests::class,
    SubmissionTests::class,
    AfterSubmissionTests::class,
)

@TestClassOrder(ClassOrderer.OrderAnnotation::class)
class AllTests

@AutoConfigureMockMvc
@SpringBootTest
abstract class BaseTest {

    private val logger = KotlinLogging.logger {}

    companion object {
        private var mvcResult: MvcResult? = null
    }

    class CurlCommandListener : AfterTestExecutionCallback {
        override fun afterTestExecution(context: ExtensionContext?) {
            val methodName = context?.requiredTestMethod?.name ?: "'Unknown'"
            println("Curl command for test method '$methodName':")
            val result = mvcResult
            if (result != null) {
                val request = result.request
                val method = request.method
                val uri = request.requestURI
                val headers = request.headerNames.toList().joinToString(" ") { "-H '$it: ${request.getHeader(it)}'" }
                val payload = request.contentAsString
                if (payload != "null") {
                    print("curl -X $method 'http://localhost:3000/api$uri' $headers --data '$payload'")
                } else {
                    print("curl -X $method 'http://localhost:3000/api$uri' $headers")
                }
            } else {
                println("No mvcResult available for test method: '$methodName'")
            }
            mvcResult = null
        }
    }

    val logResponse: ResultHandler = ResultHandler {
        mvcResult = it
        logger.info { it.response.contentAsString }
    }
}

object JsonReference {
    fun get(filename: String): String {
        return JsonReference::class.java.classLoader.getResource("publicAPIJson/$filename.json")?.readText()!!
    }
}

abstract class AccessTestExecutionCondition : ExecutionCondition {

    private val logger = KotlinLogging.logger {}

    // this function CC-BY-SA 4.0 https://stackoverflow.com/a/52441962
    fun runCommand(
        command: String,
        workingDir: String,
    ): String = runCatching {
        ProcessBuilder("\\s".toRegex().split(command))
            .directory(File(workingDir))
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start().also { it.waitFor(60, TimeUnit.SECONDS) }
            .inputStream.bufferedReader().readText()
    }.onFailure { logger.error { it.printStackTrace() } }.getOrElse { "" }
}

class AccessUserSecurityContextFactory : WithSecurityContextFactory<AccessUser> {
    override fun createSecurityContext(accessUser: AccessUser): SecurityContext {
        val context = SecurityContextHolder.createEmptyContext()

        val authoritiesSet = accessUser.authorities.toMutableSet()

        accessUser.authorities.forEach { authority ->
            if (authority.endsWith("supervisor")) {
                val prefix = authority.removeSuffix("supervisor")
                authoritiesSet.add("${prefix}assistant")
            }
            if (authority == "supervisor") {
                authoritiesSet.add("assistant")
            }
        }

        val authorities = authoritiesSet.map { SimpleGrantedAuthority(it) }
        val principal = User(accessUser.username, "password", authorities)

        val authentication = UsernamePasswordAuthenticationToken(principal, principal.password, principal.authorities)
        context.authentication = authentication

        return context
    }
}

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.TYPE)
@Retention(AnnotationRetention.RUNTIME)
@WithSecurityContext(factory = AccessUserSecurityContextFactory::class)
annotation class AccessUser(
    val username: String = "user",
    val authorities: Array<String> = [],
)
