package org.telegram.messenger.monogram;

import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MonogramAdminActivity extends UniversalFragment {

    private static final int ID_DASHBOARD_INFO = 101;
    private static final int ID_OWNER_INFO = 102;
    private static final int ID_USERS_LIST_HEADER = 103;

    private static final int ID_ECONOMY_NFT_PRICE = 201;
    private static final int ID_ECONOMY_PREMIUM_PRICE = 202;
    private static final int ID_ECONOMY_PREMIUM_TOGGLE = 203;
    private static final int ID_ECONOMY_RESET = 204;

    private static final int ID_GIFT_PRICE_SET = 301;
    private static final int ID_GIFT_UPGRADE_SET = 302;

    private static final int ID_PREMIUM_FREE_TOGGLE = 401;

    private static final int ID_APP_INFO_VERSION = 501;

    private static final int ID_DEV_MODE_TOGGLE = 601;
    private static final int ID_DEV_RESET_USERS = 602;

    @Override
    public boolean onFragmentCreate() {
        // Owner/Administrator authorization guard
        if (!MonogramAuthManager.isCurrentAccountOwner(currentAccount)) {
            AndroidUtilities.runOnUIThread(() -> {
                if (getParentActivity() != null) {
                    Toast.makeText(getParentActivity(), "Access Denied: MONOGRAM Owner required", Toast.LENGTH_SHORT).show();
                }
                finishFragment();
            });
            return false;
        }
        return super.onFragmentCreate();
    }

    @Override
    protected boolean useSimpleActionBar() {
        return true;
    }

    @Override
    public View createView(Context context) {
        setHasOwnBackground(true);
        fragmentView = super.createView(context);
        listView.setSections();
        listView.setSectionsDrawBackground(true);
        return fragmentView;
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Override
    protected CharSequence getTitle() {
        return "MONOGRAM Admin Panel";
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        MonogramConfig config = MonogramConfig.getInstance();
        MonogramEconomy economy = MonogramEconomy.getInstance();
        TLRPC.User currentUser = UserConfig.getInstance(currentAccount).getCurrentUser();

        // 1. DASHBOARD
        items.add(UItem.asHeader("1. Dashboard"));
        items.add(UItem.asButton(ID_DASHBOARD_INFO,
                "Registered Users: " + config.getRegisteredUsersCount() + " | Economy: Free Stars mode",
                "Status: Active • Owner Role Verified"));
        items.add(UItem.asShadow(null));

        // 2. ADMINISTRATOR PROFILE
        items.add(UItem.asHeader("2. Administrator Profile"));
        String adminName = currentUser != null ? UserObject.getUserName(currentUser) : "Owner";
        String adminPhone = currentUser != null && currentUser.phone != null ? currentUser.phone : "None";
        String adminUsername = currentUser != null && currentUser.username != null ? "@" + currentUser.username : "None";
        long adminId = currentUser != null ? currentUser.id : config.getOwnerUserId();

        items.add(UItem.asButton(ID_OWNER_INFO,
                adminName + " (ID: " + adminId + ")",
                "Username: " + adminUsername + " • Phone: " + adminPhone + " • MONOGRAM Badge: Active"));
        items.add(UItem.asShadow(null));

        // 3. USER MANAGEMENT
        items.add(UItem.asHeader("3. User Management"));
        List<MonogramConfig.MonogramUserRecord> users = config.getRegisteredUsers();
        if (users.isEmpty()) {
            items.add(UItem.asButton(0, "No locally registered users found", "Accounts created will appear here"));
        } else {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
            for (int i = 0; i < users.size(); i++) {
                MonogramConfig.MonogramUserRecord r = users.get(i);
                String sub = "ID: " + r.userId + (r.username.isEmpty() ? "" : " • @" + r.username) +
                        " • " + sdf.format(new Date(r.createdAt)) + (r.isOwner ? " [OWNER]" : "");
                items.add(UItem.asButton(1000 + i, (r.firstName + " " + r.lastName).trim(), sub));
            }
        }
        items.add(UItem.asShadow(null));

        // 4. ECONOMY CONFIGURATION
        items.add(UItem.asHeader("4. Economy Configuration"));
        items.add(UItem.asButton(0, "NFT Gifts", "FREE • 0 Stars • 1,000,000 copies per design"));
        items.add(UItem.asButton(0, "MONOGRAM Premium", "FREE • Always enabled"));
        items.add(UItem.asButton(0, "Gift Upgrades", "FREE • 0 Stars"));
        items.add(UItem.asButton(ID_ECONOMY_RESET, "Reset Economy", "Restores the free MONOGRAM economy"));
        items.add(UItem.asShadow(null));

        // 5. NFT GIFT CONFIGURATION
        items.add(UItem.asHeader("5. NFT Gift Configuration"));
        items.add(UItem.asButton(0, "Gift Catalog", "All designs are free; supply limit is 1,000,000 per design"));
        items.add(UItem.asButton(0, "Gift Upgrades", "All upgrades are free"));
        items.add(UItem.asShadow(null));

        // 6. PREMIUM CONFIGURATION
        items.add(UItem.asHeader("6. Premium Configuration"));
        items.add(UItem.asButton(0, "Premium Status", "Active • Free for every MONOGRAM account"));
        items.add(UItem.asShadow(null));

        // 7. APPLICATION INFORMATION
        items.add(UItem.asHeader("7. Application Information"));
        items.add(UItem.asButton(ID_APP_INFO_VERSION, "MONOGRAM Core Version: 1.0.0", "Telegram Base: " + BuildVars.BUILD_VERSION_STRING));
        items.add(UItem.asShadow(null));

        // 8. DEVELOPMENT SETTINGS
        items.add(UItem.asHeader("8. Development Settings"));
        items.add(UItem.asCheck(ID_DEV_MODE_TOGGLE, "Development Mode Active").setChecked(config.isDevModeEnabled()));
        items.add(UItem.asButton(ID_DEV_RESET_USERS, "Clear Registered Users Registry", "Clears the local registered user list"));
        items.add(UItem.asShadow(null));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        MonogramEconomy economy = MonogramEconomy.getInstance();
        MonogramConfig config = MonogramConfig.getInstance();

        switch (item.id) {
            case ID_ECONOMY_RESET:
                economy.resetDefaults();
                if (listView != null && listView.adapter != null) {
                    listView.adapter.update(true);
                }
                showToast("Economy reset to 0 Stars defaults");
                break;

            case ID_DEV_MODE_TOGGLE:
                boolean newDev = !config.isDevModeEnabled();
                config.setDevModeEnabled(newDev);
                if (listView != null && listView.adapter != null) {
                    listView.adapter.update(true);
                }
                showToast("Development mode: " + (newDev ? "Enabled" : "Disabled"));
                break;

            case ID_DEV_RESET_USERS:
                if (getParentActivity() != null) {
                    new AlertDialog.Builder(getParentActivity())
                            .setTitle("Reset Registered Users")
                            .setMessage("Are you sure you want to clear the local registry of users? Your current session will remain active.")
                            .setPositiveButton("Clear", (dialog, which) -> {
                                config.resetDevelopmentData();
                                if (listView != null && listView.adapter != null) {
                                    listView.adapter.update(true);
                                }
                                showToast("User registry cleared");
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                }
                break;

            case ID_DASHBOARD_INFO:
            case ID_OWNER_INFO:
            case ID_APP_INFO_VERSION:
                showToast(item.text != null ? item.text.toString() : "MONOGRAM Admin");
                break;
        }
    }

    private interface PriceCallback {
        void onPriceEntered(long price);
    }

    private void showPriceInputDialog(String title, long currentPrice, PriceCallback callback) {
        if (getParentActivity() == null) return;

        final EditText input = new EditText(getParentActivity());
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(currentPrice));
        input.setSelection(input.getText().length());

        FrameLayout container = new FrameLayout(getParentActivity());
        container.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(10), AndroidUtilities.dp(20), AndroidUtilities.dp(10));
        container.addView(input, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        new AlertDialog.Builder(getParentActivity())
                .setTitle(title)
                .setView(container)
                .setPositiveButton("Save", (dialog, which) -> {
                    String str = input.getText().toString().trim();
                    long price = 0;
                    try {
                        price = Long.parseLong(str);
                    } catch (Exception ignored) {}
                    if (callback != null) {
                        callback.onPriceEntered(price);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    private void showToast(String message) {
        if (getParentActivity() != null) {
            Toast.makeText(getParentActivity(), message, Toast.LENGTH_SHORT).show();
        }
    }
}
