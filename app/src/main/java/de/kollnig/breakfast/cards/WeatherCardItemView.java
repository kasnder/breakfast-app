package de.kollnig.breakfast.cards;

import android.content.Context;
import android.text.Html;
import android.util.AttributeSet;
import android.widget.ImageView;
import android.widget.TextView;

import com.dexafree.materialList.cards.internal.BaseTextCardItemView;

public class WeatherCardItemView extends BaseTextCardItemView<WeatherCard> {
	public WeatherCardItemView (Context context) {
		super(context);
	}

	public WeatherCardItemView (Context context, AttributeSet attrs) {
		super(context, attrs);
	}

	public WeatherCardItemView (Context context, AttributeSet attrs, int defStyle) {
		super(context, attrs, defStyle);
	}

	public void build(WeatherCard card) {
		super.build(card);

		TextView description = (TextView)this.findViewById(com.dexafree.materialList.R.id.descriptionTextView);
		description.setText(Html.fromHtml(card.getDescription()));
	}
}
