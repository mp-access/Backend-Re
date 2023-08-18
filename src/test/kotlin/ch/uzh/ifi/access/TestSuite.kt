package ch.uzh.ifi.access

import ch.uzh.ifi.access.config.MapperConfig
import ch.uzh.ifi.access.config.SecurityConfig
import ch.uzh.ifi.access.service.*
import jakarta.xml.bind.annotation.XmlElementDecl.GLOBAL
import org.apache.catalina.core.ApplicationContext
import org.junit.jupiter.api.ClassOrderer
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.TestClassOrder
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.ExtensionContext.Store.CloseableResource
import org.junit.platform.suite.api.SelectClasses
import org.junit.platform.suite.api.SelectPackages
import org.junit.platform.suite.api.Suite
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.web.server.ResponseStatusException
import java.io.File
import java.nio.file.Paths

@AutoConfigureMockMvc
@SpringBootTest
abstract class BaseTest

@Suite
@SelectClasses(CourseLifecycleTests::class, PublicAPITests::class)
@TestClassOrder(ClassOrderer.OrderAnnotation::class)
class AllTests