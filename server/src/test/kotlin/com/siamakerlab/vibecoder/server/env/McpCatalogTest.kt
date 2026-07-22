package com.siamakerlab.vibecoder.server.env

import com.siamakerlab.vibecoder.server.platform.PlatformEngineRegistry
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.Test

class McpCatalogTest {
    @Test
    fun `default MCP install set stays limited to no-key no-cost entries`() {
        McpCatalog.defaultInstallIds.toSet() shouldBe PlatformEngineRegistry.default.commonDefaultMcpIds()
        McpCatalog.defaultInstallIds shouldNotContain "git"
        McpCatalog.defaultInstallIds shouldNotContain "playwright"
        McpCatalog.defaultInstallIds shouldNotContain "mobile-mcp"
        McpCatalog.defaultInstallIds.shouldContainExactlyInAnyOrder(
            listOf("memory", "sequentialthinking", "context7", "time"),
        )

        McpCatalog.all
            .filter { it.id in McpCatalog.defaultInstallIds }
            .flatMap { it.configFields }
            .any { it.isSecret } shouldBe false
    }

    @Test
    fun `platform tooling MCP ids exist in catalog`() {
        PlatformEngineRegistry.default.allToolingMcpIds().forEach { id ->
            McpCatalog.byId.keys shouldContain id
        }
    }
}
