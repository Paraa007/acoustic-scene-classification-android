package com.fzi.speakerid.library

import com.fzi.speakerid.library.pipeline.OnnxSessionProvider
import com.fzi.speakerid.testutil.TestPaths
import java.io.File
import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Laedt alle drei ONNX-Modelle aus dem Repo-Pfad und prueft die Signaturen. */
class OnnxSessionProviderTest {

    @Test
    fun loadsAllThreeModelsFromRepoPath() {
        OnnxSessionProvider(TestPaths.modelsDir).use { provider ->
            val vad = provider.vad()
            assertTrue(vad.inputNames.containsAll(listOf("input", "state", "sr")))

            // ReDimNet: 1 Float-Input [1,1,16000] (externe Gewichte muessen aufloesen)
            val emb = provider.embedding()
            assertEquals(1, emb.inputNames.size)

            val seg = provider.segmentation()
            assertEquals(1, seg.inputNames.size)

            // Sessions werden gecached
            assertSame(vad, provider.vad())
        }
    }

    @Test
    fun missingModelThrowsFileNotFound() {
        val provider = OnnxSessionProvider(File("/nonexistent/dir"))
        assertThrows(FileNotFoundException::class.java) {
            provider.modelFile(OnnxSessionProvider.VAD_MODEL)
        }
    }
}
