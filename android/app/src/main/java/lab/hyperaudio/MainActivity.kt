package lab.hyperaudio

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.ComponentName
import android.graphics.drawable.Icon
import android.app.StatusBarManager
import android.net.ConnectivityManager
import android.provider.Settings
import android.net.Uri
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.projection.MediaProjectionManager
import android.net.Network
import android.os.*
import android.view.View
import android.view.WindowInsets
import android.widget.*


class MainActivity : Activity() {
    private lateinit var host: EditText
    private lateinit var token: EditText
    private lateinit var mode: Spinner
    private lateinit var save: Switch
    private lateinit var stereo: Switch
    private lateinit var sleep: Spinner
    private val sleepMinutes = listOf(0,15,30,60,120)
    private lateinit var status: TextView
    private lateinit var connectionStatus: TextView
    private lateinit var retained: RetainedConnection
    private var choiceDialog: AlertDialog?=null
    private lateinit var auto: AutoConnect
    private lateinit var connectButton: Button
    private lateinit var diagnostics: AutoConnect
    private lateinit var diagnosticStatus: TextView
    private lateinit var pauseButton: Button
    private var pages: List<LinearLayout> = emptyList()
    private val navButtons=mutableListOf<AndroidUi.NavItem>()
    private lateinit var ui: AndroidUi
    private lateinit var stopButton: Button
    private lateinit var stateBadge: TextView
    private lateinit var targetName: TextView
    private lateinit var targetCaption: TextView
    private lateinit var muteValue: TextView
    private lateinit var channelValue: TextView
    private lateinit var sleepValue: TextView
    private lateinit var modeValue: TextView
    private lateinit var settingsSleepValue: TextView
    private var selectedPage=0
    private lateinit var summary: TextView
    private lateinit var recovery: Button
    private lateinit var deviceList: LinearLayout
    private lateinit var manualSection: LinearLayout
    private var diagnosticDialog: AlertDialog?=null
    private var reportPending: String?=null
    private lateinit var receiverPanel:ReceiverPanel
    private lateinit var sendHome:LinearLayout
    private lateinit var sendDevices:LinearLayout
    private lateinit var sendSettings:LinearLayout
    private val senderSettingsViews=mutableListOf<View>()
    private lateinit var receiverSettings:LinearLayout
    private lateinit var sendRoleButton:Button
    private lateinit var receiveRoleButton:Button
    private var projectionRequest=-1
    private var nextProjectionRequest=100
    private var consentEpoch=-1L
    private var busy = false
    private var pending: ConnectionInfo? = null
    private val handler = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            renderSnapshot()
            handler.postDelayed(this, 1000)
        }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        ui=AndroidUi(this)
        AppRole.current(this)
        consentEpoch=state?.getLong("consentEpoch",-1L) ?: -1L
        projectionRequest=state?.getInt("projectionRequest",-1) ?: -1
        nextProjectionRequest=state?.getInt("nextProjectionRequest",100) ?: 100
        reportPending=state?.getString("reportPending")
        @Suppress("DEPRECATION")
        val previous=lastNonConfigurationInstance as? RetainedConnection
        retained=previous ?: RetainedConnection(applicationContext)
        auto=retained.auto; diagnostics=AutoConnect(applicationContext)
        val shell=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(getColor(R.color.canvas)) }
        shell.setOnApplyWindowInsetsListener { view,insets ->
            if(Build.VERSION.SDK_INT>=30) {
                val bars=insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left,bars.top,bars.right,bars.bottom)
            }; insets
        }
        val content=FrameLayout(this)
        shell.addView(content,LinearLayout.LayoutParams(-1,0,1f))
        pages=(0..2).map {
            val box=ui.column().apply { setPadding(dp(20),dp(26),dp(20),dp(24)) }
            content.addView(ScrollView(this).apply { isFillViewport=true; isVerticalScrollBarEnabled=false; addView(box) },FrameLayout.LayoutParams(-1,-1))
            box
        }
        val roleChoices=LinearLayout(this)
        sendRoleButton=ui.button("发送声音").apply{setOnClickListener{chooseRole(AudioRole.SEND)}}
        receiveRoleButton=ui.button("接收声音").apply{setOnClickListener{chooseRole(AudioRole.RECEIVE)}}
        roleChoices.addView(sendRoleButton,LinearLayout.LayoutParams(0,-2,1f));roleChoices.addView(receiveRoleButton,LinearLayout.LayoutParams(0,-2,1f));pages[0].addView(roleChoices)
        sendHome=ui.column();sendDevices=ui.column();sendSettings=ui.column()
        pages[0].addView(sendHome);pages[1].addView(sendDevices);pages[2].addView(sendSettings)
        buildConnection(sendHome); buildDevices(sendDevices,state); buildSettings(sendSettings,state)
        receiverPanel=ReceiverPanel(this,ui)
        pages[0].addView(receiverPanel.home);pages[1].addView(receiverPanel.devices)
        applyRole()
        val nav=LinearLayout(this).apply { setBackgroundColor(getColor(R.color.surface)); setPadding(dp(18),dp(6),dp(18),dp(3)) }
        val icons=listOf(R.drawable.ic_wave,R.drawable.ic_phone,R.drawable.ic_settings)
        listOf("连接","设备","设置").forEachIndexed { index,title ->
            val item=ui.navigation(title,icons[index]) { showPage(index) }
            nav.addView(item.view,LinearLayout.LayoutParams(0,dp(70),1f)); navButtons.add(item)
        }
        shell.addView(nav); setContentView(shell); ui.systemBars(window)
        showPage(state?.getInt("page",0) ?: 0)
        if(AppRole.current(this)==AudioRole.SEND && state?.containsKey("pendingHost")==true) {
            @Suppress("DEPRECATION")
            val network=state.getParcelable<Network>("pendingNetwork")
            pending=ConnectionInfo(state.getString("pendingHost")!!,state.getString("pendingToken")!!,50005,state.getString("pendingName") ?: "接收端",network,state.getString("pendingID"),state.getInt("pendingPort",50006),state.getBoolean("pendingStereo",false))
            busy=true; connectButton.isEnabled=false; connectionStatus.text="等待本次系统授权；可取消连接"
        }
        consumeTileIntent()
        if(!getSharedPreferences("lab",0).getBoolean(if(AppRole.current(this)==AudioRole.RECEIVE) "receiveGuideSeen" else "guideSeen",false)) handler.post { showOnboarding() }
    }
    private fun applyRole(){
        if(!::receiverPanel.isInitialized)return
        val receive=AppRole.current(this)==AudioRole.RECEIVE
        listOf(sendHome,sendDevices).forEach{it.visibility=if(receive)View.GONE else View.VISIBLE}
        listOf(receiverPanel.home,receiverPanel.devices).forEach{it.visibility=if(receive)View.VISIBLE else View.GONE}
        senderSettingsViews.forEach{it.visibility=if(receive)View.GONE else View.VISIBLE}
        receiverSettings.visibility=if(receive)View.VISIBLE else View.GONE
        ui.update(sendRoleButton,if(receive)"发送声音" else "✓ 发送声音")
        ui.update(receiveRoleButton,if(receive)"✓ 接收声音" else "接收声音")
        receiverPanel.render()
    }
    private fun chooseRole(role:AudioRole){
        if(AppRole.current(this)==role)return
        fun change(){
            // Invalidate outstanding consent before cancelling or stopping either service.
            diagnosticDialog?.dismiss();diagnostics.cancel()
            AppRole.select(this,role);consentEpoch=-1;projectionRequest=-1;pending=null;busy=false;retained.flow.cancel();choiceDialog?.dismiss()
            stopService(Intent(this,CaptureService::class.java));stopService(Intent(this,ReceiverService::class.java))
            applyRole();showPage(0)
        }
        if(LabState.running || ReceiverState.snapshot.running || busy || pending!=null)
            AlertDialog.Builder(this).setTitle("停止当前会话并切换？").setMessage("发送与接收不能同时运行。切换后请手动开始新的会话。")
                .setPositiveButton("停止并切换"){_,_->change()}.setNegativeButton("取消",null).show()
        else change()
    }
    private fun dp(value: Int) = (value*resources.displayMetrics.density).toInt()
    private fun label(parent:LinearLayout,value:String,heading:Boolean=false):TextView =
        ui.text(value,if(heading) 18f else 14f,muted=!heading,bold=heading).apply {
            setPadding(0,dp(6),0,dp(8)); parent.addView(this)
        }
    private fun action(parent:LinearLayout,value:String,block:()->Unit):Button = ui.action(parent,value,block=block)
    private fun card(parent:LinearLayout):LinearLayout = ui.card(parent)
    private fun showPage(index:Int) {
        selectedPage=index.coerceIn(0,2)
        pages.forEachIndexed { i,box -> (box.parent as View).visibility=if(i==selectedPage) View.VISIBLE else View.GONE }
        navButtons.forEachIndexed { i,item -> item.select(i==selectedPage) }
        if(selectedPage==1) refreshDevices()
        renderSnapshot()
    }
    private fun buildConnection(box:LinearLayout) {
        ui.header(box,"HyperAudio","手机照常使用，声音交给接收端。")
        val hero=card(box)
        stateBadge=ui.text("未连接",12f,bold=true).apply {
            setTextColor(getColor(R.color.accent)); background=ui.shape(getColor(R.color.accent_soft),10)
            setPadding(dp(9),dp(5),dp(9),dp(5))
        }; hero.addView(stateBadge,LinearLayout.LayoutParams(-2,-2))
        ui.gap(hero,22)
        val flow=LinearLayout(this).apply { gravity=android.view.Gravity.CENTER_VERTICAL }
        val source=ui.column(); source.addView(ui.iconTile(R.drawable.ic_phone)); ui.gap(source,10)
        source.addView(ui.text("这台 Android",15f,bold=true)); ui.gap(source,4); source.addView(ui.text("音频来源",12f,true))
        flow.addView(source,LinearLayout.LayoutParams(0,-2,1f))
        flow.addView(ui.icon(R.drawable.ic_arrow).apply { setColorFilter(getColor(R.color.secondary)) },LinearLayout.LayoutParams(dp(22),dp(22)).apply { leftMargin=dp(8); rightMargin=dp(22) })
        val target=ui.column(); target.addView(ui.iconTile(R.drawable.ic_phone)); ui.gap(target,10)
        targetName=ui.text("接收端",15f,bold=true).apply { maxLines=2 }
        target.addView(targetName); ui.gap(target,4)
        targetCaption=ui.text("等待连接",12f,true); target.addView(targetCaption)
        flow.addView(target,LinearLayout.LayoutParams(0,-2,1f)); hero.addView(flow)
        ui.gap(hero,22)
        summary=ui.text("准备好，随时连接",23f,bold=true); hero.addView(summary); ui.gap(hero,8)
        connectionStatus=ui.text("让另一台设备开始接收，再连接并授权。",13f,true); hero.addView(connectionStatus)
        ui.gap(hero,16)
        val actions=LinearLayout(this).apply { gravity=android.view.Gravity.CENTER_VERTICAL }
        connectButton=ui.button("连接并开始",primary=true).apply { setOnClickListener { startAutomatic() } }
        pauseButton=ui.button("暂停发送",primary=true).apply { setOnClickListener { if(LabState.running) startService(Intent(this@MainActivity,CaptureService::class.java).setAction("TOGGLE_PAUSE")) } }
        stopButton=ui.button("停止").apply { setOnClickListener { retained.flow.cancel(); pending=null; idle("已停止；请暂停音源后再恢复音量"); stopService(Intent(this@MainActivity,CaptureService::class.java)) } }
        actions.addView(connectButton,LinearLayout.LayoutParams(0,-2,1f))
        actions.addView(pauseButton,LinearLayout.LayoutParams(0,-2,1f))
        actions.addView(stopButton,LinearLayout.LayoutParams(-2,-2).apply { leftMargin=dp(10) })
        hero.addView(actions)
        recovery=ui.button("检查连接",quiet=true).apply {
            setOnClickListener { if(LabState.running) { showPage(2); showDiagnostics() } else startAutomatic() }
        }; hero.addView(recovery,LinearLayout.LayoutParams(-1,-2))
        val sound=card(box)
        muteValue=ui.row(sound,"本机媒体静音",value="连接后启用") { showPage(2) }.value
        ui.divider(sound)
        channelValue=ui.row(sound,"音频声道",value="优先立体声") { showPage(2) }.value
        ui.divider(sound)
        sleepValue=ui.row(sound,"睡眠定时",value="关闭") { showPage(2) }.value
        val help=ui.button("连接有问题？  一键自检",quiet=true).apply { setOnClickListener { showDiagnostics() } }
        box.addView(help,LinearLayout.LayoutParams(-1,-2))
    }
    private fun buildDevices(box:LinearLayout,state:Bundle?) {
        ui.header(box,"设备","记住你常用的接收端，下次更快连接。")
        deviceList=ui.column(); box.addView(deviceList)
        ui.action(box,"发现并连接",primary=true) { startAutomatic() }
        ui.gap(box,22)
        val tools=card(box)
        ui.row(tools,"手动连接","自动发现不可用时使用",icon=R.drawable.ic_settings) {
            manualSection.visibility=if(manualSection.visibility==View.GONE) View.VISIBLE else View.GONE
        }
        val manual=card(box).apply { visibility=View.GONE }; manualSection=manual
        label(manual,"连接信息",true)
        label(manual,"在接收端的手动连接信息中查看地址和本次配对码。")
        val prefs=getSharedPreferences("lab",0)
        fun field(hintText:String,value:String)=EditText(this).apply {
            hint=hintText; textSize=14f; setTextColor(getColor(R.color.ink)); setHintTextColor(getColor(R.color.secondary))
            setSingleLine(); minimumHeight=dp(52); setPadding(dp(14),dp(10),dp(14),dp(10))
            background=ui.shape(getColor(R.color.control_surface),12); setText(value)
            manual.addView(this,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(10) })
        }
        host=field("接收端局域网 IPv4",state?.getString("manualHost") ?: prefs.getString("host","") ?: "").apply { inputType=android.text.InputType.TYPE_CLASS_PHONE }
        token=field("当前 8 位配对码",state?.getString("manualToken") ?: prefs.getString("token","") ?: "")
        action(manual,"按地址配对") {
            if(!ready()) return@action
            val address=host.text.toString().trim()
            if(!validAddress(address)) { toast("先填写接收端 IPv4 地址"); return@action }
            if(!prepareCapture()) return@action
            busy=true; connectButton.isEnabled=false; connect(ReceiverEndpoint("指定地址接收端",address))
        }
        action(manual,"按地址和配对码开始") {
            if(!ready()) return@action
            val address=host.text.toString().trim(); val code=token.text.toString().trim().lowercase()
            if(!validAddress(address) || !code.matches(Regex("[0-9a-f]{8}"))) { toast("填写 IPv4 地址及 8 位十六进制配对码"); return@action }
            if(!prepareCapture()) return@action
            retained.flow.cancel()
            pending=ConnectionInfo(address,code,50005,"手动接收端"); busy=true; connectButton.isEnabled=false; authorize()
        }
    }
    private fun validAddress(value: String): Boolean = value.split('.').let { parts -> parts.size==4 && parts.all { it.isNotEmpty() && it.all(Char::isDigit) && (it.toIntOrNull() ?: -1) in 0..255 } }
    private fun refreshDevices() {
        if(AppRole.current(this)==AudioRole.RECEIVE){if(::receiverPanel.isInitialized)receiverPanel.render();return}
        if(!::deviceList.isInitialized) return
        deviceList.removeAllViews()
        val devices=auto.devices()
        if(devices.isEmpty()) {
            val empty=card(deviceList)
            empty.addView(ui.iconTile(R.drawable.ic_phone)); ui.gap(empty,18)
            empty.addView(ui.text("还没有配对的接收端",20f,bold=true)); ui.gap(empty,8)
            empty.addView(ui.text("先让另一台设备开始接收，再点击发现并连接。首次在接收端允许即可。",14f,true))
        }
        devices.forEach { device ->
            val item=card(deviceList)
            val top=LinearLayout(this).apply { gravity=android.view.Gravity.CENTER_VERTICAL }
            top.addView(ui.iconTile(R.drawable.ic_phone))
            val words=ui.column().apply { setPadding(dp(12),0,0,0) }
            words.addView(ui.text(device.displayName,17f,bold=true))
            words.addView(ui.text(if(device.lastSeenMS==0L) "已记住配对" else "最近使用 · "+java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT).format(java.util.Date(device.lastSeenMS)),12f,true).apply { setPadding(0,dp(5),0,0) })
            top.addView(words,LinearLayout.LayoutParams(0,-2,1f))
            val more=ui.button("⋯",quiet=true).apply { contentDescription="管理 "+device.displayName; textSize=24f }
            top.addView(more,LinearLayout.LayoutParams(dp(48),dp(48))); item.addView(top)
            more.setOnClickListener {
                PopupMenu(this,more).apply {
                    menu.add("修改别名").setOnMenuItemClickListener {
                        val edit=EditText(this@MainActivity).apply { minimumHeight=dp(52); hint="留空恢复设备名称"; setText(device.alias ?: "") }
                        AlertDialog.Builder(this@MainActivity).setTitle("设备别名").setView(edit)
                            .setPositiveButton("保存") { _,_ -> auto.rename(device.id,edit.text.toString()); refreshDevices() }
                            .setNegativeButton("取消",null).show(); true
                    }
                    menu.add("解除配对").setOnMenuItemClickListener {
                        if(busy || LabState.running && LabState.snapshot.endpoint?.expectedID==device.id) toast("请先取消连接或停止该设备的采集")
                        else AlertDialog.Builder(this@MainActivity).setTitle("解除与 "+device.displayName+" 的配对？")
                            .setMessage("只移除此设备的本地记录，下次连接需要重新允许。")
                            .setPositiveButton("解除配对") { _,_ -> if(!auto.forget(device.id)) toast("请先停止当前采集"); refreshDevices() }
                            .setNegativeButton("取消",null).show()
                        true
                    }; show()
                }
            }
            ui.action(item,"连接这台接收端",primary=true) {
                if(!ready() || !prepareCapture()) return@action
                busy=true; connectButton.isEnabled=false; showPage(0)
                if(device.host!=null) connect(ReceiverEndpoint(device.displayName,device.host,device.port,device.id))
                else retained.flow.discoverID(device.id)
            }
        }
    }
    private fun buildSettings(box:LinearLayout,state:Bundle?) {
        ui.header(box,"设置","按你的习惯，安静地连接。")
        val prefs=getSharedPreferences("lab",0)
        receiverSettings=ui.column();box.addView(receiverSettings)
        val receiving=card(receiverSettings)
        ui.heading(receiving,"接收声音")
        receiving.addView(ui.text("默认使用蓝牙、有线或 USB 耳机；没有耳机时保持静音。每个新连接都会回到耳机默认。扬声器需在首页显式选择，并确认本次外放。",14f,true))
        ui.action(receiving,"到首页选择输出或暂停声音") { showPage(0) }
        val senderAudio=ui.column();box.addView(senderAudio);senderSettingsViews.add(senderAudio)
        ui.heading(senderAudio,"声音")
        val audio=card(senderAudio)
        val stereoRow=ui.row(audio,"优先立体声","下次连接生效，不支持时自动回退")
        stereo=ui.toggle("优先立体声",prefs.getBoolean("preferStereo",true)).apply {
            setOnCheckedChangeListener { _,value -> prefs.edit().putBoolean("preferStereo",value).apply(); renderSnapshot() }
        }; stereoRow.trailing.addView(stereo)
        ui.divider(audio)
        val modeTitles=listOf("媒体音量归零","外接静音负载","媒体静音保护")
        val modeOptions=listOf("媒体音量归零（250ms 复查）","外接 USB / 有线静音负载","ADJUST_MUTE（250ms 复查）")
        mode=Spinner(this).apply {
            adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,modeOptions)
            visibility=View.GONE; setSelection((state?.getInt("modeChoice") ?: prefs.getInt("mode",0)).coerceIn(0,2))
            onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p:AdapterView<*>?,v:View?,position:Int,id:Long) {
                    prefs.edit().putInt("mode",position).apply()
                    if(::modeValue.isInitialized) modeValue.text=modeTitles[position]
                }
                override fun onNothingSelected(p:AdapterView<*>?) {}
            }
        }; audio.addView(mode)
        modeValue=ui.row(audio,"本机静音方式",value=modeTitles[mode.selectedItemPosition.coerceIn(0,2)]) {
            AlertDialog.Builder(this).setTitle("本机静音方式")
                .setSingleChoiceItems(modeOptions.toTypedArray(),mode.selectedItemPosition) { dialog,index ->
                    mode.setSelection(index); prefs.edit().putInt("mode",index).apply(); modeValue.text=modeTitles[index]
                    renderSnapshot(); dialog.dismiss()
                }.setNegativeButton("取消",null).show()
        }.value
        ui.divider(audio)
        sleep=Spinner(this).apply {
            adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,listOf("关闭","15 分钟","30 分钟","60 分钟","120 分钟"))
            visibility=View.GONE; setSelection(state?.getInt("sleepSelection",0)?.coerceIn(0,4) ?: 0)
        }; audio.addView(sleep)
        settingsSleepValue=ui.row(audio,"睡眠定时","只停止本机采集",value=if(sleep.selectedItemPosition==0) "关闭" else sleepMinutes[sleep.selectedItemPosition].toString()+" 分钟") {
            var selected=sleep.selectedItemPosition
            AlertDialog.Builder(this).setTitle("睡眠定时")
                .setSingleChoiceItems(arrayOf("关闭","15 分钟","30 分钟","60 分钟","120 分钟"),selected) { _,index -> selected=index }
                .setPositiveButton("应用") { _,_ ->
                    sleep.setSelection(selected)
                    settingsSleepValue.text=if(selected==0) "关闭" else sleepMinutes[selected].toString()+" 分钟"
                    val current=LabState.snapshot
                    if(current.running) startService(Intent(this,CaptureService::class.java).setAction("SET_SLEEP").putExtra("sessionID",current.sessionID).putExtra("sleepMinutes",sleepMinutes[selected]))
                    else toast("下次开始采集时应用所选定时")
                    renderSnapshot()
                }.setNegativeButton("取消",null).show()
        }.value
        ui.heading(box,"连接与后台")
        val system=card(box)
        ui.row(system,"后台与锁屏","让长时间播放更稳定",icon=R.drawable.ic_phone) { showBackgroundGuide() }
        ui.divider(system)
        ui.row(system,"系统快捷开关",icon=R.drawable.ic_wave) { addTile() }
        ui.heading(box,"帮助")
        val help=card(box)
        ui.row(help,"一键自检","检查连接、收包与音频状态",icon=R.drawable.ic_check) { showDiagnostics() }
        ui.divider(help)
        ui.row(help,"诊断报告","生成脱敏报告，自行选择保存位置",icon=R.drawable.ic_settings) { saveReport() }
        ui.divider(help)
        ui.row(help,"使用引导",icon=R.drawable.ic_phone) { showOnboarding() }
        val more=card(box);senderSettingsViews.add(more)
        val advanced=ui.column().apply { visibility=View.GONE }
        lateinit var advancedRow:AndroidUi.Row
        advancedRow=ui.row(more,"高级工具",value="展开") {
            val expanded=advanced.visibility==View.GONE
            advanced.visibility=if(expanded) View.VISIBLE else View.GONE
            ui.update(advancedRow.value,if(expanded) "收起" else "展开")
            advancedRow.view.contentDescription="高级工具，"+if(expanded) "已展开" else "已收起"
        }
        more.addView(advanced)
        ui.divider(advanced)
        ui.row(advanced,"立即媒体静音","影响其他 App；停止后不自动恢复") {
            getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_MUSIC,0,0)
            toast("已将系统媒体音量设为零")
        }
        ui.divider(advanced)
        val saveRow=ui.row(advanced,"保存音频副本","只保存前 5 分钟 PCM")
        save=ui.toggle("保存前五分钟音频",state?.getBoolean("savePCM",false) ?: false)
        saveRow.trailing.addView(save)
        ui.divider(advanced)
        ui.row(advanced,"测试音源","默认不发声") { startActivity(Intent(this,ToneActivity::class.java)) }
        ui.divider(advanced)
        ui.row(advanced,"忘记全部配对","保留本机身份与其他设置") {
            if(ready()) AlertDialog.Builder(this).setTitle("忘记全部已配对设备？")
                .setMessage("下次连接需要重新允许，其他设置不会被删除。")
                .setPositiveButton("忘记全部") { _,_ -> if(auto.forget()) { refreshDevices(); idle("已忘记全部配对记录") } else toast("请先停止采集") }
                .setNegativeButton("取消",null).show()
        }
        ui.divider(advanced)
        ui.row(advanced,"详细状态与日志") { showDiagnostics() }
        val about=card(box)
        ui.row(about,"关于与赞助","HyperAudio · 感谢你的支持",icon=R.drawable.ic_wave) { AboutSupportDialog.show(this) }
        box.addView(ui.text("HyperAudio "+packageManager.getPackageInfo(packageName,0).versionName,12f,true).apply { gravity=android.view.Gravity.CENTER; setPadding(0,dp(6),0,dp(6)) },LinearLayout.LayoutParams(-1,-2))
        box.addView(ui.text("停止后请先暂停音源，再自行恢复媒体音量。系统采集授权、受保护音源和后台行为仍由系统决定。",12f,true).apply {
            setPadding(dp(4),dp(4),dp(4),0);senderSettingsViews.add(this)
        })
    }
    private fun renderSnapshot() {
        if(::receiverPanel.isInitialized)receiverPanel.render()
        if(AppRole.current(this)==AudioRole.RECEIVE) {
            if(diagnosticDialog?.isShowing==true) ui.update(status,receiverDiagnosticDetail())
            return
        }
        if(!::summary.isInitialized || !::mode.isInitialized || !::stereo.isInitialized || !::sleep.isInitialized) return
        val s=LabState.snapshot
        val error=s.errorCode!=CaptureError.NONE
        val title=when {
            busy && !s.running -> "正在连接接收端"
            s.running && s.paused -> "声音已暂停"
            s.running && s.errorCode==CaptureError.NETWORK_UNAVAILABLE -> "正在恢复连接"
            s.running -> "正在发送音频"
            s.endReason==CaptureEndReason.SLEEP_EXPIRED -> "睡眠定时已结束"
            s.endReason==CaptureEndReason.AUTHORIZATION_REVOKED -> "需要重新授权"
            s.endReason==CaptureEndReason.CAPTURE_FAILED -> "暂时无法开始"
            s.endReason==CaptureEndReason.USER_STOPPED -> "转发已停止"
            else -> "准备好，随时连接"
        }
        if(summary.text.toString()!=title) summary.text=title
        val badge=when {
            busy && !s.running -> "正在连接"
            s.running && s.paused -> "已暂停"
            s.running && error -> "等待恢复"
            s.running -> "正在发送"
            s.endReason==CaptureEndReason.AUTHORIZATION_REVOKED -> "授权已结束"
            s.endReason==CaptureEndReason.SLEEP_EXPIRED -> "定时结束"
            error -> "需要处理"
            else -> "未连接"
        }
        if(stateBadge.text.toString()!=badge) stateBadge.text=badge
        val stateColor=getColor(when {
            error -> R.color.warning
            s.running && !s.paused -> R.color.success
            else -> R.color.accent
        })
        if(stateBadge.currentTextColor!=stateColor) stateBadge.setTextColor(stateColor)
        val target=s.endpoint?.name ?: auto.rememberedName() ?: "接收端"
        if(targetName.text.toString()!=target) targetName.text=target
        ui.update(targetCaption,if(s.running) "当前接收端" else if(auto.rememberedName()!=null) "上次使用" else "等待连接")
        val connection=when {
            s.running -> if(s.paused) "采集授权保持，点恢复即可继续发送。" else s.connection
            busy -> if(pending!=null) "等待本次系统采集授权" else retained.flow.message
            retained.flow.phase==ConnectionPhase.FAILED -> retained.flow.message
            s.endReason!=CaptureEndReason.NONE -> CapturePresentation.connection(s)
            retained.flow.phase==ConnectionPhase.CANCELLED -> retained.flow.message
            else -> "让另一台设备开始接收，再连接并授权。"
        }
        if(connectionStatus.text.toString()!=connection) connectionStatus.text=connection
        connectButton.visibility=if(s.running) View.GONE else View.VISIBLE
        connectButton.isEnabled=!busy && !s.running
        ui.update(connectButton,if(busy) "正在连接…" else "连接并开始")
        pauseButton.visibility=if(s.running) View.VISIBLE else View.GONE
        pauseButton.isEnabled=s.running; ui.update(pauseButton,if(s.paused) "恢复发送" else "暂停发送")
        stopButton.visibility=if(s.running || busy) View.VISIBLE else View.GONE
        ui.update(stopButton,if(busy && !s.running) "取消" else "停止")
        recovery.visibility=if(s.running && error) View.VISIBLE else View.GONE
        ui.update(recovery,"检查网络与接收端")
        val selectedMode=if(s.running) s.mode.coerceIn(0,2) else mode.selectedItemPosition.coerceIn(0,2)
        ui.update(muteValue,if(!s.running) "连接后启用" else when(selectedMode) {
            1 -> "使用外接负载"
            2 -> if(s.muted) "媒体静音已开启" else "正在检查静音"
            else -> if(s.volume==0) "媒体音量为零" else "正在检查音量"
        })
        ui.update(channelValue,if(s.running) { if(s.channels==2) "立体声" else "单声道" } else if(stereo.isChecked) "优先立体声" else "单声道")
        ui.update(sleepValue,s.sleepRemainingMS?.let { "剩余 "+((it+59999)/60000)+" 分钟" }
            ?: if(sleep.selectedItemPosition==0) "关闭" else if(s.running) "下次连接生效" else sleepMinutes[sleep.selectedItemPosition].toString()+" 分钟")
        if(diagnosticDialog?.isShowing==true) {
            val detail=s.text+"\n"+s.connection+"\n"+(s.elapsedMS/1000)+" 秒 · 采集 "+s.packets+" / UDP 已发 "+s.sent+" · "+"%.1f".format(s.db)+" dBFS\n媒体音量 "+s.volume+" / mute="+s.muted+
                "\n可用输出类型："+s.outputTypes+"（不等于其他 App 实际路由）\n日志："+s.logDirectory+
                "\n日志丢弃 "+s.loggingDropped+" 行 / "+when {
                    s.loggingFailed && s.running -> "写入失败，采集继续"
                    s.loggingFailed -> "写入失败，日志已关闭"
                    s.loggingComplete -> "日志已关闭"
                    s.sessionID==0L -> "未启用"
                    !s.running -> "后台排空中"
                    else -> "后台写入"
                }+"\n存档："+s.archiveStatus+"\n系统状态："+s.inspectMessage
            if(status.text.toString()!=detail) status.text=detail
        }
    }
    private fun showOnboarding() {
        val receive=AppRole.current(this)==AudioRole.RECEIVE
        val content=ui.column().apply { setPadding(dp(24),dp(26),dp(24),dp(12)) }
        content.addView(ui.iconTile(R.drawable.ic_wave)); ui.gap(content,18)
        content.addView(ui.text(if(receive) "让这台 Android，\n接收你的声音。" else "手机照常用，\n声音随你听。",25f,bold=true)); ui.gap(content,20)
        fun step(number:String,title:String,detail:String) {
            val row=LinearLayout(this).apply { gravity=android.view.Gravity.TOP }
            row.addView(ui.text(number,15f,bold=true).apply { setTextColor(getColor(R.color.accent)); setPadding(0,dp(2),dp(14),0) })
            val words=ui.column()
            words.addView(ui.text(title,16f,bold=true)); ui.gap(words,5); words.addView(ui.text(detail,13f,true))
            row.addView(words,LinearLayout.LayoutParams(0,-2,1f)); content.addView(row); ui.gap(content,20)
        }
        if(receive) {
            step("1","耳机连接这台设备","两机连接同一 Wi-Fi 或热点。本机默认使用耳机，无耳机时静音；每个新连接会恢复耳机默认。")
            step("2","开始接收并允许配对","到首页开始接收，在发端选择本机；首次请求需在本机允许。接收不需要录音权限或屏幕采集授权。")
            step("3","输出与锁屏控制","首页可暂停、恢复或显式确认本次扬声器外放。通知与锁屏媒体控件可暂停、恢复；系统或厂商仍可能限制后台运行。")
        } else {
            step("1","准备好接收端","两机连接同一 Wi-Fi 或热点；耳机连接接收端，并开始接收。")
            step("2","连接并确认","首次在接收端允许配对。发送需要录音权限与系统采集授权；跨 App 使用时请选择整个屏幕。")
            step("3","锁屏与后台","开始播放后可锁屏；设置里有后台和快捷开关指引。系统或厂商仍可能限制后台运行。")
        }
        content.addView(ui.text(if(receive) "接收仅播放发端传来的音频。音频未加密，仅在可信局域网使用；播放头推进不代表耳机可听，实际听感需试听。"
            else "只采集允许的媒体音频，不创建视频流或读取麦克风。音频未加密，仅在可信局域网使用；实际听感仍需试听。",12f,true))
        AlertDialog.Builder(this).setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("开始使用") { _,_ -> markGuideSeen() }
            .setNegativeButton("跳过") { _,_ -> markGuideSeen() }
            .setOnCancelListener { markGuideSeen() }.show()
    }
    private fun markGuideSeen() {
        getSharedPreferences("lab",0).edit().putBoolean(if(AppRole.current(this)==AudioRole.RECEIVE) "receiveGuideSeen" else "guideSeen",true).apply()
    }
    private fun addTile() {
        if(Build.VERSION.SDK_INT>=33) getSystemService(StatusBarManager::class.java).requestAddTileService(ComponentName(this,CaptureTileService::class.java),"HyperAudio",Icon.createWithResource(this,R.drawable.lab_icon),mainExecutor) { result ->
            toast(when(result) { StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "已添加快捷开关"; StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "快捷开关已存在"; else -> "可下拉快捷面板，编辑并拖入 HyperAudio" })
        } else toast("下拉快捷面板，编辑并拖入 HyperAudio")
    }
    private fun saveReport() {
        val version=packageManager.getPackageInfo(packageName,0).versionName ?: "unknown"
        val platform="Android ${Build.VERSION.SDK_INT}"
        reportPending=if(AppRole.current(this)==AudioRole.RECEIVE) ReceiverDiagnostics.export(ReceiverState.snapshot,version,platform)
            else DiagnosticReport.export(LabState.snapshot,version,platform)
        runCatching { startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE,"HyperAudio-report.json"),20) }
            .onFailure { reportPending=null; toast("无法打开系统保存器：${it.javaClass.simpleName}") }
    }
    private fun startAutomatic() {
        if(!ready() || !prepareCapture()) return
        retained.flow.startAutomatic()
    }
    private fun onConnectionChanged() {
        if(AppRole.current(this)!=AudioRole.SEND)return
        val flow=retained.flow
        busy=pending!=null || flow.busy
        if(flow.phase==ConnectionPhase.CHOOSING && choiceDialog?.isShowing!=true) {
            choiceDialog=AlertDialog.Builder(this).setTitle("选择 接收端")
                .setItems(flow.candidates.map { "${it.name} (${it.host})" }.toTypedArray()) { _,which -> flow.choose(which) }
                .setOnCancelListener { flow.cancel() }.show()
        }
        flow.takeReady()?.let { info -> busy=true;connected(info) }
        renderSnapshot()
    }
    private fun connected(info: ConnectionInfo) {
        pending=info; connectionStatus.text=getString(R.string.connected,info.name); authorize()
    }
    private fun consumeTileIntent() {
        if(intent?.action==ReceiverService.OPEN_RECEIVER){intent.action=null;applyRole();showPage(0);return}
        if (intent?.action != "CONNECT_FROM_TILE") return
        intent.action=null
        handler.post { if(AppRole.current(this)==AudioRole.RECEIVE)receiverPanel.start() else startAutomatic() }
    }
    override fun onNewIntent(next: Intent) { super.onNewIntent(next); setIntent(next); consumeTileIntent() }
    private fun backgroundSummary(): String {
        val receive=AppRole.current(this)==AudioRole.RECEIVE
        val notifications=getSystemService(android.app.NotificationManager::class.java)
        val notify=notifications.areNotificationsEnabled() &&
            notifications.getNotificationChannel(if(receive) "receiver" else "capture")?.importance != android.app.NotificationManager.IMPORTANCE_NONE
        val unrestricted=getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        val authorization=if(receive) "接收无需录音权限或屏幕采集授权" else
            "录音权限："+if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) "已允许" else "未允许"
        return authorization+" · 通知："+(if(notify) "已允许" else "未允许")+"\n"+
            "电池优化："+(if(unrestricted) "已豁免" else "未豁免，后台可能受限")+"；厂商自启动需手动检查。"
    }
    private fun openSettings(intent: Intent) {
        runCatching { startActivity(intent) }.onFailure { toast("无法打开此设置，请在系统设置中搜索本应用") }
    }
    private fun showBackgroundGuide() {
        val receive=AppRole.current(this)==AudioRole.RECEIVE
        val roleHelp=if(receive) "接收可用通知与锁屏媒体控件暂停、恢复。默认耳机；扬声器需在首页显式确认，每个新连接恢复耳机默认。系统或厂商仍可能限制后台运行。"
            else "发送需要录音权限与系统采集授权。系统撤销采集授权后，必须回到界面重新确认；设置无法绕过系统授权。"
        AlertDialog.Builder(this).setTitle("后台与锁屏设置")
            .setMessage(backgroundSummary()+"\n\nHyperOS：设置 → 应用设置 → 授权管理 → 自启动管理，允许 HyperAudio；应用电量管理选择“无限制”。路径随系统版本变化，需手动确认。\n"+roleHelp)
            .setPositiveButton("打开设置选项") { _, _ ->
                AlertDialog.Builder(this).setItems(arrayOf(if(receive) "申请通知权限" else "申请录音/通知权限","应用详情（权限与自启动）","电池优化设置","本应用通知设置")) { _, which ->
                    when (which) {
                        0 -> {
                            val missing=mutableListOf<String>()
                            if(!receive && checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.RECORD_AUDIO)
                            if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.POST_NOTIFICATIONS)
                            if(missing.isNotEmpty()) requestPermissions(missing.toTypedArray(),11)
                            else toast(if(receive) "无需再申请通知权限；若通知被关闭，请打开通知设置" else "录音与通知权限已允许；若通知被关闭，请打开通知设置")
                        }
                        1 -> openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName")))
                        2 -> openSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        3 -> openSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,packageName))
                    }
                }.show()
            }.setNegativeButton("关闭",null).show()
    }
    private fun showDiagnostics() {
        if(diagnosticDialog?.isShowing==true) return
        val receive=AppRole.current(this)==AudioRole.RECEIVE
        val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(8),dp(20),dp(16)) }
        diagnosticStatus=label(box,if(receive) "读取本机接收状态与系统媒体音量；不发声、不改变接收，也不进行网络探测。" else "检查接收状态、网络与蓝牙输出；不会播放声音或改变采集。")
        diagnosticStatus.setTextIsSelectable(true)
        label(box,"本机实时详细状态（不包含在脱敏报告中）")
        status=label(box,""); status.setTextIsSelectable(true)
        action(box,"一键自检") { runDiagnostics() }
        action(box,"取消自检") { diagnostics.cancel();diagnosticStatus.text="自检已取消；下方仍显示实时状态" }
        diagnosticDialog=AlertDialog.Builder(this).setTitle(if(receive) "接收自检与统计" else "自检详情与统计")
            .setView(ScrollView(this).apply { addView(box) }).setPositiveButton("关闭",null)
            .setOnDismissListener { diagnostics.cancel() }.show()
        runDiagnostics(); renderSnapshot()
    }
    private fun receiverDiagnosticDetail():String {
        val audio=getSystemService(AudioManager::class.java)
        return ReceiverDiagnostics.detail(ReceiverState.snapshot,audio.getStreamVolume(AudioManager.STREAM_MUSIC),
            audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),audio.isStreamMute(AudioManager.STREAM_MUSIC))
    }
    private fun runDiagnostics() {
        diagnostics.cancel()
        if(AppRole.current(this)==AudioRole.RECEIVE) {
            diagnosticStatus.text=backgroundSummary()+"\n本机接收状态已读取；详情会随界面刷新。耳机实际有声需人工确认。"
            ui.update(status,receiverDiagnosticDetail())
            return
        }
        val roleEpoch=AppRole.epoch
        val snapshot = LabState.snapshot
        val cm = getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        val network = if (cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED) == true })
            "有可用网络（不证明收端可达）" else "未检测到可用网络"
        val endpoint = if (snapshot.running) snapshot.endpoint else auto.rememberedEndpoint()
        val paired = endpoint?.expectedID?.let { diagnostics.hasKey(it) } == true
        val age = if (snapshot.metricAt == 0L) 0 else SystemClock.elapsedRealtime()-snapshot.metricAt
        val local = backgroundSummary()+"\n网络：$network\n已配对：${if (paired) "是" else "否（手动 token 不含认证密钥）"}\n"+
            "服务：${DiagnosticPolicy.capture(snapshot.running,snapshot.paused,snapshot.packets,snapshot.db,age)}\n"+
            (if (snapshot.running && snapshot.packets > 0) "最新电平 ${"%.1f".format(snapshot.db)} dBFS（${age/1000} 秒前） · 采集 ${snapshot.packets} / UDP已发 ${snapshot.sent} 包\n"
            else "电平与收包链路：未测试（尚未获得采集数据）\n")+
            "耳机实际有声与全机静音：需人工确认；自检不播放测试音。"
        diagnosticStatus.text=local
        if (!paired) { diagnosticStatus.append("\n接收端认证状态：未测试（无已配对接收端）"); return }
        diagnosticStatus.append("\n接收端认证状态：检查中（6 秒内结束）…")
        diagnostics.inspect(endpoint) { result ->
            if(AppRole.current(this)!=AudioRole.SEND || AppRole.epoch!=roleEpoch || diagnosticDialog?.isShowing!=true) return@inspect
            val report=local+result.fold({ phone ->
                "\n接收端认证状态：${phone.name} · ${if (phone.running) "接收中" else "未运行"}${if (phone.paused) " / 已暂停" else ""}\n"+
                    "接收计数 ${phone.received} · 最近包 ${phone.lastPacketAgeMs} ms · 队列 ${phone.queuedMS} ms\n"+
                    "蓝牙输出：${if (phone.bluetooth) "已检测到" else "未检测到"}（不证明耳机有声）"+
                    if (!snapshot.running) "\n本次未采集；以上收包为接收端历史状态，链路未测试。" else ""
            },{ "\n接收端认证状态：${it.message ?: "网络超时或接收端不可达"}" })
            diagnosticStatus.text=report
        }
    }
    private fun ready(): Boolean {
        if(AppRole.current(this)!=AudioRole.SEND || ReceiverState.snapshot.running){toast("请先选择发送声音并停止接收");return false}
        if (LabState.running) { toast("请先停止当前采集"); return false }
        if (busy) { toast("正在连接或等待授权；可先点取消"); return false }
        return true
    }
    private fun prepareCapture(): Boolean {
        return runCatching {
            val audio = getSystemService(AudioManager::class.java)
            when (mode.selectedItemPosition) {
                0 -> audio.setStreamVolume(AudioManager.STREAM_MUSIC,0,0)
                2 -> if (!audio.isStreamMute(AudioManager.STREAM_MUSIC))
                    audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,AudioManager.ADJUST_MUTE,0)
            }
            true
        }.getOrElse { toast("准备静音失败：${it.message}"); false }
    }
    private fun connect(endpoint: ReceiverEndpoint) { retained.flow.connect(endpoint) }
    private fun idle(message: String) { busy = false; connectButton.isEnabled = true; connectionStatus.text = message }
    private fun toast(s: String) { Toast.makeText(this, s, Toast.LENGTH_LONG).show() }
    private fun authorize() {
        if(AppRole.current(this)!=AudioRole.SEND){pending=null;return}
        if (pending == null) { idle("连接信息已失效，请重试"); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        else consent()
    }
    private fun consent() {
        if(AppRole.current(this)!=AudioRole.SEND || pending==null)return
        consentEpoch=AppRole.epoch
        projectionRequest=nextProjectionRequest++
        if(nextProjectionRequest>=32767)nextProjectionRequest=100
        startActivityForResult(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(), projectionRequest)
    }
    override fun onRequestPermissionsResult(r: Int, p: Array<out String>, g: IntArray) {
        super.onRequestPermissionsResult(r,p,g)
        if (r == 1) {
            if (g.firstOrNull() == PackageManager.PERMISSION_GRANTED && pending != null) consent()
            else { pending = null; idle("未授予录音权限；可重新连接授权") }
        }
    }
    @Deprecated("Activity callback retained to avoid extra dependencies")
    override fun onActivityResult(r: Int, c: Int, data: Intent?) {
        super.onActivityResult(r,c,data)
        if(r==20) {
            val report=reportPending; reportPending=null
            if(c==RESULT_OK && data?.data!=null && report!=null) {
                val uri=data.data!!
                Thread({
                    val result=runCatching { contentResolver.openOutputStream(uri)?.use { it.write(report.toByteArray(Charsets.UTF_8)) } ?: error("保存器未返回可写文件") }
                    handler.post { toast(if(result.isSuccess) "脱敏报告已保存；未自动上传" else "保存失败：${result.exceptionOrNull()?.javaClass?.simpleName}") }
                },"HyperAudio-report").start()
            }
            return
        }
        if (r != projectionRequest) return
        projectionRequest=-1
        if(AppRole.current(this)!=AudioRole.SEND || consentEpoch!=AppRole.epoch){pending=null;busy=false;return}
        val info = pending
        pending = null
        if (c != RESULT_OK || data == null || info == null) { idle("已取消授权；可重新一键连接"); return }
        getSharedPreferences("lab", 0).edit().putString("host", info.host).putString("token",info.token).putInt("mode",mode.selectedItemPosition).apply()
        val i = Intent(this, CaptureService::class.java).putExtra("roleEpoch",consentEpoch).putExtra("consent", data).putExtra("code",c)
            .putExtra("host",info.host).putExtra("token",info.token).putExtra("network",info.network)
            .putExtra("name",info.name).putExtra("receiverID",info.receiverID).putExtra("controlPort",info.controlPort)
            .putExtra("mode",mode.selectedItemPosition).putExtra("save",save.isChecked)
            .putExtra("supportsStereo",info.supportsStereo).putExtra("preferStereo",stereo.isChecked)
            .putExtra("sleepMinutes",sleepMinutes[sleep.selectedItemPosition])
        startForegroundService(i)
        idle("已开始向 ${info.name} 发送；网络断开将尝试恢复；系统撤销授权后需重新连接。")
    }
    override fun onSaveInstanceState(out: Bundle) {
        out.putInt("page",selectedPage); out.putInt("modeChoice",mode.selectedItemPosition)
        out.putBoolean("savePCM",save.isChecked); out.putString("manualHost",host.text.toString()); out.putString("manualToken",token.text.toString())
        out.putInt("projectionRequest",projectionRequest);out.putInt("nextProjectionRequest",nextProjectionRequest)
        out.putLong("consentEpoch",consentEpoch)
        out.putString("reportPending",reportPending)
        out.putInt("sleepSelection",sleep.selectedItemPosition)
        pending?.let { out.putString("pendingHost",it.host); out.putString("pendingToken",it.token); out.putString("pendingName",it.name); out.putParcelable("pendingNetwork",it.network); out.putString("pendingID",it.receiverID); out.putInt("pendingPort",it.controlPort); out.putBoolean("pendingStereo",it.supportsStereo) }
        super.onSaveInstanceState(out)
    }
    override fun onResume() {
        super.onResume()
        applyRole()
        retained.flow.attach { onConnectionChanged() }; handler.post(poll)
    }

    override fun onWindowFocusChanged(hasFocus:Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if(hasFocus && ::ui.isInitialized) window.decorView.post { ui.systemBars(window) }
    }
    override fun onPause() {
        retained.flow.detach(); choiceDialog?.dismiss(); choiceDialog=null
        handler.removeCallbacks(poll); super.onPause()
    }
    @Deprecated("Native retained holder keeps only application context")
    override fun onRetainNonConfigurationInstance(): Any = retained
    override fun onDestroy() {
        retained.flow.detach()
        if(!isChangingConfigurations) retained.close()
        diagnosticDialog?.dismiss(); diagnostics.close(); super.onDestroy()
    }
}
