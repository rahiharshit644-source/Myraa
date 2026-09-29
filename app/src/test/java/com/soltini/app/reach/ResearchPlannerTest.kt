package com.soltini.app.reach

import com.soltini.app.reach.model.ResearchMode
import com.soltini.app.reach.model.ResearchPlatform
import com.soltini.app.reach.planner.ResearchPlanner
import org.junit.Assert.*
import org.junit.Test

class ResearchPlannerTest {

    @Test
    fun testModeInference() {
        val quickPlan = ResearchPlanner.createPlan("Quickly batao latest quantum computing news")
        assertEquals(ResearchMode.QUICK, quickPlan.mode)
        assertEquals(2, quickPlan.maxSourcesTotal)

        val normalPlan = ResearchPlanner.createPlan("Search the web for Kotlin 2.2 features")
        assertEquals(ResearchMode.NORMAL, normalPlan.mode)

        val deepPlan = ResearchPlanner.createPlan("Deep research karo on autonomous AI agent architectures and cross-check")
        assertEquals(ResearchMode.DEEP, deepPlan.mode)
        assertTrue(deepPlan.crossCheckRequired)
        assertEquals(10, deepPlan.maxSourcesTotal)
    }

    @Test
    fun testExplicitUrlTargeting() {
        val ytPlan = ResearchPlanner.createPlan("Is video ka summary batao: https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        assertEquals(1, ytPlan.targets.size)
        assertEquals(ResearchPlatform.YOUTUBE, ytPlan.targets[0].platform)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", ytPlan.targets[0].specificUrl)

        val ghPlan = ResearchPlanner.createPlan("Analyze repo: https://github.com/Panniantong/Agent-Reach")
        assertEquals(1, ghPlan.targets.size)
        assertEquals(ResearchPlatform.GITHUB, ghPlan.targets[0].platform)

        val webPlan = ResearchPlanner.createPlan("Read this article https://techcrunch.com/article/ai-update")
        assertEquals(1, webPlan.targets.size)
        assertEquals(ResearchPlatform.WEB_READER, webPlan.targets[0].platform)
    }

    @Test
    fun testPlatformSelectionByTopic() {
        val codePlan = ResearchPlanner.createPlan("Explain the open source architecture of Agent-Reach on github")
        assertTrue(codePlan.targets.any { it.platform == ResearchPlatform.GITHUB })
        assertTrue(codePlan.targets.any { it.platform == ResearchPlatform.WEB })

        val redditPlan = ResearchPlanner.createPlan("What do people on Reddit say about Jetpack Compose vs Flutter?")
        assertTrue(redditPlan.targets.any { it.platform == ResearchPlatform.REDDIT })
    }
}
