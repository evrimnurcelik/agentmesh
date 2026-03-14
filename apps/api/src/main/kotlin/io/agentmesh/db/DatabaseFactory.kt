package io.agentmesh.db

import com.typesafe.config.ConfigFactory
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction

object DatabaseFactory {

    fun init() {
        val config = ConfigFactory.load()
        val hikariConfig = HikariConfig().apply {
            jdbcUrl         = config.getString("database.url")
            username        = config.getString("database.user")
            password        = config.getString("database.password")
            driverClassName = "org.postgresql.Driver"
            maximumPoolSize = config.getInt("database.poolSize")
            isAutoCommit    = false
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
            validate()
        }
        val dataSource = HikariDataSource(hikariConfig)
        Database.connect(dataSource)

        transaction {
            SchemaUtils.createMissingTablesAndColumns(
                Capabilities, Agents, Matches, Delegations,
                Teams, TeamMembers,                            // v0.3
                BillingRates, BillingTransactions             // v0.3
            )
        }
    }

    suspend fun <T> query(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }
}
