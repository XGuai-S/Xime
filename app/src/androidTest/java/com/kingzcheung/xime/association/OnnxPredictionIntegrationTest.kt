package com.kingzcheung.xime.association

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OnnxPredictionIntegrationTest {

    private lateinit var context: Context
    private var hasModel = false

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        val vocabFile = File(context.filesDir, "vocab.json")
        val modelFile = File(context.filesDir, "model_int8_dynamic.onnx")
        hasModel = vocabFile.exists() && modelFile.exists()

        if (hasModel) {
            val vocabJson = JSONObject(vocabFile.readText())
            val vocabMap = when {
                vocabJson.has("model") -> vocabJson.getJSONObject("model").getJSONObject("vocab")
                vocabJson.has("vocab") -> vocabJson.getJSONObject("vocab")
                else -> vocabJson
            }
            val vocab = vocabMap.keys().asSequence().associateWith { vocabMap.getInt(it) }
            println("Vocabulary loaded: ${vocab.size} words")
            println("filesDir: ${context.filesDir.absolutePath}")
            println("model size: ${modelFile.length()} bytes")

            NativeOnnxEngine.initialize(context, modelFile.absolutePath)
            NativeOnnxEngine.initVocab(vocab)
        } else {
            println("Model files not found in ${context.filesDir.absolutePath}")
        }
    }

    @After
    fun tearDown() {
        if (hasModel) {
            NativeOnnxEngine.release()
        }
    }

    @Test
    fun testPredictJinTianTianQi() = runBlocking {
        if (!hasModel) return@runBlocking

        val inputText = "今天天气"
        val candidates = NativeOnnxEngine.predict(inputText, 20)

        println("Input: '$inputText'")
        candidates.forEachIndexed { i, candidate ->
            println("  Top ${i+1}: word='${candidate.text}' score=${candidate.score}")
        }

        org.junit.Assert.assertTrue("Should have prediction results", candidates.isNotEmpty())
    }

    @Test
    fun testPredictJinTian() = runBlocking {
        if (!hasModel) return@runBlocking

        val inputText = "今天"
        val candidates = NativeOnnxEngine.predict(inputText, 20)

        println("Input: '$inputText'")
        candidates.forEachIndexed { i, candidate ->
            println("  Top ${i+1}: word='${candidate.text}' score=${candidate.score}")
        }

        org.junit.Assert.assertTrue("Should have prediction results", candidates.isNotEmpty())
    }
}
