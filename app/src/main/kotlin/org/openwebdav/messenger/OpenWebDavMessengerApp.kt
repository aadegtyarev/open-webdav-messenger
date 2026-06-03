package org.openwebdav.messenger

import android.app.Application

/**
 * Application entry point.
 *
 * This feature ships only the transport + protocol substrate (no UI, crypto, or sync loop),
 * so the Application class is an intentionally empty host. Later features wire their
 * dependency graph here.
 */
class OpenWebDavMessengerApp : Application()
