package de.kollnig.breakfast.cards;

import android.content.Context;
import android.util.AttributeSet;

import com.dexafree.materialList.cards.internal.BaseTextCardItemView;

public class TextCardItemView extends BaseTextCardItemView<TextCard> {
	public TextCardItemView (Context context) {
		super(context);
	}

	public TextCardItemView (Context context, AttributeSet attrs) {
		super(context, attrs);
	}

	public TextCardItemView (Context context, AttributeSet attrs, int defStyle) {
		super(context, attrs, defStyle);
	}

	@Override
	public void build (TextCard card) {
		super.build(card);

		//ImageView imageView = (ImageView) findViewById(R.id.imageView);
	}
}
