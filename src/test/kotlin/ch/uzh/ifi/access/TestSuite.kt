package ch.uzh.ifi.access

import ch.uzh.ifi.access.config.SecurityConfigurer
import org.junit.platform.suite.api.SelectPackages
import org.junit.platform.suite.api.Suite
import org.springframework.test.context.ContextConfiguration

@Suite
@SelectPackages("ch.uzh.ifi.access.service")
@ContextConfiguration(classes = [SecurityConfigurer::class])
class AllTests