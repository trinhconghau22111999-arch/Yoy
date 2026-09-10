package com.ytbrowser.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat

/**
 * Service chạy foreground trong lúc video/nhạc đang phát.
 *
 * Lý do cần cái này: khi tắt màn hình, Android (đặc biệt các hãng như Xiaomi/Oppo/Vivo với
 * trình quản lý pin riêng) sẽ nhanh chóng đóng băng hoặc kill hẳn tiến trình của app nếu app
 * không có gì "chính đáng" đang chạy ở foreground. Bản thân WAKE_LOCK không đủ để ngăn việc
 * này trên nhiều máy. Một Foreground Service loại mediaPlayback + thông báo (notification) là
 * tín hiệu chính thức mà hệ thống công nhận là "app đang phát media thật", nhờ đó được đối xử
 * khoan dung hơn nhiều so với app chạy nền bình thường.
 *
 * Notification dùng MediaStyle với 3 nút: Lùi 10s / Phát-Tạm dừng / Tới 10s - hiện được cả trên
 * màn hình khoá (đây là kiểu media-control chuẩn của Android, không phải action button thường).
 * Bấm nút không tự phát/tua video trực tiếp ở đây - Service chỉ phát 1 broadcast nội bộ
 * (ACTION_CONTROL), MainActivity lắng nghe rồi chạy JS lên đúng thẻ <video> trong WebView, vì
 * video thực sự đang phát trong WebView của Activity, không phải trong Service này.
 *
 * THEO YÊU CẦU: thông báo CHỈ hiện khi màn hình đang TẮT (đúng lúc cần điều khiển từ màn hình
 * khoá) - lúc màn hình BẬT (dù đang dùng app khác đè lên trên, hay đang ở màn hình chính, hay
 * đang ở ngay trong app này) thì ẨN thông báo đi, không hiện trên thanh trạng thái/khay thông
 * báo nữa. KHÔNG dừng hẳn Service hay huỷ MediaSession lúc ẩn - chỉ gọi stopForeground() để rút
 * gọn thông báo (Service vẫn sống, vẫn nhận được nút bấm từ tai nghe Bluetooth...), rồi
 * startForeground() lại đúng lúc màn hình tắt tiếp theo. Xem [ScreenStateReceiver] bên dưới.
 */
class PlaybackService : Service() {

    companion object {
        private const val CHANNEL_ID = "playback_channel_v2"
        private const val NOTIF_ID = 1001

        // Service tự cập nhật trạng thái phát/tạm dừng (đổi icon nút giữa) khi MainActivity
        // báo về qua các sự kiện video thật (playing/pause) - không chỉ dựa vào việc người dùng
        // bấm nút trên notification.
        const val ACTION_UPDATE_STATE = "com.ytbrowser.app.action.UPDATE_STATE"
        const val EXTRA_IS_PLAYING = "extra_is_playing"

        // Lệnh điều khiển gửi NGƯỢC LẠI cho MainActivity để chạy JS lên <video> thật.
        const val ACTION_CONTROL = "com.ytbrowser.app.action.CONTROL_PLAYBACK"
        const val EXTRA_COMMAND = "command"
        const val COMMAND_PLAY = "play"
        const val COMMAND_PAUSE = "pause"
        const val COMMAND_SEEK_FORWARD = "seek_forward"
        const val COMMAND_SEEK_BACKWARD = "seek_backward"

        private const val SEEK_ACTIONS = PlaybackStateCompat.ACTION_FAST_FORWARD or
            PlaybackStateCompat.ACTION_REWIND
        private const val SUPPORTED_ACTIONS = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            SEEK_ACTIONS
    }

    private lateinit var mediaSession: MediaSessionCompat
    private var isPlaying = true

