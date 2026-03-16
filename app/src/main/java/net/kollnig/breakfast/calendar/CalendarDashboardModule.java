package net.kollnig.breakfast.calendar;

import net.kollnig.breakfast.*;
import net.kollnig.breakfast.calendar.*;
import net.kollnig.breakfast.dashboard.*;
import net.kollnig.breakfast.news.*;
import net.kollnig.breakfast.social.*;
import net.kollnig.breakfast.todoist.*;
import net.kollnig.breakfast.weather.*;

import android.content.Context;
import android.content.pm.PackageManager;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;

public class CalendarDashboardModule {
    public interface MainThreadPoster {
        void post(Runnable runnable);
    }

    public interface CalendarPermissionRequester {
        void requestCalendarPermission();
    }

    public interface SettingsOpener {
        void openSettings();
    }

    public interface CalendarAppOpener {
        void openCalendarApp();
    }

    private final Context context;
    private final View rootView;
    private final AppConfig config;
    private final CalendarRepository calendarRepository;
    private final ExecutorService executor;
    private final MainThreadPoster mainThreadPoster;
    private final CalendarPermissionRequester permissionRequester;
    private final SettingsOpener settingsOpener;
    private final CalendarAppOpener calendarAppOpener;
    private final TextView calendarStatus;

    public CalendarDashboardModule(Context context, View rootView, AppConfig config,
                                   CalendarRepository calendarRepository, ExecutorService executor,
                                   MainThreadPoster mainThreadPoster,
                                   CalendarPermissionRequester permissionRequester,
                                   SettingsOpener settingsOpener,
                                   CalendarAppOpener calendarAppOpener) {
        this.context = context;
        this.rootView = rootView;
        this.config = config;
        this.calendarRepository = calendarRepository;
        this.executor = executor;
        this.mainThreadPoster = mainThreadPoster;
        this.permissionRequester = permissionRequester;
        this.settingsOpener = settingsOpener;
        this.calendarAppOpener = calendarAppOpener;
        this.calendarStatus = rootView.findViewById(R.id.calendar_status);
    }

    public void refreshOverview() {
        if (!config.isCalendarModuleEnabled()) {
            return;
        }

        if (!hasCalendarPermission()) {
            calendarStatus.setText("Grant calendar access to see today’s agenda.");
            rootView.setOnClickListener(v -> permissionRequester.requestCalendarPermission());
            return;
        }

        rootView.setOnClickListener(null);
        calendarStatus.setText("Loading today’s events...");

        executor.execute(() -> {
            try {
                List<CalendarInfo> calendars = calendarRepository.getVisibleCalendars(context);
                Set<Long> availableIds = new HashSet<>();
                for (CalendarInfo calendar : calendars) {
                    availableIds.add(calendar.id);
                }

                Set<Long> selectedIds = config.getSelectedCalendarIds();
                Set<Long> filteredIds = new HashSet<>();
                if (!config.hasExplicitCalendarSelection()) {
                    filteredIds.addAll(availableIds);
                } else {
                    for (Long id : selectedIds) {
                        if (availableIds.contains(id)) {
                            filteredIds.add(id);
                        }
                    }
                }

                List<CalendarEventInfo> events = filteredIds.isEmpty()
                        ? new ArrayList<>()
                        : calendarRepository.getEventsForToday(context, filteredIds);

                mainThreadPoster.post(() -> showCalendarOverview(events, calendars, filteredIds));
            } catch (Exception e) {
                mainThreadPoster.post(() ->
                        calendarStatus.setText("Couldn’t load today’s calendar overview."));
            }
        });
    }

    public CharSequence getSummaryText() {
        return calendarStatus.getText();
    }

    private void showCalendarOverview(List<CalendarEventInfo> events, List<CalendarInfo> calendars,
                                      Set<Long> selectedIds) {
        if (calendars.isEmpty()) {
            calendarStatus.setText("No visible calendars were found. In Google Calendar, enable 'Share Google Calendar data with other apps'.");
            rootView.setOnClickListener(v -> settingsOpener.openSettings());
            return;
        }

        if (selectedIds.isEmpty()) {
            calendarStatus.setText("Choose at least one calendar in Settings to build your overview.");
            rootView.setOnClickListener(v -> settingsOpener.openSettings());
            return;
        }

        rootView.setOnClickListener(v -> calendarAppOpener.openCalendarApp());

        if (events.isEmpty()) {
            calendarStatus.setText("No more events scheduled for today.");
            return;
        }

        int totalEvents = events.size();
        SpannableStringBuilder summary = new SpannableStringBuilder();
        for (int i = 0; i < totalEvents; i++) {
            if (i > 0) {
                summary.append('\n');
            }
            summary.append(buildEventLine(events.get(i)));
        }

        String heading = totalEvents == 1 ? "1 event today" : totalEvents + " events today";
        SpannableStringBuilder body = new SpannableStringBuilder();
        body.append(heading).append('\n').append(summary);
        calendarStatus.setText(body);
    }

    private CharSequence buildEventLine(CalendarEventInfo event) {
        SpannableStringBuilder line = new SpannableStringBuilder();
        SpannableString bullet = new SpannableString("\u25cf ");
        bullet.setSpan(new ForegroundColorSpan(event.calendarColor), 0, bullet.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        line.append(bullet);
        line.append(formatEventTiming(event)).append("  ").append(event.title);
        return line;
    }

    private String formatEventTiming(CalendarEventInfo event) {
        if (event.allDay) {
            return "All day";
        }

        long now = System.currentTimeMillis();
        boolean isLive = event.startMillis <= now && now < event.endMillis;
        return isLive
                ? "Now  "
                : formatClockTime(event.startMillis) + "-" + formatClockTime(event.endMillis);
    }

    private String formatClockTime(long timestamp) {
        DateFormat timeFormat = android.text.format.DateFormat.getTimeFormat(context);
        return timeFormat.format(new Date(timestamp));
    }

    private boolean hasCalendarPermission() {
        return ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CALENDAR)
                == PackageManager.PERMISSION_GRANTED;
    }
}
