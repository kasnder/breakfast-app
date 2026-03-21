package net.kollnig.breakfast.todoist;

import net.kollnig.breakfast.*;
import net.kollnig.breakfast.calendar.*;
import net.kollnig.breakfast.dashboard.*;
import net.kollnig.breakfast.news.*;
import net.kollnig.breakfast.social.*;
import net.kollnig.breakfast.todoist.*;
import net.kollnig.breakfast.weather.*;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;


public class TodoistDashboardModule {
    public interface MainThreadPoster {
        void post(Runnable runnable);
    }

    public interface RelativeTimeFormatter {
        String formatRelativeTime(long timestamp);
    }

    public interface SettingsOpener {
        void openSettings();
    }

    public interface ArticleOpener {
        void openArticle(String url);
    }

    public interface OptionsMenuInvalidator {
        void invalidateOptionsMenu();
    }

    public interface AudioPermissionRequester {
        void requestAudioPermission();
    }

    private static final String TAG = "TodoistModule";

    private final Context context;
    private final View rootView;
    private final AppConfig config;
    private final ExecutorService executor;
    private final MainThreadPoster mainThreadPoster;
    private final RelativeTimeFormatter relativeTimeFormatter;
    private final SettingsOpener settingsOpener;
    private final ArticleOpener articleOpener;
    private final OptionsMenuInvalidator optionsMenuInvalidator;
    private final ActionFailureNotifier actionFailureNotifier;
    private final AudioPermissionRequester audioPermissionRequester;
    private final TextView todoistStatus;
    private final TextView todoistHint;
    private final ProgressBar todoistLoading;
    private final LinearLayout todoistContainer;
    private final MaterialButton btnTodoistAdd;
    private final MaterialButton btnTodoistShowAll;

    private List<TodoistTask> currentTodoistTasks = new ArrayList<>();
    private boolean todoistExpanded;

    public TodoistDashboardModule(Context context, View rootView, AppConfig config,
                                  ExecutorService executor, MainThreadPoster mainThreadPoster,
                                  RelativeTimeFormatter relativeTimeFormatter,
                                  SettingsOpener settingsOpener, ArticleOpener articleOpener,
                                  OptionsMenuInvalidator optionsMenuInvalidator,
                                  AudioPermissionRequester audioPermissionRequester) {
        this.context = context;
        this.rootView = rootView;
        this.config = config;
        this.executor = executor;
        this.mainThreadPoster = mainThreadPoster;
        this.relativeTimeFormatter = relativeTimeFormatter;
        this.settingsOpener = settingsOpener;
        this.articleOpener = articleOpener;
        this.optionsMenuInvalidator = optionsMenuInvalidator;
        this.actionFailureNotifier = new ActionFailureNotifier(context);
        this.audioPermissionRequester = audioPermissionRequester;
        this.todoistStatus = rootView.findViewById(R.id.todoist_status);
        this.todoistHint = rootView.findViewById(R.id.todoist_hint);
        this.todoistLoading = rootView.findViewById(R.id.todoist_loading);
        this.todoistContainer = rootView.findViewById(R.id.todoist_tasks_container);
        this.btnTodoistAdd = rootView.findViewById(R.id.btn_todoist_add);
        this.btnTodoistShowAll = rootView.findViewById(R.id.btn_todoist_show_all);

        btnTodoistAdd.setOnClickListener(v -> showAddTodoDialog());
        btnTodoistShowAll.setOnClickListener(v -> {
            todoistExpanded = !todoistExpanded;
            showTodoistTasks(currentTodoistTasks);
        });
    }

    public void refreshUi() {
        if (!config.isTodoistConfigured()) {
            todoistStatus.setText("Add your Todoist API token and project ID in Settings.");
            todoistStatus.setVisibility(View.VISIBLE);
            todoistHint.setVisibility(View.GONE);
            todoistLoading.setVisibility(View.GONE);
            todoistContainer.removeAllViews();
            currentTodoistTasks = new ArrayList<>();
            todoistExpanded = false;
            btnTodoistAdd.setVisibility(View.GONE);
            btnTodoistShowAll.setVisibility(View.GONE);
            optionsMenuInvalidator.invalidateOptionsMenu();
            return;
        }

        List<TodoistTask> cached = sortTodoistTasks(config.getCachedTodoistTasks());
        currentTodoistTasks = new ArrayList<>(cached);
        if (!cached.isEmpty()) {
            showTodoistTasks(cached);
            showTodoistHint(config.getTodoistLastRefresh(), true);
        } else {
            todoistStatus.setText("Pulling tasks from your Todoist project...");
            todoistStatus.setVisibility(View.VISIBLE);
            todoistHint.setVisibility(View.GONE);
            todoistLoading.setVisibility(View.GONE);
            todoistContainer.removeAllViews();
        }
        btnTodoistAdd.setVisibility(View.VISIBLE);
        btnTodoistShowAll.setVisibility(View.VISIBLE);
        optionsMenuInvalidator.invalidateOptionsMenu();
    }

