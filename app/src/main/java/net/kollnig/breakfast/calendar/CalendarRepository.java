package net.kollnig.breakfast.calendar;

import net.kollnig.breakfast.*;
import net.kollnig.breakfast.calendar.*;
import net.kollnig.breakfast.dashboard.*;
import net.kollnig.breakfast.news.*;
import net.kollnig.breakfast.social.*;
import net.kollnig.breakfast.todoist.*;
import net.kollnig.breakfast.weather.*;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.provider.CalendarContract;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class CalendarRepository {

    public List<CalendarInfo> getVisibleCalendars(Context context) {
        List<CalendarInfo> calendars = new ArrayList<>();
        ContentResolver resolver = context.getContentResolver();
        String[] projection = new String[] {
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.ACCOUNT_NAME,
                CalendarContract.Calendars.CALENDAR_COLOR
        };

        try (Cursor cursor = resolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                CalendarContract.Calendars.VISIBLE + " = 1",
                null,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME + " COLLATE NOCASE ASC")) {
            if (cursor == null) return calendars;

            int idIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID);
            int nameIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME);
            int accountIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.ACCOUNT_NAME);
            int colorIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_COLOR);

            while (cursor.moveToNext()) {
                calendars.add(new CalendarInfo(
                        cursor.getLong(idIndex),
                        cursor.getString(nameIndex),
                        cursor.getString(accountIndex),
                        cursor.getInt(colorIndex)
                ));
            }
        }

        return calendars;
    }

    public List<CalendarEventInfo> getEventsForToday(Context context, Set<Long> selectedCalendarIds) {
        List<CalendarEventInfo> events = new ArrayList<>();
        ContentResolver resolver = context.getContentResolver();
        long startOfDay = getStartOfTodayMillis();
        long endOfDay = startOfDay + 24L * 60L * 60L * 1000L;

        StringBuilder selection = new StringBuilder(
                CalendarContract.Instances.BEGIN + " < ? AND " + CalendarContract.Instances.END + " > ?");
        List<String> args = new ArrayList<>();
        args.add(String.valueOf(endOfDay));
        args.add(String.valueOf(startOfDay));

        if (selectedCalendarIds != null && !selectedCalendarIds.isEmpty()) {
            selection.append(" AND ").append(CalendarContract.Instances.CALENDAR_ID).append(" IN (");
            int i = 0;
            for (Long ignored : selectedCalendarIds) {
                if (i > 0) selection.append(",");
                selection.append("?");
                i++;
            }
            selection.append(")");
            for (Long id : selectedCalendarIds) {
                args.add(String.valueOf(id));
            }
        }

        String[] projection = new String[] {
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
                CalendarContract.Instances.CALENDAR_COLOR
        };

        android.net.Uri.Builder builder = CalendarContract.Instances.CONTENT_URI.buildUpon();
        android.content.ContentUris.appendId(builder, startOfDay);
        android.content.ContentUris.appendId(builder, endOfDay);

        try (Cursor cursor = resolver.query(
                builder.build(),
                projection,
                selection.toString(),
                args.toArray(new String[0]),
                CalendarContract.Instances.BEGIN + " ASC")) {
            if (cursor == null) return events;

            int idIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID);
            int titleIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE);
            int startIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN);
            int endIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.END);
            int allDayIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY);
            int calendarNameIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.CALENDAR_DISPLAY_NAME);
            int calendarColorIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.CALENDAR_COLOR);

            Set<String> seen = new HashSet<>();
            while (cursor.moveToNext()) {
                long eventId = cursor.getLong(idIndex);
                long start = cursor.getLong(startIndex);
                long end = cursor.getLong(endIndex);
                String dedupeKey = eventId + ":" + start + ":" + end;
                if (!seen.add(dedupeKey)) {
                    continue;
                }

                String title = cursor.getString(titleIndex);
                if (title == null || title.trim().isEmpty()) {
                    title = "Untitled event";
                }

                events.add(new CalendarEventInfo(
                        eventId,
                        title,
                        start,
                        end,
                        cursor.getInt(allDayIndex) == 1,
                        cursor.getString(calendarNameIndex),
                        cursor.getInt(calendarColorIndex)
                ));
            }
        }

        return events;
    }

    private long getStartOfTodayMillis() {
        java.util.Calendar calendar = java.util.Calendar.getInstance();
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0);
        calendar.set(java.util.Calendar.MINUTE, 0);
        calendar.set(java.util.Calendar.SECOND, 0);
        calendar.set(java.util.Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }
}
