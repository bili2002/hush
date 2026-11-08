package com.hush.server

/**
 * Everything the server reads from its environment, as one value so tests can inject it
 * (`module(ServerConfig(token = ..., dbPath = ":memory:"))`) and run "token unset" and "token set"
 * in the same JVM. [ServerConfig.fromEnv] is the ONLY place `System.getenv` is consulted.
 *
 * @property token the shared bearer secret (`HUSH_TOKEN`); `null` means "not configured", and the
 *   gated routes fail CLOSED with 503. A blank value is treated as unset, never as an empty password.
 * @property dbPath SQLite file for the archive (`HUSH_DB_PATH`), or `:memory:` for tests.
 */
data class ServerConfig(val token: String?, val dbPath: String) {
    companion object {
        const val ENV_TOKEN = "HUSH_TOKEN"
        const val ENV_DB_PATH = "HUSH_DB_PATH"

        /** Default DB location outside Docker: the same host data dir deploy-server.sh bind-mounts. */
        fun defaultDbPath(): String = System.getProperty("user.home") + "/.local/share/hush-server/hush.db"

        fun fromEnv(): ServerConfig = ServerConfig(
            token = System.getenv(ENV_TOKEN)?.takeUnless { it.isBlank() },
            dbPath = System.getenv(ENV_DB_PATH)?.takeUnless { it.isBlank() } ?: defaultDbPath(),
        )
    }
}
