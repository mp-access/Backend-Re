package ch.uzh.ifi.access.service

import org.springframework.stereotype.Service
import javax.sql.DataSource

@Service
class VacuumService(
    private val dataSource: DataSource
) {

    fun vacuumAllTables() {
        dataSource.connection.use {
            it.prepareStatement("VACUUM ANALYZE").execute()
        }
    }
}

