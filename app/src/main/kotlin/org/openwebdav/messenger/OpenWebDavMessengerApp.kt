package org.openwebdav.messenger

import android.app.Application

/**
 * Application entry point.
 *
 * The shipped substrates (transport, crypto, identity, message-model, sync) are backend-only — there
 * is no UI and no connection-config / roster management yet (those are later features). So the
 * Application class stays an intentionally empty host: the sync engine and its WorkManager poll are
 * exercised by tests, and are **activated** by the future config/UI feature that supplies the
 * connection config + roster and calls `SyncRunner.install(...)` + `SyncScheduler.schedule(...)`.
 * Until then `SyncWorker` runs the default no-op runner (a benign clean cycle). Later features wire
 * their dependency graph here.
 */
class OpenWebDavMessengerApp : Application()
