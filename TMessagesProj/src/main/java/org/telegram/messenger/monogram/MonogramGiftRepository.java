package org.telegram.messenger.monogram;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

/** Server-backed free MONOGRAM collectibles and profile username assets. */
public final class MonogramGiftRepository {
    private MonogramGiftRepository() {}

    public static void loadCatalog(Context context, MonogramBackend.Callback<JSONArray> callback) {
        MonogramBackend.loadGiftCatalog(context, callback);
    }

    public static void claim(Context context, String giftSlug, MonogramBackend.Callback<JSONObject> callback) {
        MonogramBackend.claimGift(context, giftSlug, callback);
    }

    public static void loadOwned(Context context, MonogramBackend.Callback<JSONArray> callback) {
        MonogramBackend.loadMyGifts(context, callback);
    }

    public static void equip(Context context, String giftId, MonogramBackend.Callback<JSONObject> callback) {
        MonogramBackend.equipGift(context, giftId, callback);
    }

    public static void claimUsername(Context context, String username, MonogramBackend.Callback<JSONObject> callback) {
        MonogramBackend.claimCollectibleUsername(context, username, callback);
    }

    public static void resolveArtwork(Context context, String assetKey, MonogramBackend.Callback<String> callback) {
        MonogramBackend.createSignedMediaUrl(context, assetKey, callback);
    }
}
