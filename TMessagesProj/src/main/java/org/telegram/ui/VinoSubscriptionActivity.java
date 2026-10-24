package org.telegram.ui;

import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.vinogram.ProxySubscription;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

public class VinoSubscriptionActivity extends BaseFragment {
    private final ProxySubscription subscription = ProxySubscription.get();
    private final Runnable listener = this::refreshState;
    private EditTextBoldCursor url;
    private TextView status;
    private TextCheckCell enabled;
    private Button refresh;

    @Override
    public View createView(Context context) {
        subscription.init();
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle("Subscribtion");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) { if (id == -1) finishFragment(); }
        });
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(20), AndroidUtilities.dp(20), AndroidUtilities.dp(28));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        TextView description = new TextView(context);
        description.setText(LocaleController.getString(R.string.VinoSubscriptionInfo));
        description.setTextSize(15);
        description.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        content.addView(description, LayoutHelper.createLinear(-1, -2, 0, 0, 0, 20));
        url = new EditTextBoldCursor(context);
        url.setTextSize(16); url.setSingleLine(true);
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        url.setHint("https://…"); url.setText(subscription.url());
        url.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        url.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        content.addView(url, LayoutHelper.createLinear(-1, 52));
        enabled = new TextCheckCell(context);
        enabled.setOnClickListener(view -> subscription.setEnabled(!subscription.enabled()));
        content.addView(enabled, LayoutHelper.createLinear(-1, -2));
        status = new TextView(context); status.setTextSize(14); status.setTextIsSelectable(true);
        status.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        content.addView(status, LayoutHelper.createLinear(-1, -2, 0, 12, 0, 20));
        refresh = new Button(context);
        refresh.setText(LocaleController.getString(R.string.VinoSubscriptionRefresh)); refresh.setAllCaps(false);
        refresh.setOnClickListener(view -> { AndroidUtilities.hideKeyboard(url); subscription.refresh(url.getText().toString()); });
        content.addView(refresh, LayoutHelper.createLinear(-1, 48));
        Button remove = new Button(context);
        remove.setText(LocaleController.getString(R.string.VinoSubscriptionRemove)); remove.setAllCaps(false);
        remove.setOnClickListener(view -> { subscription.remove(); url.setText(""); });
        content.addView(remove, LayoutHelper.createLinear(-1, 48, 0, 12, 0, 0));
        fragmentView = scroll; refreshState();
        return fragmentView;
    }

    @Override public void onResume() { super.onResume(); subscription.addListener(listener); refreshState(); }
    @Override public void onPause() { subscription.removeListener(listener); super.onPause(); }
    @Override public void onFragmentDestroy() { subscription.removeListener(listener); super.onFragmentDestroy(); }

    private void refreshState() {
        if (status == null) return;
        status.setText(subscription.status());
        enabled.setTextAndCheck(LocaleController.getString(R.string.VinoSubscriptionUse), subscription.enabled(), false);
        enabled.setEnabled(subscription.hasServers() || subscription.enabled());
        enabled.setAlpha(enabled.isEnabled() ? 1 : .5f);
        refresh.setEnabled(!subscription.busy());
    }
}
