package net.kollnig.breakfast.main;

import net.kollnig.breakfast.calendar.*;
import net.kollnig.breakfast.dashboard.*;
import net.kollnig.breakfast.news.*;
import net.kollnig.breakfast.social.*;
import net.kollnig.breakfast.todoist.*;
import net.kollnig.breakfast.weather.*;

import android.app.Activity;
import android.view.View;
import android.widget.LinearLayout;

import com.google.android.material.card.MaterialCardView;

import net.kollnig.breakfast.AppConfig;
import net.kollnig.breakfast.R;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MainDashboardViews {
    private final LinearLayout dashboardCardsContainer;
    private final MaterialCardView cardWelcome;
    private final MaterialCardView cardWeather;
    private final MaterialCardView cardEmail;
    private final MaterialCardView cardCalendar;
    private final MaterialCardView cardTodoist;
    private final MaterialCardView cardSocial;
    private final MaterialCardView cardHeadlines;
    private final MaterialCardView cardNews;
    private final Map<String, List<View>> dashboardModuleCards = new LinkedHashMap<>();

    public MainDashboardViews(Activity activity) {
        dashboardCardsContainer = activity.findViewById(R.id.dashboard_cards_container);
        cardWelcome = activity.findViewById(R.id.card_welcome);
        cardWeather = activity.findViewById(R.id.card_weather);
        cardEmail = activity.findViewById(R.id.card_email);
        cardCalendar = activity.findViewById(R.id.card_calendar);
        cardTodoist = activity.findViewById(R.id.card_todoist);
        cardSocial = activity.findViewById(R.id.card_social);
        cardHeadlines = activity.findViewById(R.id.card_headlines);
        cardNews = activity.findViewById(R.id.card_news);

        registerModuleCards(AppConfig.MODULE_WEATHER, cardWeather);
        registerModuleCards(AppConfig.MODULE_NEWS, cardHeadlines, cardNews);
        registerModuleCards(AppConfig.MODULE_SOCIAL, cardSocial);
        registerModuleCards(AppConfig.MODULE_EMAIL, cardEmail);
        registerModuleCards(AppConfig.MODULE_CALENDAR, cardCalendar);
        registerModuleCards(AppConfig.MODULE_TODOIST, cardTodoist);
    }

    public MaterialCardView getCardWelcome() {
        return cardWelcome;
    }

    public MaterialCardView getCardWeather() {
        return cardWeather;
    }

    public MaterialCardView getCardEmail() {
        return cardEmail;
    }

    public MaterialCardView getCardCalendar() {
        return cardCalendar;
    }

    public MaterialCardView getCardTodoist() {
        return cardTodoist;
    }

    public MaterialCardView getCardSocial() {
        return cardSocial;
    }

    public MaterialCardView getCardHeadlines() {
        return cardHeadlines;
    }

    public MaterialCardView getCardNews() {
        return cardNews;
    }

    public void applyModuleVisibility(AppConfig config, Runnable invalidateOptionsMenuCallback) {
        cardWelcome.setVisibility(config.isWelcomeDismissed() ? View.GONE : View.VISIBLE);
        for (DashboardModuleDefinition module : DashboardModuleRegistry.getModules()) {
            int visibility = module.isEnabled(config) ? View.VISIBLE : View.GONE;
            List<View> cards = dashboardModuleCards.get(module.getId());
            if (cards == null) {
                continue;
            }
            for (View card : cards) {
                card.setVisibility(visibility);
            }
        }
        applyModuleOrder(config.getModuleOrder());
        invalidateOptionsMenuCallback.run();
    }

    private void registerModuleCards(String moduleId, View... cards) {
        dashboardModuleCards.put(moduleId, Arrays.asList(cards));
    }

    private void applyModuleOrder(List<String> moduleOrder) {
        dashboardCardsContainer.removeView(cardWelcome);
        for (List<View> cards : dashboardModuleCards.values()) {
            for (View card : cards) {
                dashboardCardsContainer.removeView(card);
            }
        }

        dashboardCardsContainer.addView(cardWelcome);
        for (String moduleId : moduleOrder) {
            List<View> cards = dashboardModuleCards.get(moduleId);
            if (cards == null) {
                continue;
            }
            for (View card : cards) {
                dashboardCardsContainer.addView(card);
            }
        }
    }
}
