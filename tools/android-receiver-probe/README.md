# Android接收原生验证

唯一测试源码位于android/app/src/androidTest/java/lab/hyperaudio/ReceiverProbeInstrumentation.kt。它是独立测试APK中的标准android.app.Instrumentation，不进入Release主APK，也没有向产品增加测试后门。

本机用既有JDK17、Android SDK35与GRADLE_USER_HOME，运行 `bash ./gradlew --offline :app:assembleDebug :app:assembleDebugAndroidTest`；将app-debug.apk和app-debug-androidTest.apk覆盖安装到专用API35模拟器，再运行：

```
adb -s emulator-5554 shell am instrument -w lab.hyperaudio.test/lab.hyperaudio.ReceiverProbeInstrumentation
```

必须同时看到ALL_RECEIVER_NATIVE_CHECKS_PASSED与INSTRUMENTATION_CODE: -1，不能只看adb退出码（失败时也可能退出0）。测试要求RECORD_AUDIO未授权、无实际耳机；使用显式扬声器和静音参考轨道，发送低电平PCM。通过生产服务与真实TCP/UDP/AudioTrack验证配对拒绝/批准、错token/超长包、单双声道、head推进、busy、暂停/认证重连/恢复、断流、端口与角色释放、以及manual来源从127.0.0.1到127.0.0.2后不继承扬声器。测试自行停止服务并恢复原角色。

优化Release与release测试APK的R8映射存在Kotlin Result字段错误，未将该轮视为功能通过，未为测试而给生产加keep规则。实际链路以Debug原生结果为准；优化Release另验证构建/签名/覆盖安装与启动。模拟器AudioTrack推进不等价两台真实手机或蓝牙耳机实际可听；真实耳机插拔、来电/焦点、HyperOS后台和耗电需实测。
