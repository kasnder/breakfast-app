package de.kollnig.breakfast;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.TargetApi;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.support.v7.app.ActionBarActivity;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import com.dexafree.materialList.cards.BigImageCard;
import com.dexafree.materialList.cards.OnButtonPressListener;
import com.dexafree.materialList.cards.SimpleCard;
import com.dexafree.materialList.cards.SmallImageCard;
import com.dexafree.materialList.cards.WelcomeCard;
import com.dexafree.materialList.controller.OnDismissCallback;
import com.dexafree.materialList.controller.RecyclerItemClickListener;
import com.dexafree.materialList.events.BusProvider;
import com.dexafree.materialList.model.Card;
import com.dexafree.materialList.model.CardItemView;
import com.dexafree.materialList.view.MaterialListView;
import com.nostra13.universalimageloader.core.ImageLoader;
import com.nostra13.universalimageloader.core.ImageLoaderConfiguration;
import com.nostra13.universalimageloader.core.listener.SimpleImageLoadingListener;
import com.nostra13.universalimageloader.utils.StorageUtils;

import java.io.File;
import java.util.Calendar;

import de.kollnig.breakfast.cards.CardType;
import de.kollnig.breakfast.cards.FeedlyCard;
import de.kollnig.breakfast.cards.TextCard;
import de.kollnig.breakfast.cards.WeatherCard;
import de.kollnig.breakfast.libs.Common;
import de.kollnig.breakfast.libs.Model;
import de.kollnig.breakfast.libs.Settings;
import de.kollnig.breakfast.stores.Cards;


public class MainActivity extends ActionBarActivity {
	private View progressView;
	private Context mContext;
	private MaterialListView mListView;

	private AlarmManager alarmMgr;
	private PendingIntent alarmIntent;

	@Override
	protected void onCreate (Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		// Init settings
		Settings.init(this);

		// Save myself
		mContext = this;

		// Create global configuration and initialize ImageLoader with this config
		//ImageLoaderConfiguration config = new ImageLoaderConfiguration.Builder(this).build();
		//ImageLoader.getInstance().init(config);

		Common.cards = new Cards() {
			@Override
			public void onLoaded (Boolean success) {
				onCardsLoaded(success);
			}
		};

		// Set up alarm
		alarmMgr = (AlarmManager)mContext.getSystemService(Context.ALARM_SERVICE);
		Intent intent = new Intent(mContext, AlarmReceiver.class);
		alarmIntent = PendingIntent.getBroadcast(mContext, 0, intent, 0); // PendingIntent.FLAG_UPDATE_CURRENT
		//alarmMgr.cancel(alarmIntent);

		// Set the alarm to start at approximately 8:00 a.m.
		Calendar calendar = Calendar.getInstance();
		calendar.setTimeInMillis(System.currentTimeMillis());
		calendar.set(Calendar.HOUR_OF_DAY, 8);

		// With setInexactRepeating(), you have to use one of the AlarmManager interval
		// constants--in this case, AlarmManager.INTERVAL_DAY.
		alarmMgr.setInexactRepeating(AlarmManager.RTC_WAKEUP, calendar.getTimeInMillis(),
				AlarmManager.INTERVAL_DAY, alarmIntent);
		/*alarmMgr.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
				SystemClock.elapsedRealtime() +
						1000, alarmIntent);*/

		progressView = findViewById(R.id.tabbed_progress);

		// Cards stuff
		mListView = (MaterialListView) findViewById(R.id.material_listview);
		/*mListView.setItemAnimator(new DefaultItemAnimator());

		mListView.getItemAnimator().setAddDuration(2000);
		mListView.getItemAnimator().setRemoveDuration(2000);
		mListView.getItemAnimator().setMoveDuration(2000);
		mListView.getItemAnimator().setChangeDuration(2000);*/

		addItem(CardType.WELCOME);

		// Click
		mListView.addOnItemTouchListener(new RecyclerItemClickListener.OnItemClickListener() {
			@Override
			public void onItemClick (CardItemView view, int position) {
				Log.d("SHORT_CLICK", view.getTag().toString());

				if (view.getTag() instanceof Model && ((Model) view.getTag()).getString("provider").equals("Feedly")) {
					// Fetch content of entry
					String title = ((Model) view.getTag()).getString("title");
					String content = ((Model) view.getTag()).getString("content");
					String link = ((Model) view.getTag()).getString("link"); // Common.cards.getAt(position).getString("link")

					// Build details intent
					Intent intent = new Intent(mContext, DetailsActivity.class);

					intent.putExtra("title", title);
					intent.putExtra("content", content);
					intent.putExtra("link", link);

					startActivity(intent);
				}
			}

			@Override
			public void onItemLongClick (CardItemView view, int position) {
				Log.d("LONG_CLICK", view.getTag().toString());

				if (view.getTag() instanceof Model && ((Model) view.getTag()).containsKey("link")) {
					Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(((Model) view.getTag()).getString("link")));
					startActivity(browserIntent);
				}
			}
		});

		mListView.setOnDismissCallback(new OnDismissCallback() {
			@Override
			public void onDismiss (Card card, int position) {
				if (card.getTag().equals("welcome")) {
					//
				}
				Log.d("DISMISS", card.getTag().toString());
			}
		});

