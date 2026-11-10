package dev.hmg.pleoskit

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * 차량 신호와 내비 이벤트를 받는 로직을 올려 두는 포그라운드 서비스의 바탕.
 *
 * Activity만 있는 앱은 내비 앱이 화면 앞으로 오면 cached 프로세스가 되어 Android 14 freezer에 얼려지고,
 * 그동안 SDK 이벤트를 받지 못한다. 주행 중 계속 동작해야 하는 로직은 이 서비스를 상속한 서비스에 둔다.
 *
 * 앱 Manifest에 서비스를 선언해야 한다.
 * ```xml
 * <service android:name=".DriveService" android:exported="false" android:foregroundServiceType="specialUse">
 *     <property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="용도 설명" />
 * </service>
 * ```
 */
abstract class PleosForegroundService : Service() {

    /** 서비스가 살아 있는 동안 유지되는 스코프. 자식 하나가 실패해도 나머지는 계속 돈다. */
    protected val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 상태 표시줄 알림 제목. */
    protected abstract val notificationTitle: String

    protected open val notificationChannelId: String = "pleos_drive"
    protected open val notificationIcon: Int = android.R.drawable.stat_notify_sync
    protected open val foregroundType: Int = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE

    private var started = false

    /** 서비스가 처음 시작될 때 한 번 불린다. kit 연결과 신호 수집은 여기서 [serviceScope]로 시작한다. */
    protected abstract fun onServiceStarted()

    final override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification(), foregroundType)
        if (!started) {
            started = true
            onServiceStarted()
        }
        onCommand(intent)
        return START_STICKY
    }

    /** `startForegroundService`로 들어온 인텐트를 처리한다. 필요할 때만 재정의한다. */
    protected open fun onCommand(intent: Intent?) = Unit

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(notificationChannelId, notificationTitle, NotificationManager.IMPORTANCE_LOW),
        )
        return Notification.Builder(this, notificationChannelId)
            .setSmallIcon(notificationIcon)
            .setContentTitle(notificationTitle)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 1

        /** Activity가 화면에 있을 때 호출한다(백그라운드에서는 포그라운드 서비스를 시작할 수 없다). */
        fun start(context: Context, service: Class<out PleosForegroundService>, intent: Intent? = null) {
            val i = (intent ?: Intent()).setClass(context, service)
            context.startForegroundService(i)
        }
    }
}
