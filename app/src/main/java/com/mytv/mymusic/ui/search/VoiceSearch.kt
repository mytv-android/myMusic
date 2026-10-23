package com.mytv.mymusic.ui.search

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.mytv.mymusic.ui.components.LocalAppSnackbar
import kotlinx.coroutines.launch
import java.util.Locale

/** 系统语音搜索入口：available 为假时不要显示麦克风。 */
class VoiceSearch(val available: Boolean, val start: () -> Unit)

/**
 * 系统语音识别（RecognizerIntent）。录音由系统识别器完成，应用本身不需要 RECORD_AUDIO：
 * 识别器不存在或启动失败时走 [onFailure]，不弹权限框。
 */
@Composable
fun rememberVoiceSearch(onResult: (String) -> Unit, onFailure: () -> Unit): VoiceSearch {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
        if (result.resultCode == Activity.RESULT_OK && text.isNotEmpty()) onResult(text)
    }
    val available = remember(context) {
        // 有的机型只装了能处理识别 Intent 的语音助手，没有实现 RecognitionService
        runCatching {
            SpeechRecognizer.isRecognitionAvailable(context) ||
                recognizerIntent(context).resolveActivity(context.packageManager) != null
        }.getOrDefault(false)
    }
    return VoiceSearch(available) {
        if (!available) {
            onFailure()
        } else {
            runCatching { launcher.launch(recognizerIntent(context)) }.onFailure { onFailure() }
        }
    }
}

/** 带提示的版本：识别器不存在或启动失败时弹 Snackbar。 */
@Composable
fun rememberVoiceSearchWithFeedback(onResult: (String) -> Unit): VoiceSearch {
    val snackbar = LocalAppSnackbar.current
    val scope = rememberCoroutineScope()
    return rememberVoiceSearch(
        onResult = onResult,
        onFailure = { scope.launch { snackbar.showSnackbar("这台设备没有可用的语音识别服务") } },
    )
}

/** 用系统语言识别，识别器自己决定是否离线、返回几条候选。 */
private fun recognizerIntent(context: Context): Intent =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_PROMPT, "说出歌曲、歌手或专辑名")
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
    }
