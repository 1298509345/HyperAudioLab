package lab.hyperaudio

import android.app.Activity
import android.media.*
import android.os.Bundle
import android.os.Build
import android.view.WindowInsets
import android.widget.*
import kotlin.math.sin

/** Known-positive / known-negative source, independent of commercial app policy. */
class ToneActivity : Activity() {
    private var track: AudioTrack? = null
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24,24,24,24) }
        box.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(24+bars.left,24+bars.top,24+bars.right,24+bars.bottom)
            }
            insets
        }
        box.addView(TextView(this).apply { setText(R.string.tone_help) })
        fun start(policy: Int) {
            stop()
            val pcm = ShortArray(4800) { (3276 * sin(2 * Math.PI * 1000 * it / 48000)).toInt().toShort() }
            track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).setAllowedCapturePolicy(policy).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size * 2).build().also {
                    it.write(pcm,0,pcm.size); it.setLoopPoints(0,pcm.size,-1); it.play()
                }
        }
        fun button(s: String, f: () -> Unit) { box.addView(Button(this).apply { text = s; setOnClickListener { f() } }) }
        button("播放：允许采集") { start(AudioAttributes.ALLOW_CAPTURE_BY_ALL) }
        button("播放：禁止采集") { start(AudioAttributes.ALLOW_CAPTURE_BY_NONE) }
        button("停止测试音") { stop() }
        button("返回采集界面（测试音继续）") { startActivity(android.content.Intent(this,MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
        setContentView(box)
    }
    private fun stop() { track?.let { it.stop(); it.release() }; track = null }
    override fun onDestroy() { stop(); super.onDestroy() }
}
