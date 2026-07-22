package com.siamakerlab.vibecoder.server.config

import com.charleskorn.kaml.Yaml
import io.kotest.matchers.shouldBe
import org.junit.Test

class ServerConfigIosTest {
    @Test
    fun `bundled server config contains ios agent defaults`() {
        val text = requireNotNull(
            Thread.currentThread().contextClassLoader.getResource("config/server.yml")
        ).readText()
        val config = Yaml.default.decodeFromString(ServerConfig.serializer(), text)

        config.ios.agent.enabled shouldBe false
        config.ios.agent.mode shouldBe "local"
        config.ios.agent.port shouldBe 22
        config.ios.agent.xcodePath shouldBe "auto"
    }
}
