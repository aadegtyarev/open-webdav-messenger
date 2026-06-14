package org.openwebdav.messenger.transport

/**
 * WebDAV connection config — base URL + app-password credential + chat-root path.
 *
 * Held in memory / app-private storage only; per `docs/protocol/webdav-layout.md` §1.1 and
 * the project Security constraints, **none of these fields are ever written to the disk**.
 *
 * @param baseUrl WebDAV endpoint, e.g. `https://webdav.yandex.com` (TLS). No trailing slash required.
 * @param username Yandex login / WebDAV user.
 * @param appPassword App password for Files/WebDAV (NOT the account password) — stack-notes OkHttp/WebDAV.
 * @param chatRoot Folder the credential is scoped to (§1.1). Relative to [baseUrl]; no leading/trailing slash.
 */
data class ConnectionConfig(
    val baseUrl: String,
    val username: String,
    val appPassword: String,
    val chatRoot: String,
) {
    /** Base URL with no trailing slash, for clean path joining. */
    val normalizedBaseUrl: String = baseUrl.trimEnd('/')

    /** Chat-root with no surrounding slashes. */
    val normalizedChatRoot: String = chatRoot.trim('/')

    /**
     * Redact [appPassword] so the credential never leaks through an accidental log/toString of the
     * config (Security constraints — credentials never logged). All other fields print normally.
     */
    override fun toString(): String = "ConnectionConfig(baseUrl=$baseUrl, username=$username, appPassword=***, chatRoot=$chatRoot)"
}
