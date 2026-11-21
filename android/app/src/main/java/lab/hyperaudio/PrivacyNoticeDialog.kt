package lab.hyperaudio

import android.app.Activity
import android.app.AlertDialog
import android.widget.ScrollView

/** Reads only the bundled notice; opening this page makes no network request. */
object PrivacyNoticeDialog {
    fun show(activity: Activity) {
        val ui = AndroidUi(activity)
        val content = ui.column().apply { setPadding(ui.dp(20),ui.dp(12),ui.dp(20),ui.dp(12)) }
        val notice = runCatching {
            activity.resources.openRawResource(R.raw.privacy_notice).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrElse { "隐私说明读取失败\n请重新安装完整版本后重试。" }
        for (block in notice.trim().split("\n\n")) {
            val parts = block.split("\n",limit=2)
            content.addView(ui.text(parts[0],18f).apply { setPadding(0,ui.dp(16),0,ui.dp(8)) })
            if (parts.size > 1) content.addView(ui.text(parts[1],14f,true).apply {
                setTextIsSelectable(true)
                setLineSpacing(ui.dp(3).toFloat(),1f)
            })
        }
        AlertDialog.Builder(activity).setTitle("隐私说明")
            .setView(ScrollView(activity).apply { addView(content) })
            .setPositiveButton("返回",null).show()
    }
}
