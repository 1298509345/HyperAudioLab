package lab.hyperaudio

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowInsetsController
import android.os.Build
import android.content.res.Configuration
import android.widget.*

/** Local, static presentation components. No connection or audio ownership. */
class AndroidUi(private val context: Context) {
    fun systemBars(window:Window) {
        val dark=context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        if(Build.VERSION.SDK_INT>=30) {
            val mask=WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if(dark) 0 else mask,mask)
        } else {
            @Suppress("DEPRECATION")
            val mask=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility=if(dark) window.decorView.systemUiVisibility and mask.inv() else window.decorView.systemUiVisibility or mask
        }
    }
    fun update(label:TextView,value:String) { if(label.text.toString()!=value) label.text=value }
    fun dp(n:Int)=(n*context.resources.displayMetrics.density).toInt()
    fun color(id:Int)=context.getColor(id)
    fun shape(color:Int,radius:Int=16)=GradientDrawable().apply {
        setColor(color); cornerRadius=dp(radius).toFloat()
    }
    fun text(value:String,size:Float=14f,muted:Boolean=false,bold:Boolean=false)=TextView(context).apply {
        text=value; textSize=size; setTextColor(color(if(muted) R.color.secondary else R.color.ink))
        typeface=Typeface.create(if(bold) "sans-serif-medium" else "sans-serif",Typeface.NORMAL)
        includeFontPadding=false; setLineSpacing(dp(3).toFloat(),1f)
    }
    fun column()=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL }
    fun gap(parent:LinearLayout,n:Int) { parent.addView(View(context),LinearLayout.LayoutParams(1,dp(n))) }
    fun header(parent:LinearLayout,title:String,subtitle:String) {
        val row=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL }
        row.addView(text(title,28f,bold=true),LinearLayout.LayoutParams(0,-2,1f))
        if(title=="HyperAudio") row.addView(text("发端",12f,true).apply { setPadding(dp(10),dp(5),dp(10),dp(5)); background=shape(color(R.color.accent_soft),9) })
        parent.addView(row); gap(parent,10); parent.addView(text(subtitle,14f,true)); gap(parent,24)
    }
    fun heading(parent:LinearLayout,value:String) {
        parent.addView(text(value,13f,true,true).apply { setPadding(dp(4),dp(4),0,dp(10)) })
    }
    fun card(parent:LinearLayout)=column().apply {
        setPadding(dp(20),dp(18),dp(20),dp(18)); background=shape(color(R.color.surface),24)
        parent.addView(this,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(16) })
    }
    fun button(value:String,primary:Boolean=false,quiet:Boolean=false)=Button(context).apply {
        text=value; textSize=15f; isAllCaps=false; typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
        minHeight=0; minimumHeight=dp(52); minWidth=0; minimumWidth=0
        setPadding(dp(16),dp(10),dp(16),dp(10)); gravity=Gravity.CENTER
        stateListAnimator=null; elevation=0f; backgroundTintList=null
        val fill=color(if(primary) R.color.accent else if(quiet) R.color.transparent else R.color.control_surface)
        val states=StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled),shape(color(R.color.disabled_surface)))
            addState(intArrayOf(),RippleDrawable(ColorStateList.valueOf(color(R.color.ripple)),shape(fill),null))
        }
        background=states
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled),intArrayOf()),
            intArrayOf(color(R.color.secondary),color(if(primary) R.color.on_accent else R.color.accent))))
    }
    fun action(parent:LinearLayout,value:String,primary:Boolean=false,block:()->Unit):Button = button(value,primary).apply {
        setOnClickListener { block() }; parent.addView(this,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(8) })
    }
    fun icon(id:Int,size:Int=24)=ImageView(context).apply {
        setImageResource(id); setColorFilter(color(R.color.accent)); importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams=LinearLayout.LayoutParams(dp(size),dp(size))
    }
    fun iconTile(id:Int)=FrameLayout(context).apply {
        background=shape(color(R.color.accent_soft),13)
        addView(icon(id),FrameLayout.LayoutParams(dp(25),dp(25),Gravity.CENTER))
        layoutParams=LinearLayout.LayoutParams(dp(46),dp(46))
    }
    class Row(val view:LinearLayout,val value:TextView,val trailing:LinearLayout)
    fun row(parent:LinearLayout,title:String,subtitle:String="",value:String="",icon:Int=0,block:(()->Unit)?=null):Row {
        val row=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL; minimumHeight=dp(58); setPadding(0,dp(11),0,dp(11)) }
        if(icon!=0) row.addView(iconTile(icon).apply { layoutParams=LinearLayout.LayoutParams(dp(36),dp(36)).apply { rightMargin=dp(12) } })
        val words=column(); words.addView(text(title,15f,bold=false))
        if(subtitle.isNotEmpty()) words.addView(text(subtitle,12f,true).apply { setPadding(0,dp(5),0,0) })
        row.addView(words,LinearLayout.LayoutParams(0,-2,1f))
        val tail=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL; setPadding(dp(8),0,0,0) }
        val detail=text(value,12f,true).apply { gravity=Gravity.END; maxWidth=dp(126); visibility=if(value.isEmpty()) View.GONE else View.VISIBLE }
        tail.addView(detail)
        if(block!=null) {
            tail.addView(this.icon(R.drawable.ic_chevron,18).apply { setColorFilter(color(R.color.secondary)); setPadding(dp(5),0,0,0) })
            row.isClickable=true; row.isFocusable=true
            row.background=RippleDrawable(ColorStateList.valueOf(color(R.color.ripple)),shape(color(R.color.transparent),10),shape(-1,10))
            row.setOnClickListener { block() }
        }
        row.addView(tail); parent.addView(row)
        return Row(row,detail,tail)
    }
    fun divider(parent:LinearLayout) {
        parent.addView(View(context).apply { setBackgroundColor(color(R.color.divider)) },LinearLayout.LayoutParams(-1,dp(1)))
    }
    fun toggle(title:String,checked:Boolean)=Switch(context).apply {
        id=View.generateViewId(); isChecked=checked; showText=false; contentDescription=title
        minimumHeight=dp(48); setPadding(dp(8),0,0,0)
        thumbTintList=null; trackTintList=null; splitTrack=false; switchMinWidth=dp(48)
        fun track(color:Int)=shape(color,20).apply { setSize(dp(48),dp(28)); setPadding(dp(2),dp(2),dp(2),dp(2)) }
        trackDrawable=StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked),track(color(R.color.accent)))
            addState(intArrayOf(),track(color(R.color.switch_track)))
        }
        fun thumb(color:Int)=shape(color,20).apply { setSize(dp(24),dp(24)) }
        thumbDrawable=StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked),thumb(color(R.color.on_accent)))
            addState(intArrayOf(),thumb(color(R.color.switch_thumb)))
        }
    }
    class NavItem(val view:LinearLayout,private val pill:FrameLayout,private val icon:ImageView,private val label:TextView,private val ui:AndroidUi) {
        fun select(selected:Boolean) {
            pill.background=ui.shape(ui.color(if(selected) R.color.accent_soft else R.color.transparent),18)
            icon.setColorFilter(ui.color(if(selected) R.color.accent else R.color.secondary))
            label.setTextColor(ui.color(if(selected) R.color.accent else R.color.secondary))
            view.isSelected=selected; view.contentDescription=label.text.toString()+if(selected) "，当前页面" else ""
        }
    }
    fun navigation(title:String,iconId:Int,action:()->Unit):NavItem {
        val root=column().apply { gravity=Gravity.CENTER; minimumHeight=dp(72); isClickable=true; isFocusable=true; setOnClickListener { action() } }
        val pill=FrameLayout(context)
        val image=icon(iconId); pill.addView(image,FrameLayout.LayoutParams(dp(23),dp(23),Gravity.CENTER))
        root.addView(pill,LinearLayout.LayoutParams(dp(60),dp(32)))
        val titleView=text(title,11f,true,true).apply { setPadding(0,dp(5),0,0); importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        titleView.gravity=Gravity.CENTER
        root.addView(titleView,LinearLayout.LayoutParams(-2,-2))
        return NavItem(root,pill,image,titleView,this)
    }
}