		loadStore();
	}

	public Boolean isLoading () {
		return Common.cards.isLoading();
	}

	public void loadStore () {
		showProgress(true);
		mListView.removeAllViews();
		stopStore();
		Common.cards.load("");
	}

	protected void stopStore () {
		if (isLoading()) {
			Common.cards.cancelLoading();
		}

		// Unset data
		//scheduleAdapter.notifyDataSetInvalidated();
		//scheduleList.setAdapter(null);
	}

	public void onCardsLoaded (Boolean success) {
		showProgress(false);

		if (success) {
			for (Model model : Common.cards.dataList) {
				addCard(model);
			}
		} else {
			// Do something..
			Common.msgBox(this, "Loading failed.", true);
		}
	}

	public void addCard (Model model) {
		SimpleCard card;

		switch (model.getString("display")) {
			case "feedly":
				card = new FeedlyCard(this);
				break;
			case "text":
				card = new TextCard(this);
				break;
			case "big":
				card = new BigImageCard(this);
				break;
			default: // small
				card = new WeatherCard(this);
				break;
		}

		card.setTag(model);
		card.setTitle(model.getString("title"));
		if (model.containsKey("subtitle")) card.setDescription(model.getString("subtitle"));
		if (model.containsKey("image")) card.setDrawable(model.getString("image"));
		mListView.add(card);
	}

	/**
	 * Shows the progress UI and hides it from the activity.
	 */
	@TargetApi(Build.VERSION_CODES.HONEYCOMB_MR2)
	public void showProgress (final boolean show) {
		// On Honeycomb MR2 we have the ViewPropertyAnimator APIs, which allow
		// for very easy animations. If available, use these APIs to fade-in
		// the progress spinner.
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB_MR2) {
			int animTime = getResources().getInteger(android.R.integer.config_shortAnimTime);

			mListView.animate().setDuration(animTime).alpha(
					show ? 0 : 1).setListener(new AnimatorListenerAdapter() {
				@Override
				public void onAnimationEnd (Animator animation) {
					mListView.setVisibility(show ? View.GONE : View.VISIBLE);
				}
			});

			progressView.setVisibility(show ? View.VISIBLE : View.GONE);
			progressView.animate().setDuration(animTime).alpha(
					show ? 1 : 0).setListener(new AnimatorListenerAdapter() {
				@Override
				public void onAnimationEnd (Animator animation) {
					progressView.setVisibility(show ? View.VISIBLE : View.GONE);
				}
			});
		} else {
			// The ViewPropertyAnimator APIs are not available, so simply show
			// and hide the relevant UI components.
			progressView.setVisibility(show ? View.VISIBLE : View.GONE);
			mListView.setVisibility(show ? View.GONE : View.VISIBLE);
		}
	}

	public void addItem (CardType type) {
		SimpleCard card;

		switch (type) {
			case QUOTE:
				card = new TextCard(this); //SimpleCard

				card.setDescription("");
				card.setTitle("Spruch zum Tag");
				break;
			case WELCOME:
				// Welcome
				card = new WelcomeCard(this);
				card.setTitle("Hello there!");
				((WelcomeCard) card).setSubtitle("My subtitle!");
				card.setDescription("Your description");
				((WelcomeCard) card).setButtonText("OKAY!");
				card.setDismissible(true);
				card.setBackgroundColor(Color.parseColor("#295efa"));

				((WelcomeCard) card).setOnButtonPressedListener(new OnButtonPressListener() {
					@Override
					public void onButtonPressedListener (View view, Card card) {
						Toast.makeText(mContext,
								"You have removed the welcome page.",
								Toast.LENGTH_SHORT
						).show();
						//mListView.remove(card);
						BusProvider.dismiss(card);
					}
				});

				break;
			case INSTAGRAM:
				// Load image
				/*ImageLoader imageLoader = ImageLoader.getInstance();
				//imageLoader.displayImage(imageUri, imageView);
				// Load image, decode it to Bitmap and return Bitmap to callback
				imageLoader.loadImage("https://example.com/breakfast/instagram/?n=1", new SimpleImageLoadingListener() {
					@Override
					public void onLoadingComplete (String imageUri, View view, Bitmap loadedImage) {
						BigImageCard card = new BigImageCard(mContext);
						card.setTitle("Instagram Image");
						//card.setDescription("Your description");
						card.setTag(card.getClass());
						card.setDrawable(new BitmapDrawable(getResources(), loadedImage));
						//bigImageCard.setDrawable("https://assets-cdn.github.com/images/modules/logos_page/GitHub-Mark.png");

						mListView.add(card);
					}
				});*/
				return;
			default:
				card = new TextCard(this);
		}

		card.setTag(card.getClass());
		mListView.add(card);
	}

	@Override
	public boolean onCreateOptionsMenu (Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.main, menu);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected (MenuItem item) {
		// Handle action bar item clicks here. The action bar will
		// automatically handle clicks on the Home/Up button, so long
		// as you specify a parent activity in AndroidManifest.xml.
		int id = item.getItemId();

		//noinspection SimplifiableIfStatement
		switch (id) {
			case R.id.action_settings:
				return true;
			case R.id.action_refresh:
				if (!isLoading()) loadStore();
				return true;
		}

		return super.onOptionsItemSelected(item);
	}
}
