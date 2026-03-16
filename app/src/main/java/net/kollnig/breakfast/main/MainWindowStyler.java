package net.kollnig.breakfast.main;

import android.app.Activity;
import android.content.res.Configuration;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowInsetsController;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import net.kollnig.breakfast.R;

public final class MainWindowStyler {
    private MainWindowStyler() {
    }

    public static void applyNavigationBarColor(Activity activity) {
        Window window = activity.getWindow();
        int backgroundColor = activity.getResources().getColor(R.color.background_main, activity.getTheme());
        window.setNavigationBarColor(backgroundColor);

        boolean isLightMode = (activity.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                int appearance = isLightMode
                        ? WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                        : 0;
                controller.setSystemBarsAppearance(
                        appearance,
                        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            View decorView = window.getDecorView();
            int flags = decorView.getSystemUiVisibility();
            if (isLightMode) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            } else {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            decorView.setSystemUiVisibility(flags);
        }
    }

    public static void applySystemBarPadding(View rootLayout) {
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout, (view, insets) -> {
            Insets navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars());
            Insets statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars());
            view.setPadding(
                    view.getPaddingLeft(),
                    statusBarInsets.top,
                    view.getPaddingRight(),
                    navBarInsets.bottom);
            return insets;
        });
    }
}
