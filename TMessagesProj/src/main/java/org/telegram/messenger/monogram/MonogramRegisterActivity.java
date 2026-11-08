package org.telegram.messenger.monogram;

import android.animation.AnimatorSet;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.ImageUpdater;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.OutlineTextContainerView;
import org.telegram.ui.Components.RadialProgressView;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.MainTabsActivity;

public class MonogramRegisterActivity extends BaseFragment implements ImageUpdater.ImageUpdaterDelegate {

    private EditTextBoldCursor firstNameField;
    private EditTextBoldCursor lastNameField;
    private EditTextBoldCursor usernameField;
    private EditTextBoldCursor phoneField;

    private OutlineTextContainerView firstNameContainer;
    private OutlineTextContainerView lastNameContainer;
    private OutlineTextContainerView usernameContainer;
    private OutlineTextContainerView phoneContainer;

    private BackupImageView avatarImage;
    private AvatarDrawable avatarDrawable;
    private View avatarOverlay;
    private RadialProgressView avatarProgressView;

    private ImageUpdater imageUpdater;
    private TLRPC.FileLocation avatarLocation;

    private TextView startButton;
    private boolean isCreating = false;

    public MonogramRegisterActivity() {
        super();
    }

    public MonogramRegisterActivity(Bundle args) {
        super(args);
    }

    @Override
    public boolean onFragmentCreate() {
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Create MONOGRAM Account");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        fragmentView = new ScrollView(context);
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

        LinearLayout contentLayout = new LinearLayout(context);
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        contentLayout.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(20), AndroidUtilities.dp(24), AndroidUtilities.dp(36));

