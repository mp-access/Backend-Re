package ch.uzh.ifi.access

import ch.uzh.ifi.access.performance.CalculationTests
import org.junit.jupiter.api.ClassOrderer
import org.junit.jupiter.api.TestClassOrder
import org.junit.platform.suite.api.SelectClasses
import org.junit.platform.suite.api.Suite

@Suite
@SelectClasses(
    CalculationTests::class,
)
@TestClassOrder(ClassOrderer.OrderAnnotation::class)
class PerformanceTests
