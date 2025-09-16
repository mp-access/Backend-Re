package ch.uzh.ifi.access

import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling


@SpringBootApplication
@EnableScheduling
class AccessApplication {
    fun main(args: Array<String>) {
        SpringApplication.run(AccessApplication::class.java, *args)
    }
}

fun main(args: Array<String>) {
    runApplication<AccessApplication>(*args)
}
