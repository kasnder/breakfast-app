package de.kollnig.breakfast.cards;

import android.content.Context;

import com.dexafree.materialList.cards.SimpleCard;

import de.kollnig.breakfast.R;

public class WeatherCard extends SimpleCard {
	public WeatherCard (final Context context) {
		super(context);
	}

	@Override
	public int getLayout () {
		return R.layout.weather_card;
	}
}