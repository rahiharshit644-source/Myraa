package com.soltini.app.reach

import com.soltini.app.reach.backends.ReachBackend
import com.soltini.app.reach.backends.WebReaderBackend
import com.soltini.app.reach.gateway.BackendRouter
import com.soltini.app.reach.gateway.CapabilityRegistry
import com.soltini.app.reach.model.HealthStatus
import com.soltini.app.reach.model.ResearchPlatform
import com.soltini.app.reach.model.ResearchSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BackendRouterTest {

    private class FailingReaderBackend(override val id: String) : WebReaderBackend {
        override val name = "Failing Backend"
        override val description = "Always fails"
        override suspend fun probe(): Pair<HealthStatus, String> = HealthStatus.ERROR to "Broken"
        override suspend fun read(url: String): ResearchSource {
            throw RuntimeException("Simulated upstream failure in $id")
        }
    }

    private class WorkingReaderBackend(override val id: String) : WebReaderBackend {
        override val name = "Working Fallback Backend"
        override val description = "Succeeds"
        override suspend fun probe(): Pair<HealthStatus, String> = HealthStatus.READY to "Ready"
        override suspend fun read(url: String): ResearchSource {
            return ResearchSource(
                title = "Fallback Content",
                url = url,
                platform = ResearchPlatform.WEB_READER,
                snippet = "Retrieved via fallback",
                backendUsed = id
            )
        }
    }

    @Test
    fun testFallbackChainExecutesWhenPrimaryFails() = runBlocking {
        val registry = CapabilityRegistry()
        val router = BackendRouter(registry)

        val failingPrimary = FailingReaderBackend("jina_reader")
        val workingFallback = WorkingReaderBackend("direct_http_reader")

        router.registerBackend(failingPrimary)
        router.registerBackend(workingFallback)

        val (result, activeBackend) = router.executeWebRead("https://example.com/test")

        assertNotNull(result)
        assertEquals("Fallback Content", result.title)
        assertEquals("direct_http_reader", activeBackend)
        assertEquals("direct_http_reader", registry.getCapability("web.read")?.activeBackend)
    }
}
