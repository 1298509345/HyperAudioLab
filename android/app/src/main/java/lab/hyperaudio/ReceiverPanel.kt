package lab.hyperaudio

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Receiving UI owns no sender widgets or LabState writes. Snapshot polling is once per UI tick. */
internal class ReceiverPanel(private val activity:Activity,private val ui:AndroidUi) {
    val home=ui.column()
    val devices=ui.column()
    val settings=ui.column()
    private val deviceList=ui.column()
    private val title:TextView
    private val detail:TextView
    private val route:TextView
    private val manual:TextView
    private val pairing:LinearLayout
    private val pairText:TextView
    private val start:android.widget.Button
    private val pause:android.widget.Button
    private val stop:android.widget.Button
    private var shownRequest:String?=null
    private var deviceKey=""
    init {
        ui.header(home,"接收声音","让这台 Android 接收另一台设备的声音。")
        val hero=ui.card(home)
        title=ui.text("尚未开始接收",23f,bold=true);hero.addView(title);ui.gap(hero,10)
        detail=ui.text("先连接耳机，再点开始接收。无需录音或屏幕采集授权。",14f,true);hero.addView(detail);ui.gap(hero,16)
        start=ui.button("开始接收",primary=true).apply{setOnClickListener{start()}};hero.addView(start,LinearLayout.LayoutParams(-1,-2))
        val row=LinearLayout(activity)
        pause=ui.button("暂停声音",primary=true).apply{setOnClickListener{command(if(ReceiverState.snapshot.paused)ReceiverService.ACTION_RESUME else ReceiverService.ACTION_PAUSE)}}
        stop=ui.button("停止接收").apply{setOnClickListener{command(ReceiverService.ACTION_STOP)}}
        row.addView(pause,LinearLayout.LayoutParams(0,-2,1f));row.addView(stop,LinearLayout.LayoutParams(0,-2,1f));hero.addView(row)
        val output=ui.card(home);ui.heading(output,"播放到哪里")
        route=ui.text("静音 · 等待耳机",14f,true);output.addView(route)
        ui.action(output,"耳机（默认）"){command(ReceiverService.ACTION_HEADPHONES)}
        ui.action(output,"本次使用扬声器"){
            if(!ReceiverState.snapshot.running){toast("请先开始接收");return@action}
            AlertDialog.Builder(activity).setTitle("允许本次外放？").setMessage("收到的声音会从这台手机扬声器播放。下一次连接会恢复耳机默认。")
                .setPositiveButton("使用扬声器"){_,_->command(ReceiverService.ACTION_SPEAKER)}.setNegativeButton("取消",null).show()
        }
        pairing=ui.card(home);pairText=ui.text("",16f,bold=true);pairing.addView(pairText)
        ui.action(pairing,"允许首次配对",primary=true){shownRequest?.let{command(ReceiverService.ACTION_ALLOW,it)}}
        ui.action(pairing,"拒绝"){shownRequest?.let{command(ReceiverService.ACTION_DENY,it)}}
        val info=ui.card(home);ui.heading(info,"手动连接信息")
        manual=ui.text("开始接收后显示地址与本次配对码。",14f,true).apply{setTextIsSelectable(true)};info.addView(manual)
        info.addView(ui.text("在发端填写这里的地址和 8 位配对码；配对码只对本次接收有效。请只向信任的设备分享。",12f,true))
        ui.header(devices,"允许的发端","已允许的设备可认证重连；活动设备需先停止再解除配对。")
        devices.addView(deviceList)
        ui.header(settings,"接收设置","每个新会话都默认使用耳机。")
        val help=ui.card(settings);help.addView(ui.text("连接蓝牙媒体、有线或 USB 耳机后播放；无耳机时保持静音。来电或其他 App 抢占音频会让路；永久失去焦点后需手动恢复。",14f,true))
        ui.action(help,"通知权限") { if(Build.VERSION.SDK_INT>=33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),7) else toast("通知权限已允许，或此系统无需授权") }
        ui.action(help,"关于与赞助"){AboutSupportDialog.show(activity)}
        render()
    }
    private fun toast(value:String){android.widget.Toast.makeText(activity,value,android.widget.Toast.LENGTH_SHORT).show()}
    fun start(){
        if(AppRole.current(activity)!=AudioRole.RECEIVE)return
        if(Build.VERSION.SDK_INT>=33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),7)
        activity.startForegroundService(Intent(activity,ReceiverService::class.java).setAction(ReceiverService.ACTION_START).putExtra("roleEpoch",AppRole.epoch))
    }
    private fun command(action:String,request:String?=null){if(!ReceiverState.snapshot.running && action!=ReceiverService.ACTION_STOP){toast("请先开始接收");return};activity.startService(Intent(activity,ReceiverService::class.java).setAction(action).putExtra(ReceiverService.EXTRA_REQUEST_ID,request))}
    fun render(){
        val s=ReceiverState.snapshot
        ui.update(title,when(s.audioPhase){"stopped"->"尚未开始接收";"paused"->"接收声音已暂停";"playing"->"正在播放收到的声音";"buffering"->"正在缓冲音频";else->if(s.received>0)"等待音频重新连接" else "等待发端连接"})
        ui.update(detail,s.status+if(s.running)"\n已收 ${s.received} 包 · ${if(s.channels==2)"立体声" else "单声道"} · 缓冲 ${s.queuedMs}ms" else "\n点开始后，在发端选择这台设备。")
        ui.update(route,s.actualOutput+if(s.speaker)"\n本次已允许扬声器" else "\n未允许扬声器外放")
        start.visibility=if(s.running)View.GONE else View.VISIBLE;pause.visibility=if(s.running)View.VISIBLE else View.GONE;stop.visibility=pause.visibility
        ui.update(pause,if(s.paused)"恢复声音" else "暂停声音")
        pairing.visibility=if(s.pending!=null)View.VISIBLE else View.GONE;shownRequest=s.pending?.id
        s.pending?.let{ui.update(pairText,"允许 ${it.name} 向本机发送声音？\n请确认这是你正在操作的设备。")}
        ui.update(manual,if(s.running)"局域网地址：${s.addresses.ifBlank{"暂未找到，请连接局域网"}}\n配对码：${s.token}\nUDP 50005 · 自动发现 / TCP 50006" else "开始接收后显示地址与本次配对码。")
        val clients=if(s.running)s.clients else ReceiverStore(activity).clients()
        val key=clients.toString()+s.running+s.activeClient
        if(key!=deviceKey){deviceKey=key;deviceList.removeAllViews()
            if(clients.isEmpty())ui.card(deviceList).addView(ui.text("尚无允许的发端。开始接收后，首次请求会在连接页等待你确认。",14f,true))
            clients.forEach{c->val card=ui.card(deviceList);card.addView(ui.text(c.name,18f,bold=true));card.addView(ui.text(c.id,12f,true));ui.action(card,"解除配对"){
                if(s.running){toast("请先停止接收，再解除配对");return@action}
                AlertDialog.Builder(activity).setTitle("解除与 ${c.name} 的配对？").setMessage("下次连接需要你重新允许。")
                    .setPositiveButton("解除配对"){_,_->ReceiverStore(activity).forget(c.id);deviceKey="";render()}.setNegativeButton("取消",null).show()
            }}
        }
    }
}
