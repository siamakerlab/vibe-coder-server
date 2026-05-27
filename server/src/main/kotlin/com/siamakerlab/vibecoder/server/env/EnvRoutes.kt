package com.siamakerlab.vibecoder.server.env

import com.siamakerlab.vibecoder.server.auth.AUTH_BEARER
import com.siamakerlab.vibecoder.shared.ApiPath
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post

fun Routing.envRoutes(status: StatusService, env: EnvDiagnostics) {
    authenticate(AUTH_BEARER) {
        get(ApiPath.SERVER_STATUS) { call.respond(status.snapshot()) }
        get(ApiPath.SERVER_ENVIRONMENT) { call.respond(env.run()) }
        post(ApiPath.SERVER_ENVIRONMENT_CHECK) { call.respond(env.run()) }
    }
}
