/*
 * Copyright (c) Konrad Kollnig 2015.
 */

package de.kollnig.breakfast.stores;

import de.kollnig.breakfast.libs.Common;
import de.kollnig.breakfast.libs.Config;
import de.kollnig.breakfast.libs.Store;

public class Cards extends Store {
	protected String[][] dataModel = {
			{"title", "string"},
			{"content", "string"},
			{"image", "string"},
			{"link", "string"},
			{"id", "string"},
			{"subtitle", "string"},
			{"additional", "string"},
			{"timestamp", "string"},
			{"provider", "string"},
			{"display", "string"}
	};

	public String[][] getModel () {
		return dataModel;
	}

	@Override
	public void load (String token) {
		// Build Url
		String[][] requestParams = {
				{"token", token}
		};
		String requestUrl = Common.buildUrl(requestParams, Config.host, Config.jsonVersion);

		// Finally begin to load
		fire(requestUrl);
	}
}
