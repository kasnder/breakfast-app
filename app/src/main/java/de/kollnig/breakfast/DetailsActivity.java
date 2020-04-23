/*
 * Copyright (c) Konrad Kollnig 2015.
 */

package de.kollnig.breakfast;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.support.v7.app.ActionBarActivity;
import android.util.Log;
import android.view.GestureDetector;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebView;

public class DetailsActivity extends ActionBarActivity {
	// Store the URL to the news' page
	String link;

	GestureDetector gestureDetector = null;

	Activity mActivity;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_details);

	    mActivity = this;

        Intent intent = getIntent();

        // Go home if no extras
        if (!intent.hasExtra("content") || !intent.hasExtra("link") || !intent.hasExtra("title")) {
            finish();
        }

        // Set title
        String title = intent.getStringExtra("title");
        setTitle(title);

        // Display content
        String content = intent.getStringExtra("content");
	    WebView webView = (WebView) findViewById(R.id.news_details_content);
	    webView.getSettings().setDomStorageEnabled(true);
	    webView.getSettings().setUseWideViewPort(true);
	    webView.getSettings().setJavaScriptEnabled(true);
	    webView.loadDataWithBaseURL("file:///android_asset/",
			    "<!DOCTYPE HTML>\n" +
					    "<html>\n" +
					    "	<head>\n" +
					    "		<meta charset=\"utf-8\">\n" +
					    "       <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no\" />" +
					    "		<title>" + title + "</title>\n" +
					    "		<script type=\"text/javascript\">\n" +
					    "			readStyle = 'style-newspaper';\n" +
					    "			readSize = 'size-large';\n" +
					    "			readMargin = 'margin-x-narrow';\n" +
					    "			\n" +
					    "			_readability_script = document.createElement('SCRIPT');\n" +
					    "			_readability_script.type = 'text/javascript';\n" +
					    "			_readability_script.src = 'readability.js';\n" +
					    "			document.getElementsByTagName('head')[0].appendChild(_readability_script)\n" +
					    "		</script>\n" +
					    "\n" +
					    "		<link rel=\"stylesheet\" type=\"text/css\" href=\"readability.css\">\n" +
					    "	</head>\n" +
					    "	<body>\n" +
					    "		<article>\n" +
					    "			\n" + content +
					    "		</article>\n" +
					    "	</body>\n" +
					    "</html>", "text/html", "utf-8", null);

	    webView.setOnTouchListener(new View.OnTouchListener() {
		    @Override
		    public boolean onTouch (View v, MotionEvent event) {
			    if (gestureDetector == null) {
				    gestureDetector = new GestureDetector(
						    mActivity,
						    new GestureDetector.SimpleOnGestureListener() {
							    @Override
							    public boolean onDoubleTapEvent (MotionEvent e) {
								    mActivity.finish();
								    return true;
							    }
						    });
			    }
			    gestureDetector.onTouchEvent(event);

			    return false;
		    }
	    });

	    webView.setWebChromeClient(new WebChromeClient() {
		    public boolean onConsoleMessage(ConsoleMessage cm) {
			    Log.d("MyApplication", cm.message() + " -- From line "
					    + cm.lineNumber() + " of "
					    + cm.sourceId() );
			    return true;
		    }
	    });

		// Fetch url
	    this.link = intent.getStringExtra("link");
    }

	@Override
	public boolean onCreateOptionsMenu (Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.activity_details, menu);
		return true;
	}

	@Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
		switch (id) {
			case android.R.id.home:
				finish();
				return true;
			case R.id.menu_browser: // Open page in browser
				Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(link));
				startActivity(browserIntent);
				return true;
		}
        return super.onOptionsItemSelected(item);
    }
}