    // Nghe sự kiện màn hình BẬT/TẮT của hệ thống - dùng đúng 2 tín hiệu chuẩn của Android
    // (ACTION_SCREEN_ON/OFF), KHÔNG tự đoán qua cách nào khác. Đăng ký trong onCreate(), huỷ
    // đăng ký trong onDestroy() để tránh rò rỉ receiver khi Service bị dừng hẳn.
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> postForegroundNotification()
                Intent.ACTION_SCREEN_ON -> hideNotificationKeepServiceAlive()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        mediaSession = MediaSessionCompat(this, "TubeForMePlayback").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    sendControlCommand(COMMAND_PLAY)
                    updatePlaybackState(true)
                    postForegroundNotification()
                }

                override fun onPause() {
                    sendControlCommand(COMMAND_PAUSE)
                    updatePlaybackState(false)
                    postForegroundNotification()
                }

                override fun onFastForward() {
                    sendControlCommand(COMMAND_SEEK_FORWARD)
                }

                override fun onRewind() {
                    sendControlCommand(COMMAND_SEEK_BACKWARD)
                }
            })
            isActive = true
        }
        updatePlaybackState(true)
        registerReceiver(screenStateReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannelIfNeeded()

        when (intent?.action) {
            // Nút bấm trên notification/màn hình khoá (hoặc nút media vật lý trên tai nghe) đi
            // qua đường này - MediaButtonReceiver tự dịch sang đúng callback ở trên.
            Intent.ACTION_MEDIA_BUTTON -> MediaButtonReceiver.handleIntent(mediaSession, intent)
            // MainActivity báo trạng thái phát/tạm dừng THẬT của video (vd người dùng bấm nút
            // play/pause ngay trong giao diện YouTube, không qua notification).
            ACTION_UPDATE_STATE -> updatePlaybackState(
                intent.getBooleanExtra(EXTRA_IS_PLAYING, isPlaying)
            )
        }

        // Bắt buộc phải startForeground() ngay trong onStartCommand() lần khởi tạo đầu tiên (yêu
        // cầu của Android với mọi Foreground Service, nếu không sẽ crash ANR "did not call
        // startForeground"). Những lần SAU đó (service đã ở foreground rồi), nếu màn hình đang
        // BẬT thì rút gọn lại ngay - không cần giữ thông báo hiện ra chỉ vì onStartCommand() vừa
        // chạy lại (vd người dùng bấm play/pause ngay trong app trong lúc màn hình đang bật).
        postForegroundNotification()
        if (isScreenOn()) hideNotificationKeepServiceAlive()
        return START_STICKY
    }

    private fun isScreenOn(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        return pm?.isInteractive ?: true
    }

    /** Rút gọn thông báo (ẩn khỏi thanh trạng thái/khay thông báo) nhưng GIỮ NGUYÊN Service đang
     *  chạy và MediaSession vẫn "active" - không xoá/dừng gì cả, chỉ là không còn HIỂN THỊ ra
     *  cho người dùng thấy nữa lúc màn hình đang bật. */
    private fun hideNotificationKeepServiceAlive() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun sendControlCommand(command: String) {
        sendBroadcast(
            Intent(ACTION_CONTROL).apply {
                setPackage(packageName)
                putExtra(EXTRA_COMMAND, command)
            }
        )
    }

    private fun updatePlaybackState(playing: Boolean) {
        isPlaying = playing
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(SUPPORTED_ACTIONS)
                .setState(
                    if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                    1f
                )
                .build()
        )
    }

    private fun postForegroundNotification() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Phát nền",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    // Đây chỉ là thông báo trạng thái ("đang giữ tiến trình sống" + điều khiển
                    // phát), không phải cảnh báo cần chú ý - tắt hẳn rung/âm thanh/badge để
                    // không làm phiền, kể cả trên các ROM hay tự ý rung ở mức LOW.
                    enableVibration(false)
                    vibrationPattern = null
                    setSound(null, null)
                    setShowBadge(false)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    private fun buildNotification(): Notification {
        val rewindAction = NotificationCompat.Action(
            android.R.drawable.ic_media_rew,
            "Lùi 10 giây",
            MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_REWIND)
        )
        val playPauseAction = if (isPlaying) {
            NotificationCompat.Action(
                android.R.drawable.ic_media_pause,
                "Tạm dừng",
                MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PAUSE)
            )
        } else {
            NotificationCompat.Action(
                android.R.drawable.ic_media_play,
                "Phát",
                MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PLAY)
            )
        }
        val forwardAction = NotificationCompat.Action(
            android.R.drawable.ic_media_ff,
            "Tới 10 giây",
            MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_FAST_FORWARD)
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Tube for me")
            .setContentText(if (isPlaying) "Đang phát nền" else "Đã tạm dừng")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // Đã đăng lại (vd đổi icon play/pause) hay bị hệ thống gọi lại onStartCommand nhiều
            // lần cũng chỉ báo/rung ở LẦN ĐẦU - không rung/kêu lại các lần sau.
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(rewindAction)
            .addAction(playPauseAction)
            .addAction(forwardAction)
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenStateReceiver) }
        mediaSession.isActive = false
        mediaSession.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