        // Subtitle / Intro
        TextView titleDesc = new TextView(context);
        titleDesc.setText("Set up your independent profile. No SMS or telephone verification required.");
        titleDesc.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        titleDesc.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        titleDesc.setGravity(Gravity.CENTER_HORIZONTAL);
        contentLayout.addView(titleDesc, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 20));

        // Avatar setup
        imageUpdater = new ImageUpdater(false, ImageUpdater.FOR_TYPE_USER, false);
        imageUpdater.setOpenWithFrontfaceCamera(true);
        imageUpdater.setSearchAvailable(false);
        imageUpdater.setUploadAfterSelect(false);
        imageUpdater.parentFragment = this;
        imageUpdater.setDelegate(this);

        FrameLayout avatarContainer = new FrameLayout(context);
        contentLayout.addView(avatarContainer, LayoutHelper.createLinear(84, 84, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 24));

        avatarDrawable = new AvatarDrawable();
        avatarDrawable.setAvatarType(AvatarDrawable.AVATAR_TYPE_REGISTER);
        avatarDrawable.setInfo(5, null, null);

        avatarImage = new BackupImageView(context);
        avatarImage.setRoundRadius(AndroidUtilities.dp(42));
        avatarImage.setImageDrawable(avatarDrawable);
        avatarContainer.addView(avatarImage, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(0x55000000);

        avatarOverlay = new View(context) {
            @Override
            protected void onDraw(Canvas canvas) {
                if (avatarProgressView != null && avatarProgressView.getVisibility() == VISIBLE) {
                    paint.setAlpha((int) (0x55 * avatarImage.getImageReceiver().getCurrentAlpha() * avatarProgressView.getAlpha()));
                    canvas.drawCircle(getMeasuredWidth() / 2.0f, getMeasuredHeight() / 2.0f, getMeasuredWidth() / 2.0f, paint);
                }
            }
        };
        avatarContainer.addView(avatarOverlay, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        avatarOverlay.setOnClickListener(v -> {
            imageUpdater.openMenu(avatarLocation != null, () -> {
                avatarLocation = null;
                avatarImage.setImage(null, null, avatarDrawable, null);
            }, null, 0);
        });

        avatarProgressView = new RadialProgressView(context);
        avatarProgressView.setSize(AndroidUtilities.dp(30));
        avatarProgressView.setProgressColor(0xffffffff);
        avatarProgressView.setVisibility(View.GONE);
        avatarContainer.addView(avatarProgressView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // First Name Input
        firstNameContainer = new OutlineTextContainerView(context);
        firstNameContainer.setText("First name (required)");
        firstNameField = new EditTextBoldCursor(context);
        firstNameField.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        firstNameField.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        firstNameField.setSingleLine(true);
        firstNameField.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        firstNameField.setBackground(null);
        firstNameField.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16));
        firstNameField.setOnFocusChangeListener((v, hasFocus) -> firstNameContainer.animateSelection(hasFocus ? 1f : 0f));
        firstNameContainer.addView(firstNameField, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        firstNameField.setOnEditorActionListener((textView, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_NEXT) {
                lastNameField.requestFocus();
                return true;
            }
            return false;
        });
        contentLayout.addView(firstNameContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 14));

        // Last Name Input
        lastNameContainer = new OutlineTextContainerView(context);
        lastNameContainer.setText("Last name (optional)");
        lastNameField = new EditTextBoldCursor(context);
        lastNameField.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        lastNameField.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        lastNameField.setSingleLine(true);
        lastNameField.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        lastNameField.setBackground(null);
        lastNameField.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16));
        lastNameField.setOnFocusChangeListener((v, hasFocus) -> lastNameContainer.animateSelection(hasFocus ? 1f : 0f));
        lastNameContainer.addView(lastNameField, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        lastNameField.setOnEditorActionListener((textView, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_NEXT) {
                usernameField.requestFocus();
                return true;
            }
            return false;
        });
        contentLayout.addView(lastNameContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 14));

        // Username Input
        usernameContainer = new OutlineTextContainerView(context);
        usernameContainer.setText("Username (e.g. monogram_user)");
        usernameField = new EditTextBoldCursor(context);
        usernameField.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        usernameField.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        usernameField.setSingleLine(true);
        usernameField.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        usernameField.setBackground(null);
        usernameField.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16));
        usernameField.setOnFocusChangeListener((v, hasFocus) -> usernameContainer.animateSelection(hasFocus ? 1f : 0f));
        usernameContainer.addView(usernameField, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        usernameField.setOnEditorActionListener((textView, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_NEXT) {
                phoneField.requestFocus();
                return true;
            }
            return false;
        });
        contentLayout.addView(usernameContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 14));

        // Display Phone (Profile info only)
        phoneContainer = new OutlineTextContainerView(context);
        phoneContainer.setText("Display Phone (profile only, optional)");
        phoneField = new EditTextBoldCursor(context);
        phoneField.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        phoneField.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        phoneField.setSingleLine(true);
        phoneField.setInputType(InputType.TYPE_CLASS_PHONE);
        phoneField.setImeOptions(EditorInfo.IME_ACTION_DONE);
        phoneField.setBackground(null);
        phoneField.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16));
        phoneField.setOnFocusChangeListener((v, hasFocus) -> phoneContainer.animateSelection(hasFocus ? 1f : 0f));
        phoneContainer.addView(phoneField, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        phoneField.setOnEditorActionListener((textView, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submitRegistration();
                return true;
            }
            return false;
        });
        // MONOGRAM numbers are generated by the server; no phone number is required.

        // Create Account Button
        startButton = new TextView(context);
        startButton.setText("Start Messaging with MONOGRAM");
        startButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        startButton.setTypeface(AndroidUtilities.bold());
        startButton.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
        startButton.setGravity(Gravity.CENTER);
        startButton.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(8), Theme.getColor(Theme.key_featuredStickers_addButton), Theme.getColor(Theme.key_featuredStickers_addButtonPressed)));
        startButton.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(14), AndroidUtilities.dp(16), AndroidUtilities.dp(14));
        startButton.setOnClickListener(v -> submitRegistration());
        contentLayout.addView(startButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));

        ((ScrollView) fragmentView).addView(contentLayout, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return fragmentView;
    }

    private void submitRegistration() {
        if (isCreating) return;

        String firstName = firstNameField != null ? firstNameField.getText().toString().trim() : "";
        String lastName = lastNameField != null ? lastNameField.getText().toString().trim() : "";
        String username = usernameField != null ? usernameField.getText().toString().trim() : "";
        String phone = "";

        if (TextUtils.isEmpty(firstName)) {
            if (getParentActivity() != null) {
                new AlertDialog.Builder(getParentActivity())
                        .setTitle("Name Required")
                        .setMessage("Please enter your first name.")
                        .setPositiveButton("OK", null)
                        .show();
            }
            return;
        }

        isCreating = true;
        startButton.setEnabled(false);

        MonogramAuthManager.createAccount(
                currentAccount,
                firstName,
                lastName,
                username,
                phone,
                avatarLocation,
                new MonogramAuthManager.AuthCallback() {
                    @Override
                    public void onSuccess(TLRPC.User user, boolean isOwner) {
                        isCreating = false;
                        if (getParentLayout() != null) {
                            MainTabsActivity mainTabsActivity = new MainTabsActivity();
                            getParentLayout().removeAllFragments();
                            getParentLayout().addFragmentToStack(mainTabsActivity);
                        }
                    }

                    @Override
                    public void onError(String error) {
                        isCreating = false;
                        startButton.setEnabled(true);
                        if (getParentActivity() != null) {
                            new AlertDialog.Builder(getParentActivity())
                                    .setTitle("Error")
                                    .setMessage(error)
                                    .setPositiveButton("OK", null)
                                    .show();
                        }
                    }
                }
        );
    }

    @Override
    public void didUploadPhoto(TLRPC.InputFile file, TLRPC.InputFile smallFile, double videoTimestamp, String videoPath, TLRPC.PhotoSize bigSize, TLRPC.PhotoSize smallSize, boolean isVideo, TLRPC.VideoSize emojiMarkup) {
        AndroidUtilities.runOnUIThread(() -> {
            if (smallSize != null) {
                avatarLocation = smallSize.location;
                avatarImage.setImage(ImageLocation.getForLocal(avatarLocation), "50_50", avatarDrawable, null);
            } else if (bigSize != null) {
                avatarLocation = bigSize.location;
                avatarImage.setImage(ImageLocation.getForLocal(avatarLocation), "50_50", avatarDrawable, null);
            }
        });
    }

    @Override
    public String getInitialSearchString() {
        return null;
    }
}
