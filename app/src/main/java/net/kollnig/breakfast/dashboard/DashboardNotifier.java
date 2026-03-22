package net.kollnig.breakfast.dashboard;

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

import androidx.core.app.NotificationCompat;

public class DashboardNotifier {
    private static final String CHANNEL_ID = "breakfast_dashboard";
    private static final int NOTIFICATION_ID = 2001;
    private static final int PROCESSING_NOTIFICATION_ID = 2002;

    private final Context context;
    private final NotificationManager notificationManager;

    public DashboardNotifier(Context context) {
        this.context = context;
        this.notificationManager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        createChannel();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.dashboard_notification_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            channel.setDescription(context.getString(R.string.dashboard_notification_channel_description));
            notificationManager.createNotificationChannel(channel);
        }
    }

    public void showReadyNotification(String summaryText) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_settings)
                .setContentTitle("Breakfast is ready")
                .setContentText(summaryText)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(summaryText))
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);

        notificationManager.notify(NOTIFICATION_ID, builder.build());
    }

    /**
     * Shows or updates an ongoing progress notification while the on-device LLM is processing.
     *
     * @param phase   Human-readable description of the current phase (e.g. "Weighting articles")
     * @param current Number of items processed so far (used to compute fraction).
     *                Pass 0 to show an indeterminate progress bar.
     * @param total   Total number of items to process (0 = indeterminate).
     */
    public void showProcessingNotification(String phase, int current, int total) {
        boolean indeterminate = total <= 0;
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_settings)
                .setContentTitle(context.getString(R.string.llm_processing_title))
                .setContentText(phase)
                .setProgress(indeterminate ? 0 : total, indeterminate ? 0 : current, indeterminate)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);

        notificationManager.notify(PROCESSING_NOTIFICATION_ID, builder.build());
    }

    /**
     * Dismisses the processing progress notification (if it is showing).
     */
    public void cancelProcessingNotification() {
        notificationManager.cancel(PROCESSING_NOTIFICATION_ID);
    }
}
