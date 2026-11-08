package org.telegram.messenger.monogram;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.NotificationCenter;

public class MonogramEconomy {
    private static final String PREFS_NAME = "monogram_economy";
    private static final String KEY_NFT_PRICE = "nft_price";
    private static final String KEY_PREMIUM_PRICE = "premium_price";
    private static final String KEY_PREMIUM_ENABLED = "premium_enabled";
    private static final String KEY_UPGRADE_GIFT_PRICE = "upgrade_gift_price";

    public static final long DEFAULT_NFT_PRICE = 0L;
    public static final long DEFAULT_PREMIUM_PRICE = 0L;
    public static final boolean DEFAULT_PREMIUM_ENABLED = true;

    private static volatile MonogramEconomy instance;
    private final SharedPreferences preferences;

    private long nftPrice;
    private long premiumPrice;
    private boolean premiumEnabled;
    private long upgradeGiftPrice;

    public static MonogramEconomy getInstance() {
        if (instance == null) {
            synchronized (MonogramEconomy.class) {
                if (instance == null) {
                    instance = new MonogramEconomy();
                }
            }
        }
        return instance;
    }

    private MonogramEconomy() {
        Context context = ApplicationLoader.applicationContext;
        preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        // MONOGRAM's launch economy is intentionally free. Ignore legacy local
        // prices so an older installation cannot accidentally retain paid prices.
        nftPrice = DEFAULT_NFT_PRICE;
        premiumPrice = DEFAULT_PREMIUM_PRICE;
        premiumEnabled = DEFAULT_PREMIUM_ENABLED;
        upgradeGiftPrice = 0L;
        preferences.edit()
                .putLong(KEY_NFT_PRICE, DEFAULT_NFT_PRICE)
                .putLong(KEY_PREMIUM_PRICE, DEFAULT_PREMIUM_PRICE)
                .putBoolean(KEY_PREMIUM_ENABLED, DEFAULT_PREMIUM_ENABLED)
                .putLong(KEY_UPGRADE_GIFT_PRICE, 0L)
                .apply();
    }

    public synchronized long getNftPrice() {
        return nftPrice;
    }

    public synchronized void setNftPrice(long price) {
        this.nftPrice = DEFAULT_NFT_PRICE;
        preferences.edit().putLong(KEY_NFT_PRICE, DEFAULT_NFT_PRICE).apply();
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.starGiftsLoaded);
    }

    public synchronized long getPremiumPrice() {
        return premiumPrice;
    }

    public synchronized void setPremiumPrice(long price) {
        this.premiumPrice = DEFAULT_PREMIUM_PRICE;
        preferences.edit().putLong(KEY_PREMIUM_PRICE, DEFAULT_PREMIUM_PRICE).apply();
    }

    public synchronized boolean isPremiumEnabled() {
        return premiumEnabled;
    }

    public synchronized void setPremiumEnabled(boolean enabled) {
        this.premiumEnabled = DEFAULT_PREMIUM_ENABLED;
        preferences.edit().putBoolean(KEY_PREMIUM_ENABLED, DEFAULT_PREMIUM_ENABLED).apply();
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.currentUserPremiumStatusChanged);
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.premiumStatusChangedGlobal);
    }

    public synchronized long getUpgradeGiftPrice() {
        return upgradeGiftPrice;
    }

    public synchronized void setUpgradeGiftPrice(long price) {
        this.upgradeGiftPrice = 0L;
        preferences.edit().putLong(KEY_UPGRADE_GIFT_PRICE, 0L).apply();
    }

    public synchronized void resetDefaults() {
        setNftPrice(DEFAULT_NFT_PRICE);
        setPremiumPrice(DEFAULT_PREMIUM_PRICE);
        setPremiumEnabled(DEFAULT_PREMIUM_ENABLED);
        setUpgradeGiftPrice(0L);
    }
}
