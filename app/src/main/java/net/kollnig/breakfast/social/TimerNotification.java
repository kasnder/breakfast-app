package net.kollnig.breakfast.social;

import net.kollnig.breakfast.*;
import net.kollnig.breakfast.calendar.*;
import net.kollnig.breakfast.dashboard.*;
import net.kollnig.breakfast.news.*;
import net.kollnig.breakfast.social.*;
import net.kollnig.breakfast.todoist.*;
import net.kollnig.breakfast.weather.*;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;

import androidx.core.app.NotificationCompat;

import java.util.Locale;

/**
 * Manages a persistent notification that shows the remaining time
 * for the social media break.  Updates every second while the timer
 * is running, then shows a "time's up" message when it expires.
 */
public class TimerNotification {
    private static final String CHANNEL_ID = "breakfast_timer";
    private static final int NOTIFICATION_ID = 1001;

    private final Context context;
    private final NotificationManager notificationManager;
    private CountDownTimer countDownTimer;
    private Runnable onExpired;

    public TimerNotification(Context context) {
        this.context = context;
        this.notificationManager = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        createChannel();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW   // no sound, stays in shade
            );
            channel.setDescription(context.getString(R.string.notification_channel_description));
            channel.setShowBadge(false);
            notificationManager.createNotificationChannel(channel);
        }
    }

    /**
     * Start (or restart) the countdown notification.
     *
     * @param remainingMs   milliseconds left on the timer
     * @param onExpired     callback fired once on the main thread when the timer hits zero
     */
    public void start(long remainingMs, Runnable onExpired) {
        this.onExpired = onExpired;
        cancel();                         // tear down any previous timer

        if (remainingMs <= 0) {
            showExpired();
            if (onExpired != null) onExpired.run();
            return;
        }

        // Show the first tick immediately
        updateNotification(remainingMs);

        countDownTimer = new CountDownTimer(remainingMs, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                updateNotification(millisUntilFinished);
            }

            @Override
            public void onFinish() {
                showExpired();
                if (TimerNotification.this.onExpired != null) {
                    TimerNotification.this.onExpired.run();
                }
            }
        };
        countDownTimer.start();
    }

    /** Stop the countdown and remove the notification. */
    public void cancel() {
        if (countDownTimer != null) {
            countDownTimer.cancel();
            countDownTimer = null;
        }
        notificationManager.cancel(NOTIFICATION_ID);
    }

    // ---- private helpers ------------------------------------------------

    private void updateNotification(long millisLeft) {
        int minutes = (int) (millisLeft / 60_000);
        int seconds = (int) ((millisLeft % 60_000) / 1000);
        String timeText = String.format(Locale.getDefault(), "%d:%02d", minutes, seconds);

        NotificationCompat.Builder builder = baseBuilder()
                .setContentTitle("Social media break")
                .setContentText(timeText + " remaining")
                .setOngoing(true)                       // user can't swipe it away
                .setOnlyAlertOnce(true)                 // no sound/vibration on updates
                .setProgress(0, 0, false)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS);

        notificationManager.notify(NOTIFICATION_ID, builder.build());
    }

    private void showExpired() {
        NotificationCompat.Builder builder = baseBuilder()
                .setContentTitle("Time\u2019s up!")
                .setContentText("Distraction rules are now active.")
                .setOngoing(false)
                .setAutoCancel(true);

        notificationManager.notify(NOTIFICATION_ID, builder.build());
    }

    private NotificationCompat.Builder baseBuilder() {
        Intent tapIntent = new Intent(context, MainActivity.class);
        tapIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = PendingIntent.getActivity(
                context, 0, tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_instagram)   // reuse existing icon
                .setContentIntent(pending)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setSilent(true);
    }
}
