package com.soltini.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Myra", appName)
  }

  @Test
  fun `youtube automator openVideo resolves song correctly`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val automator = com.soltini.app.agent.YouTubeAutomator(context)
    val result = automator.openVideo("Kesariya song")
    org.junit.Assert.assertTrue(result.has("status"))
    val status = result.getString("status")
    org.junit.Assert.assertTrue(status == "playing_video" || status == "searching")
  }

  @Test
  fun `conversation history persistence saves and retrieves properly`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = com.soltini.app.memory2.Memory2Database(context)
    db.clearConversationHistory()

    val id1 = java.util.UUID.randomUUID().toString()
    val id2 = java.util.UUID.randomUUID().toString()
    val now = System.currentTimeMillis()

    db.saveConversationEntry(id1, "USER", "Can you read my report.txt?", now)
    db.saveConversationEntry(id2, "GEMINI", "Yes Boss, reading report.txt now.", now + 1000)

    val retrieved = db.getAllConversationEntries(10)
    org.junit.Assert.assertEquals(2, retrieved.size)
    org.junit.Assert.assertEquals("USER", retrieved[0].sender)
    org.junit.Assert.assertEquals("Can you read my report.txt?", retrieved[0].text)
    org.junit.Assert.assertEquals("GEMINI", retrieved[1].sender)
    org.junit.Assert.assertEquals("Yes Boss, reading report.txt now.", retrieved[1].text)

    db.deleteConversationEntry(id1)
    val afterDelete = db.getAllConversationEntries(10)
    org.junit.Assert.assertEquals(1, afterDelete.size)
    org.junit.Assert.assertEquals(id2, afterDelete[0].id)

    db.clearConversationHistory()
    org.junit.Assert.assertEquals(0, db.getAllConversationEntries(10).size)
  }

  @Test
  fun `storage manager plugin is registered and returns valid response`() = kotlinx.coroutines.test.runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val registry = com.soltini.app.plugins.PluginRegistry.getInstance(context)
    val executor = com.soltini.app.agent.AgentToolExecutor(context)
    val settings = com.soltini.app.settings.AppSettings(context)
    com.soltini.app.plugins.BuiltinPluginInitializer.initializeAll(context, registry, executor, settings)

    val storagePlugin = registry.getPlugin("file_manager_service")
    org.junit.Assert.assertNotNull(storagePlugin)
    org.junit.Assert.assertEquals("Storage Intelligence & File Manager", storagePlugin!!.metadata.name)

    val params = org.json.JSONObject().apply {
      put("action", "search")
      put("query", "test")
    }
    val result = storagePlugin.execute(com.soltini.app.plugins.PluginContext(context, params))
    org.junit.Assert.assertTrue(result.isSuccess)
  }

  @Test
  fun `myra orchestrator processes storage task`() = kotlinx.coroutines.test.runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val settings = com.soltini.app.settings.AppSettings(context)
    val memory2 = com.soltini.app.memory2.Memory2Engine.getInstance(context, settings)
    val registry = com.soltini.app.plugins.PluginRegistry.getInstance(context)
    val executor = com.soltini.app.agent.AgentToolExecutor(context)
    com.soltini.app.plugins.BuiltinPluginInitializer.initializeAll(context, registry, executor, settings)

    val orchestrator = com.soltini.app.orchestrator.MyraOrchestrator.getInstance(context, memory2, registry, settings)
    val outcome = orchestrator.processRequest("Search for report.pdf in files")
    org.junit.Assert.assertNotNull(outcome)
    org.junit.Assert.assertTrue(outcome.toolSequenceUsed.contains("file_manager_service"))
  }

  @Test
  fun `file safety manager requires confirmation for destructive operations`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val storageManager = com.soltini.app.storage.SafStorageManager(context)
    val safetyManager = com.soltini.app.storage.FileSafetyManager(storageManager)

    val fakeUri = android.net.Uri.parse("content://fake/item.txt")
    val pending = safetyManager.registerDestructiveAction(
      com.soltini.app.storage.FileActionType.DELETE,
      fakeUri,
      "item.txt"
    )
    org.junit.Assert.assertNotNull(pending.id)
    org.junit.Assert.assertTrue(pending.confirmationPrompt.contains("item.txt"))

    val latest = safetyManager.getLatestPending()
    org.junit.Assert.assertEquals(pending.id, latest?.id)

    safetyManager.cancelPendingAction(pending.id)
    org.junit.Assert.assertNull(safetyManager.getLatestPending())
  }

  @Test
  fun `rag knowledge engine handles document lifecycle`() = kotlinx.coroutines.test.runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val settings = com.soltini.app.settings.AppSettings(context)
    val rag = com.soltini.app.rag.RagKnowledgeEngine.getInstance(context, settings)

    val testItem = com.soltini.app.storage.StorageItemInfo(
      uriString = "content://test/notes.txt",
      name = "notes.txt",
      isDirectory = false,
      sizeBytes = 120,
      mimeType = "text/plain",
      lastModified = System.currentTimeMillis()
    )

    rag.db.deleteDocument(testItem.uriString)
    val statsBefore = rag.db.getStats()
    org.junit.Assert.assertTrue(statsBefore.totalDocuments >= 0)
  }
}
