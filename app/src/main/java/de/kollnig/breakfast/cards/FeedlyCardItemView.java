package de.kollnig.breakfast.cards;

import android.content.Context;
import android.text.Html;
import android.util.AttributeSet;
import android.widget.ImageView;
import android.widget.TextView;

import com.dexafree.materialList.cards.internal.BaseTextCardItemView;
import com.squareup.picasso.Picasso;

import de.kollnig.breakfast.libs.Common;

public class FeedlyCardItemView extends BaseTextCardItemView<FeedlyCard> {
	public FeedlyCardItemView (Context context) {
		super(context);
	}

	public FeedlyCardItemView (Context context, AttributeSet attrs) {
		super(context, attrs);
	}

	public FeedlyCardItemView (Context context, AttributeSet attrs, int defStyle) {
		super(context, attrs, defStyle);
	}

	public void build(FeedlyCard card) {
		super.build(card);

		TextView description = (TextView)this.findViewById(com.dexafree.materialList.R.id.descriptionTextView);
		description.setText(Html.fromHtml(card.getDescription()));

		ImageView imageView = (ImageView)this.findViewById(com.dexafree.materialList.R.id.imageView);
		imageView.setScaleType(ImageView.ScaleType.CENTER);
	}
}
