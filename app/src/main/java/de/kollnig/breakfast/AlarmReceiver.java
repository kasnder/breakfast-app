package de.kollnig.breakfast;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.StrictMode;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import android.widget.Toast;

import com.squareup.picasso.Downloader;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.URL;

import de.kollnig.breakfast.libs.Common;
import de.kollnig.breakfast.libs.Config;
import de.kollnig.breakfast.libs.Settings;

public class AlarmReceiver extends BroadcastReceiver {
	public static final int NOTIFICATION_ID = 1;

	Context mContext;

	@Override
	public void onReceive (Context context, Intent intent) {
		Log.d(context.getPackageName(), "Cooking..");

		mContext = context;

		// TODO Remove
		//StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
		//StrictMode.setThreadPolicy(policy);

		CookTask cookTask = new CookTask();
		cookTask.execute();
	}

	private class CookTask extends AsyncTask<Void, Void, String> {
		protected String doInBackground(Void... voids) {
			Log.d(mContext.getPackageName(), "Downloading..");
			return Common.downloadString(Config.host + "?cook=1");
		}

		protected void onPostExecute(String result) {
			// Cache result
			String filename = "breakfast.json";
			String string = "Hello world!";
			FileOutputStream outputStream;

			try {
				outputStream = mContext.openFileOutput(filename, Context.MODE_PRIVATE);
				outputStream.write(string.getBytes());
				outputStream.close();
			} catch (Exception e) {
				e.printStackTrace();
			}

			// Check settings -- user logged in?
			Settings.init(mContext);
			Boolean showNotification = Settings.getBoolean("checkbox_notifications", true);

			// Check received message
			if (showNotification) {
				Bundle notification = new Bundle();
				notification.putString("title", "Breakfast");
				notification.putString("message", "Prepared a delicious meal!");

				sendNotification(mContext, notification);
			}

			Log.d(mContext.getPackageName(), "Cooked");
		}
	}

	// Put the message into a notification and post it.
	// This is just one simple example of what you might choose to do with
	// a GCM message.
	private void sendNotification(Context context, Bundle message) {
		// Show notification in notifications center
		NotificationManager mNotificationManager = (NotificationManager)
				context.getSystemService(Context.NOTIFICATION_SERVICE);

		if (message.containsKey("title") && message.containsKey("message")) {
			String title = message.getString("title");
			String text = message.getString("message");
			Uri notificationSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);

			// Show app and update on notification click
			Intent intent = new Intent(context, MainActivity.class);
			intent.putExtra("force_update", true);
			PendingIntent contentIntent = PendingIntent.getActivity(context, 0,
					intent, 0);

			NotificationCompat.Builder mBuilder =
					new NotificationCompat.Builder(context)
							.setSmallIcon(R.drawable.ic_drawer)
							.setContentTitle(title)
							.setStyle(new NotificationCompat.BigTextStyle()
									.bigText(text))
							.setContentText(text)
							.setAutoCancel(true)
							.setContentIntent(contentIntent)
									//.setLights(Color.RED, 3000, 3000)
							.setSound(notificationSound)
							.setVibrate(new long[]{100, 100, 100, 100, 100});

			mNotificationManager.notify(NOTIFICATION_ID, mBuilder.build());
		}
	}
}
