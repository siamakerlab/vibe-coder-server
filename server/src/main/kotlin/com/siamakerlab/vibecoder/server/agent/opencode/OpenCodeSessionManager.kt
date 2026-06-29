package com.siamakerlab.vibecoder.server.agent.opencode

import com.siamakerlab.vibecoder.server.agent.AgentProvider
import com.siamakerlab.vibecoder.server.agent.UnsupportedAgentSessionManager
import com.siamakerlab.vibecoder.server.ws.LogHub

class OpenCodeSessionManager(hub: LogHub) : UnsupportedAgentSessionManager(AgentProvider.OPENCODE, hub)

