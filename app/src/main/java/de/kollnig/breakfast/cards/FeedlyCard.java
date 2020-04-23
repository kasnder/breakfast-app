package de.kollnig.breakfast.cards;

import android.content.Context;
import android.widget.ImageView;

import com.dexafree.materialList.cards.SimpleCard;

import de.kollnig.breakfast.R;

public class FeedlyCard extends SimpleCard {
	public FeedlyCard (final Context context) {
		super(context);
	}

	@Override
	public int getLayout () {
		return R.layout.feedly_card;
	}
}