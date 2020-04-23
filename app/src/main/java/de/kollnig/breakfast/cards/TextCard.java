package de.kollnig.breakfast.cards;

import android.content.Context;

import com.dexafree.materialList.cards.SimpleCard;

import de.kollnig.breakfast.R;

public class TextCard extends SimpleCard {
	public TextCard (final Context context) {
		super(context);
	}

	@Override
	public int getLayout () {
		return R.layout.text_card;
	}
}