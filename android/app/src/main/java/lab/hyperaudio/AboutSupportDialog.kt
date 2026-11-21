package lab.hyperaudio

import android.app.Activity
import android.app.AlertDialog
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView

/** Only creates the image view when the user opens this page. No payment SDK or network request. */
object AboutSupportDialog {
    fun show(activity: Activity) {
        val ui=AndroidUi(activity)
        val content=ui.column().apply { setPadding(ui.dp(20),ui.dp(12),ui.dp(20),ui.dp(12)) }
        val version=activity.packageManager.getPackageInfo(activity.packageName,0).versionName.orEmpty()
        content.addView(ui.text("HyperAudio $version",20f).apply { gravity=Gravity.CENTER })
        content.addView(ui.text("把 Android 的媒体声音送到 iPhone 蓝牙耳机。",14f,true).apply { setPadding(0,ui.dp(12),0,ui.dp(18)) })
        ui.row(content,"隐私说明","音频、权限与本地数据如何处理") { PrivacyNoticeDialog.show(activity) }
        content.addView(ui.text("感谢你的支持",18f))
        content.addView(ui.text("如果它帮到了你，可以通过支付宝自愿赞助。所有现有功能均可直接使用。",14f,true).apply { setPadding(0,ui.dp(8),0,ui.dp(12)) })
        content.addView(ImageView(activity).apply {
            setImageResource(R.drawable.sponsor_alipay)
            adjustViewBounds=true
            scaleType=ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(android.graphics.Color.WHITE)
            contentDescription="支付宝赞助收款码，收款人以原图和支付宝确认页为准"
        },LinearLayout.LayoutParams(-1,-2))
        content.addView(ui.text("可截图后打开支付宝，使用“扫一扫 → 相册”识别。付款前请核对收款人和金额。",13f,true).apply { setPadding(0,ui.dp(12),0,ui.dp(8)) })
        AlertDialog.Builder(activity).setTitle("关于与赞助")
            .setView(ScrollView(activity).apply { addView(content) })
            .setPositiveButton("关闭",null).show()
    }
}
