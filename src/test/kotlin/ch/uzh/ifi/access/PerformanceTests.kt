package ch.uzh.ifi.access

import ch.uzh.ifi.access.aggregates.AggregateConcurrencyTests
import ch.uzh.ifi.access.aggregates.AggregateConsistencyTests
import ch.uzh.ifi.access.aggregates.AggregateEvaluationMappingTests
import ch.uzh.ifi.access.aggregates.AggregateWritePathTests
import ch.uzh.ifi.access.performance.CalculationTests
import org.junit.jupiter.api.ClassOrderer
import org.junit.jupiter.api.TestClassOrder
import org.junit.platform.suite.api.SelectClasses
import org.junit.platform.suite.api.Suite

@Suite
@SelectClasses(
    AggregateEvaluationMappingTests::class,
    AggregateWritePathTests::class,
    AggregateConcurrencyTests::class,
    AggregateConsistencyTests::class,
    CalculationTests::class,
)
@TestClassOrder(ClassOrderer.OrderAnnotation::class)
class PerformanceTests