    public void refreshData() {
        if (!config.isTodoistConfigured()) {
            refreshUi();
            return;
        }

        todoistLoading.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            try {
                TodoistClient client = new TodoistClient(config.getTodoistApiKey());
                List<TodoistTask> tasks = sortTodoistTasks(
                        client.fetchProjectTasks(config.getTodoistProjectId()));
                long fetchedAt = System.currentTimeMillis();
                config.setCachedTodoistTasks(tasks);
                config.setTodoistLastRefresh(fetchedAt);
                config.setDashboardLastRefresh(fetchedAt);
                mainThreadPoster.post(() -> {
                    currentTodoistTasks = new ArrayList<>(tasks);
                    showTodoistTasks(tasks);
                    showTodoistHint(fetchedAt, false);
                });
            } catch (Exception e) {
                Log.e(TAG, "Error loading Todoist tasks", e);
                mainThreadPoster.post(() -> {
                    todoistLoading.setVisibility(View.GONE);
                    List<TodoistTask> cached = sortTodoistTasks(config.getCachedTodoistTasks());
                    if (!cached.isEmpty()) {
                        currentTodoistTasks = new ArrayList<>(cached);
                        showTodoistTasks(cached);
                        todoistStatus.setText("Unable to refresh Todoist right now.");
                        todoistStatus.setVisibility(View.VISIBLE);
                        showTodoistHint(config.getTodoistLastRefresh(), true);
                    } else {
                        currentTodoistTasks = new ArrayList<>();
                        todoistExpanded = false;
                        todoistContainer.removeAllViews();
                        todoistStatus.setText("Unable to load Todoist tasks. Check your API token and project ID.");
                        todoistStatus.setVisibility(View.VISIBLE);
                        todoistHint.setVisibility(View.GONE);
                    }
                    btnTodoistAdd.setVisibility(config.isTodoistConfigured() ? View.VISIBLE : View.GONE);
                    btnTodoistShowAll.setVisibility(config.isTodoistConfigured() ? View.VISIBLE : View.GONE);
                    optionsMenuInvalidator.invalidateOptionsMenu();
                });
            }
        });
    }

    public void toggleVoiceCapture() {
        Toast.makeText(context, "Voice todos are unavailable in on-device-only mode.", Toast.LENGTH_LONG).show();
    }

    public void onAudioPermissionResult(boolean granted) {
        // Voice todos are unavailable in on-device-only mode.
    }

    public void stopVoiceCaptureOnPause() {
        // Voice todos are unavailable in on-device-only mode.
    }

    public void release() {
        // Voice todos are unavailable in on-device-only mode.
    }

    public boolean isVoiceRecording() {
        return false;
    }

    private List<TodoistTask> sortTodoistTasks(List<TodoistTask> tasks) {
        List<TodoistTask> sorted = new ArrayList<>(tasks);
        Collections.sort(sorted, new Comparator<TodoistTask>() {
            @Override
            public int compare(TodoistTask left, TodoistTask right) {
                boolean leftDue = hasTodoDue(left);
                boolean rightDue = hasTodoDue(right);
                if (leftDue != rightDue) {
                    return leftDue ? -1 : 1;
                }

                if (leftDue) {
                    int dueCompare = Long.compare(getTodoDueSortTime(left), getTodoDueSortTime(right));
                    if (dueCompare != 0) {
                        return dueCompare;
                    }
                } else {
                    int sectionCompare = Integer.compare(left.sectionOrder, right.sectionOrder);
                    if (sectionCompare != 0) {
                        return sectionCompare;
                    }

                    int priorityCompare = Integer.compare(right.priority, left.priority);
                    if (priorityCompare != 0) {
                        return priorityCompare;
                    }
                }

                int taskOrderCompare = Integer.compare(left.taskOrder, right.taskOrder);
                if (taskOrderCompare != 0) {
                    return taskOrderCompare;
                }
                return safeString(left.content).compareToIgnoreCase(safeString(right.content));
            }
        });
        return sorted;
    }

    private boolean hasTodoDue(TodoistTask task) {
        return task != null
                && task.due != null
                && (!TextUtils.isEmpty(task.due.datetime) || !TextUtils.isEmpty(task.due.date));
    }

    private long getTodoDueSortTime(TodoistTask task) {
        if (task == null || task.due == null) {
            return Long.MAX_VALUE;
        }
        if (!TextUtils.isEmpty(task.due.datetime)) {
            long parsed = parseTodoistDateTime(task.due.datetime);
            if (parsed != Long.MAX_VALUE) {
                return parsed;
            }
        }
        if (!TextUtils.isEmpty(task.due.date)) {
            return parseTodoistDate(task.due.date);
        }
        return Long.MAX_VALUE;
    }

    private long parseTodoistDateTime(String raw) {
        if (TextUtils.isEmpty(raw)) {
            return Long.MAX_VALUE;
        }
        String normalized = raw.replaceFirst("\\.\\d+(?=[Z+-])", "");
        String[] patterns = {
                "yyyy-MM-dd'T'HH:mm:ssX",
                "yyyy-MM-dd'T'HH:mm:ssXXX",
                "yyyy-MM-dd'T'HH:mmX",
                "yyyy-MM-dd'T'HH:mmXXX"
        };
        for (String pattern : patterns) {
            try {
                SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
                format.setLenient(false);
                Date parsed = format.parse(normalized);
                if (parsed != null) {
                    return parsed.getTime();
                }
            } catch (ParseException ignored) {
            }
        }
        return Long.MAX_VALUE;
    }

    private long parseTodoistDate(String raw) {
        if (TextUtils.isEmpty(raw)) {
            return Long.MAX_VALUE;
        }
        try {
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            format.setLenient(false);
            format.setTimeZone(TimeZone.getDefault());
            Date parsed = format.parse(raw);
            return parsed != null ? parsed.getTime() : Long.MAX_VALUE;
        } catch (ParseException ignored) {
            return Long.MAX_VALUE;
        }
    }

    private void showTodoistTasks(List<TodoistTask> tasks) {
        todoistLoading.setVisibility(View.GONE);
        todoistContainer.removeAllViews();
        currentTodoistTasks = new ArrayList<>(tasks);
        List<TodoistTask> displayableTasks = filterVisibleTasks(tasks);

        if (displayableTasks.isEmpty()) {
            todoistStatus.setText("No open tasks in this Todoist project.");
            todoistStatus.setVisibility(View.VISIBLE);
            todoistHint.setVisibility(View.GONE);
            btnTodoistAdd.setVisibility(config.isTodoistConfigured() ? View.VISIBLE : View.GONE);
            btnTodoistShowAll.setVisibility(View.GONE);
            optionsMenuInvalidator.invalidateOptionsMenu();
            return;
        }

        List<TodoistTask> shortlist = buildTodoistShortlist(displayableTasks);
        List<TodoistTask> visibleTasks = todoistExpanded ? displayableTasks : shortlist;
        int hiddenCount = Math.max(0, displayableTasks.size() - visibleTasks.size());
        if (hiddenCount > 0) {
            todoistStatus.setText("Showing " + visibleTasks.size() + " of " + displayableTasks.size()
                    + " open tasks");
        } else {
            todoistStatus.setText(visibleTasks.size() + " open tasks");
        }
        todoistStatus.setVisibility(View.VISIBLE);
        for (TodoistTask task : visibleTasks) {
            View itemView = LayoutInflater.from(context).inflate(R.layout.item_todo, todoistContainer, false);
            bindTodoView(itemView, task);
            todoistContainer.addView(itemView);
        }
        btnTodoistShowAll.setVisibility(displayableTasks.size() > shortlist.size() ? View.VISIBLE : View.GONE);
        btnTodoistShowAll.setText(todoistExpanded
                ? R.string.todoist_show_fewer
                : R.string.todoist_show_all);
        optionsMenuInvalidator.invalidateOptionsMenu();
    }

    private List<TodoistTask> filterVisibleTasks(List<TodoistTask> tasks) {
        List<TodoistTask> visibleTasks = new ArrayList<>();
        for (TodoistTask task : tasks) {
            if (shouldDisplayTask(task)) {
                visibleTasks.add(task);
            }
        }
        return visibleTasks;
    }

    private boolean shouldDisplayTask(TodoistTask task) {
        if (!isWeeklyRecurringTask(task)) {
            return true;
        }
        long dueTime = getTodoDueSortTime(task);
        if (dueTime == Long.MAX_VALUE) {
            return false;
        }
        long now = System.currentTimeMillis();
        long twoDaysFromNow = now + (2L * 24L * 60L * 60L * 1000L);
        return dueTime <= twoDaysFromNow;
    }

    private boolean isWeeklyRecurringTask(TodoistTask task) {
        if (task == null || task.due == null || !task.due.isRecurring) {
            return false;
        }
        String dueString = safeString(task.due.string).toLowerCase(Locale.ROOT);
        return dueString.contains("every week")
                || dueString.contains("weekly")
                || dueString.contains("each week")
                || dueString.contains("every 1 week");
    }

    private List<TodoistTask> buildTodoistShortlist(List<TodoistTask> tasks) {
        List<TodoistTask> dueSoon = new ArrayList<>();
        List<TodoistTask> remaining = new ArrayList<>();
        final int shortlistLimit = 4;
        long now = System.currentTimeMillis();
        long oneWeekFromNow = now + (7L * 24L * 60L * 60L * 1000L);

        for (TodoistTask task : tasks) {
            long dueTime = getTodoDueSortTime(task);
            if (dueTime != Long.MAX_VALUE && dueTime <= oneWeekFromNow) {
                dueSoon.add(task);
            } else {
                remaining.add(task);
            }
        }

        Collections.sort(remaining, new Comparator<TodoistTask>() {
            @Override
            public int compare(TodoistTask left, TodoistTask right) {
                int priorityCompare = Integer.compare(right.priority, left.priority);
                if (priorityCompare != 0) {
                    return priorityCompare;
                }
                int sectionCompare = Integer.compare(left.sectionOrder, right.sectionOrder);
                if (sectionCompare != 0) {
                    return sectionCompare;
                }
                int orderCompare = Integer.compare(left.taskOrder, right.taskOrder);
                if (orderCompare != 0) {
                    return orderCompare;
                }
                return safeString(left.content).compareToIgnoreCase(safeString(right.content));
            }
        });

        Collections.sort(dueSoon, new Comparator<TodoistTask>() {
            @Override
            public int compare(TodoistTask left, TodoistTask right) {
                return Long.compare(getTodoDueSortTime(left), getTodoDueSortTime(right));
            }
        });

        List<TodoistTask> shortlist = new ArrayList<>();
        int dueCount = Math.min(shortlistLimit, dueSoon.size());
        for (int i = 0; i < dueCount; i++) {
            shortlist.add(dueSoon.get(i));
        }
        int extraCount = Math.min(shortlistLimit - shortlist.size(), remaining.size());
        for (int i = 0; i < extraCount; i++) {
            shortlist.add(remaining.get(i));
        }
        return shortlist;
    }

    private void bindTodoView(View itemView, TodoistTask task) {
        View root = itemView.findViewById(R.id.todo_item_root);
        TextView title = itemView.findViewById(R.id.todo_title);
        TextView meta = itemView.findViewById(R.id.todo_meta);
        TextView description = itemView.findViewById(R.id.todo_description);
        MaterialButton doneButton = itemView.findViewById(R.id.btn_todo_done);

        title.setText(task.content);
        meta.setText(buildTodoMeta(task));
        if (TextUtils.isEmpty(task.description)) {
            description.setVisibility(View.GONE);
        } else {
            description.setVisibility(View.VISIBLE);
            description.setText(task.description);
        }

        if (!TextUtils.isEmpty(task.url)) {
            root.setOnClickListener(v -> articleOpener.openArticle(task.url));
        } else {
            root.setClickable(false);
            root.setFocusable(false);
        }

        doneButton.setOnClickListener(v -> completeTodoTask(task, doneButton));
    }

    private String buildTodoMeta(TodoistTask task) {
        List<String> parts = new ArrayList<>();
        if (hasTodoDue(task)) {
            parts.add("Due " + formatTodoDue(task));
        }
        if (!TextUtils.isEmpty(task.sectionName)) {
            parts.add(task.sectionName);
        }
        if (task.priority > 1) {
            parts.add("P" + task.priority);
        }
        return parts.isEmpty() ? "No due date" : TextUtils.join("  •  ", parts);
    }

    private String formatTodoDue(TodoistTask task) {
        if (task == null || task.due == null) {
            return "";
        }
        if (!TextUtils.isEmpty(task.due.string)) {
            return task.due.string;
        }
        if (!TextUtils.isEmpty(task.due.datetime)) {
            long dueTime = parseTodoistDateTime(task.due.datetime);
            if (dueTime != Long.MAX_VALUE) {
                Date dueDate = new Date(dueTime);
                DateFormat dateFormat = android.text.format.DateFormat.getMediumDateFormat(context);
                DateFormat timeFormat = android.text.format.DateFormat.getTimeFormat(context);
                return dateFormat.format(dueDate) + " " + timeFormat.format(dueDate);
            }
        }
        if (!TextUtils.isEmpty(task.due.date)) {
            long dueTime = parseTodoistDate(task.due.date);
            if (dueTime != Long.MAX_VALUE) {
                return android.text.format.DateFormat.getMediumDateFormat(context).format(new Date(dueTime));
            }
            return task.due.date;
        }
        return "";
    }

    private void showTodoistHint(long timestamp, boolean cached) {
        if (timestamp <= 0) {
            todoistHint.setVisibility(View.GONE);
            return;
        }
        String prefix = cached ? "Showing cached Todoist tasks" : "Updated";
        todoistHint.setText(prefix + " " + relativeTimeFormatter.formatRelativeTime(timestamp));
        todoistHint.setVisibility(View.VISIBLE);
    }

    private String safeString(String value) {
        return value == null ? "" : value;
    }

    private void showAddTodoDialog() {
        if (!config.isTodoistConfigured()) {
            settingsOpener.openSettings();
            return;
        }

        View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_text_input, null);
        TextInputLayout inputLayout = (TextInputLayout) dialogView;
        TextInputEditText input = dialogView.findViewById(R.id.dialog_text_input);
        inputLayout.setHint(context.getString(R.string.dialog_add_todo_hint));
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);

        new AlertDialog.Builder(context)
                .setTitle(R.string.dialog_add_todo_title)
                .setView(dialogView)
                .setPositiveButton(R.string.add, (dialog, which) -> {
                    String content = input.getText() == null ? "" : input.getText().toString().trim();
                    if (!content.isEmpty()) {
                        addTodoTask(content);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void addTodoTask(String content) {
        todoistLoading.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            try {
                TodoistClient client = new TodoistClient(config.getTodoistApiKey());
                client.addTask(config.getTodoistProjectId(), content);
                mainThreadPoster.post(() -> {
                    Toast.makeText(context, "Todo added", Toast.LENGTH_SHORT).show();
                    refreshData();
                });
            } catch (Exception e) {
                Log.e(TAG, "Error adding Todoist task", e);
                mainThreadPoster.post(() -> {
                    todoistLoading.setVisibility(View.GONE);
                    Toast.makeText(context, "Could not add todo", Toast.LENGTH_SHORT).show();
                    actionFailureNotifier.showTodoFailure(
                            "Couldn't add todo",
                            "Breakfast couldn't reach Todoist. Try again when you're back online.");
                });
            }
        });
    }

    private void completeTodoTask(TodoistTask task, MaterialButton doneButton) {
        doneButton.setEnabled(false);
        executor.execute(() -> {
            try {
                TodoistClient client = new TodoistClient(config.getTodoistApiKey());
                client.closeTask(task.id);
                mainThreadPoster.post(() -> {
                    Toast.makeText(context, "Completed: " + task.content, Toast.LENGTH_SHORT).show();
                    refreshData();
                });
            } catch (Exception e) {
                Log.e(TAG, "Error completing Todoist task", e);
                mainThreadPoster.post(() -> {
                    doneButton.setEnabled(true);
                    Toast.makeText(context, "Could not complete todo", Toast.LENGTH_SHORT).show();
                    actionFailureNotifier.showTodoFailure(
                            "Couldn't mark todo done",
                            "Breakfast couldn't update Todoist. Try again when you're back online.");
                });
            }
        });
    }

}
