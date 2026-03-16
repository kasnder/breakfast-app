package net.kollnig.breakfast.dashboard;

import net.kollnig.breakfast.*;
import net.kollnig.breakfast.calendar.*;
import net.kollnig.breakfast.dashboard.*;
import net.kollnig.breakfast.news.*;
import net.kollnig.breakfast.social.*;
import net.kollnig.breakfast.todoist.*;
import net.kollnig.breakfast.weather.*;

import android.view.View;
import android.widget.TextView;

public class EmailDashboardModule {
    private final TextView emailNotes;

    public EmailDashboardModule(View rootView) {
        this.emailNotes = rootView.findViewById(R.id.email_notes);
    }

    public void render() {
        emailNotes.setText(R.string.module_email_not_implemented);
    }

    public CharSequence getSummaryText() {
        return emailNotes.getText();
    }
}
